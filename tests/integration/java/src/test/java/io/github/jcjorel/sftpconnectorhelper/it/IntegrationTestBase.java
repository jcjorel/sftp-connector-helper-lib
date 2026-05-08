package io.github.jcjorel.sftpconnectorhelper.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jcjorel.sftpconnectorhelper.SftpConnectorHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Duration;
import java.util.*;
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
public abstract class IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationTestBase.class);

    private static final String RESOURCE_PREFIX = "integ-test-java-";
    private static final String RULE_PREFIX = RESOURCE_PREFIX + "rule-";

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

    private static String queueUrl;
    private static String queueArn;
    private static String ruleName;

    /** Job IDs to clean up from DynamoDB after all tests. */
    protected static final List<String> jobIdsToCleanup = new CopyOnWriteArrayList<>();

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

        // Create EventBridge rule on dedicated bus
        LOG.info("Creating EventBridge rule: {} on bus: {}", ruleName, EVENT_BUS_NAME);
        PutRuleResponse ruleResp = ebClient.putRule(PutRuleRequest.builder()
                .name(ruleName)
                .eventBusName(EVENT_BUS_NAME)
                .eventPattern("{\"source\":[\"aws.transfer\"]}")
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
        LOG.info("Test infrastructure ready. Rule ARN: {}", ruleArn);
    }

    @AfterAll
    static void teardownInfrastructure() {
        LOG.info("Tearing down test infrastructure. Cleaning {} DynamoDB records", jobIdsToCleanup.size());

        // Cleanup DynamoDB records
        for (String jobId : jobIdsToCleanup) {
            try {
                dynamoDb.deleteItem(DeleteItemRequest.builder()
                        .tableName(TABLE_NAME)
                        .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                        .build());
            } catch (Exception ignored) {}
        }

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

        // Clean up EventBridge rules
        try {
            ListRulesResponse rulesResp = ebClient.listRules(ListRulesRequest.builder()
                    .eventBusName(EVENT_BUS_NAME)
                    .namePrefix(RULE_PREFIX)
                    .build());
            for (Rule rule : rulesResp.rules()) {
                LOG.info("Removing orphaned EventBridge rule: {}", rule.name());
                try {
                    // List and remove all targets first
                    ListTargetsByRuleResponse targets = ebClient.listTargetsByRule(
                            ListTargetsByRuleRequest.builder()
                                    .rule(rule.name())
                                    .eventBusName(EVENT_BUS_NAME)
                                    .build());
                    if (!targets.targets().isEmpty()) {
                        List<String> targetIds = targets.targets().stream().map(Target::id).toList();
                        ebClient.removeTargets(RemoveTargetsRequest.builder()
                                .rule(rule.name())
                                .eventBusName(EVENT_BUS_NAME)
                                .ids(targetIds)
                                .build());
                    }
                    ebClient.deleteRule(DeleteRuleRequest.builder()
                            .name(rule.name())
                            .eventBusName(EVENT_BUS_NAME)
                            .build());
                } catch (Exception e) {
                    LOG.warn("Failed to delete rule {}: {}", rule.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to list EventBridge rules: {}", e.getMessage());
        }

        // Clean up SQS queues
        try {
            ListQueuesResponse queuesResp = sqsClient.listQueues(ListQueuesRequest.builder()
                    .queueNamePrefix(RESOURCE_PREFIX)
                    .build());
            for (String orphanQueueUrl : queuesResp.queueUrls()) {
                LOG.info("Removing orphaned SQS queue: {}", orphanQueueUrl);
                try {
                    sqsClient.deleteQueue(DeleteQueueRequest.builder().queueUrl(orphanQueueUrl).build());
                } catch (Exception e) {
                    LOG.warn("Failed to delete queue {}: {}", orphanQueueUrl, e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to list SQS queues: {}", e.getMessage());
        }
    }

    /** Track a job ID for DynamoDB cleanup. */
    protected void trackForCleanup(String jobId) {
        jobIdsToCleanup.add(jobId);
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

        await().atMost(timeout).pollInterval(Duration.ofSeconds(2)).until(() -> {
            ReceiveMessageResponse resp = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(2)
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

    /** Assert that _helper_metadata in the event matches the original metadata. */
    protected void assertEnrichedMetadata(JsonNode eventDetail, String originalMetadata) throws Exception {
        assert eventDetail.has("_helper_metadata") : "Enriched event missing _helper_metadata";
        JsonNode helperMeta = eventDetail.get("_helper_metadata");
        JsonNode expected = MAPPER.readTree(originalMetadata);
        assert expected.equals(helperMeta) : "Metadata mismatch in enriched event. Expected: "
                + expected + ", got: " + helperMeta;
        LOG.info("Enriched event metadata matches expected payload");
    }

    private static String prop(String name, String defaultValue) {
        String val = System.getProperty(name);
        if (val == null || val.isEmpty() || val.equals("null")) {
            val = System.getenv(name);
        }
        return (val != null && !val.isEmpty()) ? val : defaultValue;
    }
}
