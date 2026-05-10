package io.github.jcjorel.sftpconnectorhelper.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jcjorel.sftpconnectorhelper.SftpConnectorHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import software.amazon.awssdk.services.transfer.TransferClient;
import software.amazon.awssdk.services.transfer.model.StartFileTransferRequest;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;

/**
 * Base class for integration tests. Manages:
 * - SftpConnectorHelper instance
 * - Temporary SQS queue + EventBridge rule for capturing enriched events
 * - DynamoDB cleanup tracking
 * - Event polling utilities
 * - Orphaned test resource cleanup (prefix-based)
 */
@ExtendWith(IntegrationTestBase.FailedTestRecordPreserver.class)
public abstract class IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationTestBase.class);

    private static final String RESOURCE_PREFIX = "integ-test-java-";
    private static final String RULE_PREFIX = RESOURCE_PREFIX + "rule-";
    private static final String RAW_QUEUE_PREFIX = RESOURCE_PREFIX + "raw-";
    private static final String DEFAULT_BUS_RULE_PREFIX = RESOURCE_PREFIX + "rule-default-";

    protected static final String CONNECTOR_ID = prop("CONNECTOR_ID", "c-0123456789abcdef0");
    protected static final String TABLE_NAME = prop("TABLE_NAME", "sftp-connector-helper");
    protected static final String EVENT_BUS_NAME = prop("EVENT_BUS_NAME", "sftp-connector-helper-bus");
    protected static final String TEST_S3_BUCKET = prop("TEST_S3_BUCKET", "sftp-connector-helper-test-123456789012");
    protected static final String REMOTE_DIR = prop("REMOTE_DIR", "REDACTED_PATH/test_sftp_connector");
    protected static final Region REGION = Region.of(prop("AWS_REGION", "eu-central-1"));

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    protected static SftpConnectorHelper helper;
    protected static DynamoDbClient dynamoDb;
    protected static SqsClient sqsClient;
    protected static EventBridgeClient ebClient;
    protected static S3Client s3Client;
    protected static TransferClient transferClient;

    private static String queueUrl;
    private static String queueArn;
    private static String ruleName;
    protected static String rawQueueUrl;
    private static String rawQueueArn;
    private static String defaultBusRuleName;

    /** Job IDs to clean up from DynamoDB after all tests (only from passing tests). */
    protected static final List<String> jobIdsToCleanup = new CopyOnWriteArrayList<>();

    /** Job IDs associated with the currently running test. */
    private static final List<String> currentTestJobIds = new CopyOnWriteArrayList<>();

    /** Job IDs from failed tests — never cleaned up (preserved for debugging). */
    protected static final Set<String> failedTestJobIds = ConcurrentHashMap.newKeySet();

    /** Queue URLs already deleted in this JVM session (SQS has 60s propagation delay). */
    private static final Set<String> deletedQueueUrls = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void logTestEntry(TestInfo testInfo) {
        LOG.info(">>> Entering test: {}", testInfo.getDisplayName());
        currentTestJobIds.clear();
    }

    /**
     * JUnit 5 TestWatcher that preserves DynamoDB records from failed tests.
     * On failure, moves currentTestJobIds into failedTestJobIds so teardown skips them.
     */
    static class FailedTestRecordPreserver implements TestWatcher {
        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            failedTestJobIds.addAll(currentTestJobIds);
            LOG.error("Test '{}' failed — preserving {} DynamoDB record(s) for debugging (table={}): {}",
                    context.getDisplayName(), currentTestJobIds.size(), TABLE_NAME,
                    String.join(", ", currentTestJobIds));
        }
    }

    @BeforeAll
    static void setupInfrastructure() {
        LOG.info("Setting up test infrastructure: region={}, connector={}, table={}, bus={}",
                REGION, CONNECTOR_ID, TABLE_NAME, EVENT_BUS_NAME);

        dynamoDb = DynamoDbClient.builder().region(REGION).build();
        sqsClient = SqsClient.builder().region(REGION).build();
        ebClient = EventBridgeClient.builder().region(REGION).build();
        s3Client = S3Client.builder().region(REGION).build();

        // Clean up any orphaned resources from previous runs
        cleanupOrphanedTestResources();

        helper = SftpConnectorHelper.builder()
                .tableName(TABLE_NAME)
                .ttlDuration(Duration.ofHours(1))
                .build();
        transferClient = helper.getTransferClient();

        // Create temporary SQS queue
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        String queueName = RESOURCE_PREFIX + sessionId;
        ruleName = RULE_PREFIX + sessionId;

        LOG.info("Creating temporary SQS queue: {}", queueName);
        CreateQueueResponse createResp = sqsClient.createQueue(CreateQueueRequest.builder()
                .queueName(queueName).build());
        queueUrl = createResp.queueUrl();

        GetQueueAttributesResponse attrs = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build());
        queueArn = attrs.attributes().get(QueueAttributeName.QUEUE_ARN);
        LOG.info("Queue created: {}", queueArn);

        // Create EventBridge rule on dedicated bus (match both real events and canary)
        LOG.info("Creating EventBridge rule: {} on bus: {}", ruleName, EVENT_BUS_NAME);
        PutRuleResponse ruleResp = ebClient.putRule(PutRuleRequest.builder()
                .name(ruleName)
                .eventBusName(EVENT_BUS_NAME)
                .eventPattern("{\"source\":[\"custom.sftp-connector-helper\",\"test.canary\"]}")
                .state(RuleState.ENABLED)
                .build());
        String ruleArn = ruleResp.ruleArn();

        // Set SQS policy allowing EventBridge
        String policy = """
                {
                  "Version":"2012-10-17",
                  "Statement":[{
                    "Effect":"Allow",
                    "Principal":{"Service":"events.amazonaws.com"},
                    "Action":"sqs:SendMessage",
                    "Resource":"%s",
                    "Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}
                  }]
                }""".formatted(queueArn, ruleArn);
        sqsClient.setQueueAttributes(SetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributes(Map.of(QueueAttributeName.POLICY, policy))
                .build());

        // Add SQS as target
        ebClient.putTargets(PutTargetsRequest.builder()
                .rule(ruleName)
                .eventBusName(EVENT_BUS_NAME)
                .targets(Target.builder().id("test-queue").arn(queueArn).build())
                .build());

        // Create raw SQS queue for capturing events from the default bus
        String rawQueueName = RAW_QUEUE_PREFIX + sessionId;
        defaultBusRuleName = DEFAULT_BUS_RULE_PREFIX + sessionId;

        LOG.info("Creating raw SQS queue: {}", rawQueueName);
        CreateQueueResponse rawCreateResp = sqsClient.createQueue(CreateQueueRequest.builder()
                .queueName(rawQueueName).build());
        rawQueueUrl = rawCreateResp.queueUrl();

        GetQueueAttributesResponse rawAttrs = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                .queueUrl(rawQueueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
                .build());
        rawQueueArn = rawAttrs.attributes().get(QueueAttributeName.QUEUE_ARN);
        LOG.info("Raw queue created: {}", rawQueueArn);

        // Create EventBridge rule on default bus for raw Transfer Family events
        LOG.info("Creating EventBridge rule: {} on default bus", defaultBusRuleName);
        PutRuleResponse defaultRuleResp = ebClient.putRule(PutRuleRequest.builder()
                .name(defaultBusRuleName)
                .eventPattern("{\"source\":[\"aws.transfer\"],\"detail-type\":[{\"prefix\":\"SFTP Connector\"}]}")
                .state(RuleState.ENABLED)
                .build());
        String defaultRuleArn = defaultRuleResp.ruleArn();

        // Set SQS policy allowing both rules to send messages
        String rawPolicy = """
                {
                  "Version":"2012-10-17",
                  "Statement":[{
                    "Effect":"Allow",
                    "Principal":{"Service":"events.amazonaws.com"},
                    "Action":"sqs:SendMessage",
                    "Resource":"%s",
                    "Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}
                  }]
                }""".formatted(rawQueueArn, defaultRuleArn);
        sqsClient.setQueueAttributes(SetQueueAttributesRequest.builder()
                .queueUrl(rawQueueUrl)
                .attributes(Map.of(QueueAttributeName.POLICY, rawPolicy))
                .build());

        ebClient.putTargets(PutTargetsRequest.builder()
                .rule(defaultBusRuleName)
                .targets(Target.builder().id("raw-test-queue").arn(rawQueueArn).build())
                .build());

        // Canary warm-up: require 3 consecutive deliveries to confirm stable rule propagation
        String canaryId = "canary-" + sessionId;
        LOG.info("Sending canary events to confirm rule propagation (id={}, required=3)", canaryId);
        int[] received = {0};

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).until(() -> {
            ebClient.putEvents(PutEventsRequest.builder()
                    .entries(PutEventsRequestEntry.builder()
                            .source("test.canary")
                            .detailType("Integration Test Canary")
                            .eventBusName(EVENT_BUS_NAME)
                            .detail("{\"canary\":\"" + canaryId + "\"}")
                            .build())
                    .build());
            ReceiveMessageResponse resp = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(2).build());
            boolean found = false;
            for (Message msg : resp.messages()) {
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build());
                if (msg.body().contains(canaryId)) found = true;
            }
            if (found) {
                received[0]++;
                LOG.info("Canary received ({}/3) — rule is active", received[0]);
            } else {
                received[0] = 0;
            }
            return received[0] >= 3;
        });

        LOG.info("Test infrastructure ready. Rule ARN: {}", ruleArn);
    }

    @AfterAll
    static void teardownInfrastructure() {
        int skipped = 0;
        int deleted = 0;

        // Cleanup DynamoDB records — skip those from failed tests
        for (String jobId : jobIdsToCleanup) {
            if (failedTestJobIds.contains(jobId)) {
                skipped++;
                continue;
            }
            try {
                dynamoDb.deleteItem(DeleteItemRequest.builder()
                        .tableName(TABLE_NAME)
                        .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                        .build());
                deleted++;
            } catch (Exception ignored) {}
        }
        LOG.info("Tearing down test infrastructure. Deleted {} DynamoDB records, preserved {} from failed tests",
                deleted, skipped);

        // Clean up all test resources (current session + any stragglers)
        cleanupOrphanedTestResources();

        // Close clients
        if (helper != null) helper.close();
        if (s3Client != null) s3Client.close();
        if (sqsClient != null) sqsClient.close();
        if (ebClient != null) ebClient.close();
        if (dynamoDb != null) dynamoDb.close();
        LOG.info("Teardown complete");
    }

    /**
     * Scans for and removes any orphaned test resources (SQS queues and EventBridge rules)
     * left behind by previous test runs that were killed or failed during teardown.
     */
    private static void cleanupOrphanedTestResources() {
        LOG.info("Scanning for orphaned test resources (prefix={})", RESOURCE_PREFIX);

        // Clean up EventBridge rules on dedicated bus
        try {
            ListRulesResponse rulesResp = ebClient.listRules(ListRulesRequest.builder()
                    .eventBusName(EVENT_BUS_NAME)
                    .namePrefix(RULE_PREFIX)
                    .build());
            for (Rule rule : rulesResp.rules()) {
                LOG.info("Removing orphaned EventBridge rule: {}", rule.name());
                deleteRuleWithTargets(rule.name(), EVENT_BUS_NAME);
            }
        } catch (Exception e) {
            LOG.warn("Failed to list EventBridge rules: {}", e.getMessage());
        }

        // Clean up EventBridge rules on default bus
        try {
            ListRulesResponse defaultRulesResp = ebClient.listRules(ListRulesRequest.builder()
                    .namePrefix(DEFAULT_BUS_RULE_PREFIX)
                    .build());
            for (Rule rule : defaultRulesResp.rules()) {
                LOG.info("Removing orphaned default-bus EventBridge rule: {}", rule.name());
                deleteRuleWithTargets(rule.name(), null);
            }
        } catch (Exception e) {
            LOG.warn("Failed to list default-bus EventBridge rules: {}", e.getMessage());
        }

        // Clean up SQS queues (both regular and raw)
        try {
            ListQueuesResponse queuesResp = sqsClient.listQueues(ListQueuesRequest.builder()
                    .queueNamePrefix(RESOURCE_PREFIX)
                    .build());
            for (String orphanQueueUrl : queuesResp.queueUrls()) {
                if (deletedQueueUrls.contains(orphanQueueUrl)) continue;
                LOG.info("Removing orphaned SQS queue: {}", orphanQueueUrl);
                try {
                    sqsClient.deleteQueue(DeleteQueueRequest.builder().queueUrl(orphanQueueUrl).build());
                    deletedQueueUrls.add(orphanQueueUrl);
                } catch (Exception e) {
                    LOG.warn("Failed to delete queue {}: {}", orphanQueueUrl, e.getMessage());
                    deletedQueueUrls.add(orphanQueueUrl);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to list SQS queues: {}", e.getMessage());
        }
    }

    private static void deleteRuleWithTargets(String name, String busName) {
        try {
            ListTargetsByRuleRequest.Builder listReq = ListTargetsByRuleRequest.builder().rule(name);
            DeleteRuleRequest.Builder deleteReq = DeleteRuleRequest.builder().name(name);
            RemoveTargetsRequest.Builder removeReq = RemoveTargetsRequest.builder().rule(name);
            if (busName != null) {
                listReq.eventBusName(busName);
                deleteReq.eventBusName(busName);
                removeReq.eventBusName(busName);
            }
            ListTargetsByRuleResponse targets = ebClient.listTargetsByRule(listReq.build());
            if (!targets.targets().isEmpty()) {
                List<String> targetIds = targets.targets().stream().map(Target::id).toList();
                ebClient.removeTargets(removeReq.ids(targetIds).build());
            }
            ebClient.deleteRule(deleteReq.build());
        } catch (Exception e) {
            LOG.warn("Failed to delete rule {}: {}", name, e.getMessage());
        }
    }

    /** Track a job ID for DynamoDB cleanup. */
    protected void trackForCleanup(String jobId) {
        jobIdsToCleanup.add(jobId);
        currentTestJobIds.add(jobId);
    }

    /** Generate unique metadata JSON with a correlation ID. */
    protected String testMetadata(String testName) {
        return "{\"testId\":\"%s\",\"testName\":\"%s\",\"ts\":%d}"
                .formatted(UUID.randomUUID(), testName, System.currentTimeMillis());
    }

    /** Verify DynamoDB record has the expected metadata. */
    protected void assertMetadataInDynamoDb(String jobId, String expectedMetadata) {
        LOG.info("Verifying DynamoDB metadata for jobId: {}", jobId);
        GetItemResponse resp = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                .build());
        assert resp.hasItem() : "DynamoDB record not found for jobId: " + jobId;
        String stored = resp.item().get("metadata").s();
        assert expectedMetadata.equals(stored) : "Metadata mismatch. Expected: " + expectedMetadata + ", got: " + stored;
        LOG.info("DynamoDB metadata verified OK for jobId: {}", jobId);
    }

    /**
     * Poll the SQS queue for an enriched event matching the given job ID field and value.
     * Returns the parsed event detail node.
     */
    protected JsonNode pollForEnrichedEvent(String jobIdField, String jobIdValue, Duration timeout) {
        LOG.info("Polling for enriched event: {}={} (timeout={}s)", jobIdField, jobIdValue, timeout.toSeconds());
        List<JsonNode> found = new ArrayList<>();

        await().atMost(timeout).pollInterval(Duration.ofMillis(100)).until(() -> {
            ReceiveMessageResponse resp = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)
                    .build());
            for (Message msg : resp.messages()) {
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build());
                try {
                    JsonNode event = MAPPER.readTree(msg.body());
                    JsonNode detail = event.has("detail") && event.get("detail").isTextual()
                            ? MAPPER.readTree(event.get("detail").asText())
                            : event.get("detail");
                    if (detail != null && detail.has(jobIdField)
                            && jobIdValue.equals(detail.get(jobIdField).asText())) {
                        LOG.info("Found matching event for {}={}", jobIdField, jobIdValue);
                        found.add(detail);
                    } else {
                        LOG.debug("Received non-matching event: {}", msg.body());
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse SQS message: {}", e.getMessage());
                }
            }
            return !found.isEmpty();
        });

        LOG.info("Enriched event received for {}={}", jobIdField, jobIdValue);
        return found.get(0);
    }

    /**
     * Poll the raw SQS queue for a Transfer Family event matching the given job ID field and value.
     * Returns the parsed event detail node.
     */
    protected static JsonNode pollForRawEvent(String jobIdField, String jobIdValue, Duration timeout) {
        LOG.info("Polling for raw event: {}={} (timeout={}s)", jobIdField, jobIdValue, timeout.toSeconds());
        List<JsonNode> found = new ArrayList<>();

        await().atMost(timeout).pollInterval(Duration.ofMillis(100)).until(() -> {
            ReceiveMessageResponse resp = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(rawQueueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)
                    .build());
            for (Message msg : resp.messages()) {
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(rawQueueUrl).receiptHandle(msg.receiptHandle()).build());
                try {
                    JsonNode event = MAPPER.readTree(msg.body());
                    JsonNode detail = event.has("detail") && event.get("detail").isTextual()
                            ? MAPPER.readTree(event.get("detail").asText())
                            : event.get("detail");
                    if (detail != null && detail.has(jobIdField)
                            && jobIdValue.equals(detail.get(jobIdField).asText())) {
                        LOG.info("Found matching raw event for {}={}", jobIdField, jobIdValue);
                        found.add(detail);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse raw SQS message: {}", e.getMessage());
                }
            }
            return !found.isEmpty();
        });

        LOG.info("Raw event received for {}={}", jobIdField, jobIdValue);
        return found.get(0);
    }

    /**
     * Poll the raw SQS queue for multiple events matching the given field and value.
     * Returns all matching event detail nodes once the expected count is reached.
     */
    protected List<JsonNode> pollForAllRawEvents(String jobIdField, String jobIdValue, int expectedCount, Duration timeout) {
        LOG.info("Polling for {} raw events: {}={} (timeout={}s)", expectedCount, jobIdField, jobIdValue, timeout.toSeconds());
        List<JsonNode> found = new ArrayList<>();

        await().atMost(timeout).pollInterval(Duration.ofMillis(100)).until(() -> {
            ReceiveMessageResponse resp = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(rawQueueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)
                    .build());
            for (Message msg : resp.messages()) {
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(rawQueueUrl).receiptHandle(msg.receiptHandle()).build());
                try {
                    JsonNode event = MAPPER.readTree(msg.body());
                    JsonNode detail = event.has("detail") && event.get("detail").isTextual()
                            ? MAPPER.readTree(event.get("detail").asText())
                            : event.get("detail");
                    if (detail != null && detail.has(jobIdField)
                            && jobIdValue.equals(detail.get(jobIdField).asText())) {
                        found.add(detail);
                        LOG.info("Found matching raw event {}/{} for {}={}", found.size(), expectedCount, jobIdField, jobIdValue);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse raw SQS message: {}", e.getMessage());
                }
            }
            return found.size() >= expectedCount;
        });

        LOG.info("All {} raw events received for {}={}", found.size(), jobIdField, jobIdValue);
        return found;
    }

    /**
     * Poll the SQS queue for multiple enriched events matching the given field and value.
     * Returns all matching event detail nodes once the expected count is reached.
     */
    protected List<JsonNode> pollForAllEnrichedEvents(String jobIdField, String jobIdValue, int expectedCount, Duration timeout) {
        LOG.info("Polling for {} enriched events: {}={} (timeout={}s)", expectedCount, jobIdField, jobIdValue, timeout.toSeconds());
        List<JsonNode> found = new ArrayList<>();

        await().atMost(timeout).pollInterval(Duration.ofMillis(100)).until(() -> {
            ReceiveMessageResponse resp = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)
                    .build());
            for (Message msg : resp.messages()) {
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build());
                try {
                    JsonNode event = MAPPER.readTree(msg.body());
                    JsonNode detail = event.has("detail") && event.get("detail").isTextual()
                            ? MAPPER.readTree(event.get("detail").asText())
                            : event.get("detail");
                    if (detail != null && detail.has(jobIdField)
                            && jobIdValue.equals(detail.get(jobIdField).asText())) {
                        found.add(detail);
                        LOG.info("Found matching event {}/{} for {}={}", found.size(), expectedCount, jobIdField, jobIdValue);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse SQS message: {}", e.getMessage());
                }
            }
            return found.size() >= expectedCount;
        });

        LOG.info("All {} enriched events received for {}={}", found.size(), jobIdField, jobIdValue);
        return found;
    }

    /**
     * Poll the SQS queue for enriched events matching a detail-type and a field/value inside detail.
     * Returns all matching event detail nodes once the expected count is reached.
     */
    protected List<JsonNode> pollForEnrichedEventsByDetailType(
            String detailType, String jobIdField, String jobIdValue, int expectedCount, Duration timeout) {
        LOG.info("Polling for {} events with detail-type='{}', {}={} (timeout={}s)",
                expectedCount, detailType, jobIdField, jobIdValue, timeout.toSeconds());
        List<JsonNode> found = new ArrayList<>();

        await().atMost(timeout).pollInterval(Duration.ofMillis(100)).until(() -> {
            ReceiveMessageResponse resp = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)
                    .build());
            for (Message msg : resp.messages()) {
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build());
                try {
                    JsonNode event = MAPPER.readTree(msg.body());
                    String eventDetailType = event.has("detail-type") ? event.get("detail-type").asText() : "";
                    if (!detailType.equals(eventDetailType)) continue;
                    JsonNode detail = event.has("detail") && event.get("detail").isTextual()
                            ? MAPPER.readTree(event.get("detail").asText())
                            : event.get("detail");
                    if (detail != null && detail.has(jobIdField)
                            && jobIdValue.equals(detail.get(jobIdField).asText())) {
                        found.add(detail);
                        LOG.info("Found matching event {}/{} (detail-type='{}')", found.size(), expectedCount, detailType);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse SQS message: {}", e.getMessage());
                }
            }
            return found.size() >= expectedCount;
        });

        LOG.info("All {} events received for detail-type='{}', {}={}", found.size(), detailType, jobIdField, jobIdValue);
        return found;
    }

    /**
     * Poll the SQS queue and assert no events matching the given detail-type arrive within the timeout.
     * Returns any events that did arrive (should be empty for a passing assertion).
     */
    protected List<JsonNode> pollAndExpectNoEvents(
            String detailType, String jobIdField, String jobIdValue, Duration timeout) {
        LOG.info("Asserting no events with detail-type='{}', {}={} within {}s",
                detailType, jobIdField, jobIdValue, timeout.toSeconds());
        List<JsonNode> found = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            ReceiveMessageResponse resp = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(5)
                    .build());
            for (Message msg : resp.messages()) {
                sqsClient.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build());
                try {
                    JsonNode event = MAPPER.readTree(msg.body());
                    String eventDetailType = event.has("detail-type") ? event.get("detail-type").asText() : "";
                    if (!detailType.equals(eventDetailType)) continue;
                    JsonNode detail = event.has("detail") && event.get("detail").isTextual()
                            ? MAPPER.readTree(event.get("detail").asText())
                            : event.get("detail");
                    if (detail != null && detail.has(jobIdField)
                            && jobIdValue.equals(detail.get(jobIdField).asText())) {
                        found.add(detail);
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to parse SQS message: {}", e.getMessage());
                }
            }
        }
        return found;
    }

    /** Assert that _helper_metadata in the event matches the original metadata. */
    protected void assertEnrichedMetadata(JsonNode eventDetail, String originalMetadata) throws Exception {
        assert eventDetail.has("_helper_metadata") : "Enriched event missing _helper_metadata";
        JsonNode helperMeta = eventDetail.get("_helper_metadata");
        JsonNode expected = MAPPER.readTree(originalMetadata);
        assert expected.equals(helperMeta) : "Metadata mismatch in enriched event. Expected: "
                + expected + ", got: " + helperMeta;
        LOG.info("Enriched event metadata matches expected payload");
    }

    /**
     * Deliberate pause between timed sequences to avoid SFTP Connector silent throttling.
     * NOT for waiting on async completion — use poll methods for that.
     */
    protected void throttleGuard(long ms) {
        if (ms > 0) {
            LOG.info("Throttle guard: sleeping {}ms between timed sequences", ms);
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * Send a file to the remote server and wait for confirmed completion via event polling.
     * Use this instead of Thread.sleep() for setup phases.
     */
    protected String sendToRemoteAndWait(String s3Key) {
        uploadTestFile(s3Key, "setup-content");
        StartFileTransferRequest req = StartFileTransferRequest.builder()
                .connectorId(CONNECTOR_ID)
                .sendFilePaths("/" + TEST_S3_BUCKET + "/" + s3Key)
                .remoteDirectoryPath(REMOTE_DIR).build();
        String transferId = transferClient.startFileTransfer(req).transferId();
        trackForCleanup(transferId);
        pollForRawEvent("transfer-id", transferId, Duration.ofSeconds(90));
        return transferId;
    }

    /** Upload a test file to S3. */
    protected void uploadTestFile(String key, String content) {
        s3Client.putObject(PutObjectRequest.builder().bucket(TEST_S3_BUCKET).key(key).build(),
                RequestBody.fromString(content));
    }

    /** Delete a single S3 object (swallows errors). */
    protected void cleanupS3(String key) {
        try { s3Client.deleteObject(DeleteObjectRequest.builder().bucket(TEST_S3_BUCKET).key(key).build()); }
        catch (Exception ignored) {}
    }

    /** Delete all S3 objects under a prefix (swallows errors). */
    protected void cleanupS3Prefix(String prefix) {
        try {
            s3Client.listObjectsV2(b -> b.bucket(TEST_S3_BUCKET).prefix(prefix)).contents()
                    .forEach(o -> cleanupS3(o.key()));
        } catch (Exception ignored) {}
    }

    static String prop(String name, String defaultValue) {
        String val = System.getProperty(name);
        if (val == null || val.isEmpty() || val.equals("null")) {
            val = System.getenv(name);
        }
        return (val != null && !val.isEmpty()) ? val : defaultValue;
    }
}
