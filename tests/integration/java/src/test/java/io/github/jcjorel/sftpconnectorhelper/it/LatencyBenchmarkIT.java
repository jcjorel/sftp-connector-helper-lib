package io.github.jcjorel.sftpconnectorhelper.it;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jcjorel.sftpconnectorhelper.EventEmissionMode;
import io.github.jcjorel.sftpconnectorhelper.FileTransferOptions;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.transfer.model.*;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Latency benchmark measuring end-to-end latency for each SFTP Connector operation
 * across two paths: direct SDK call and helper-enriched call.
 * Reports min/mean/max statistics per operation in a summary table.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LatencyBenchmarkIT extends IntegrationTestBase {

    private boolean benchmarkFailed = false;

    @BeforeEach
    void abortIfPreviousTestFailed() {
        if (benchmarkFailed) fail("Aborting: a previous benchmark operation failed");
    }

    private static final Logger LOG = LoggerFactory.getLogger(LatencyBenchmarkIT.class);

    private static final int ITERATIONS = Integer.parseInt(prop("BENCHMARK_ITERATIONS", "5"));
    private static final long THROTTLE_MS = Long.parseLong(prop("BENCHMARK_SLEEP_MS", "7500"));
    private static final long LONG_THROTTLE_MS = THROTTLE_MS * 5;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration MULTI_POLL_TIMEOUT = Duration.ofSeconds(180);
    private static final int MULTI_FILE_COUNT = 10;

    private final List<OperationTimings> allTimings = new ArrayList<>();

    // --- Data types ---

    record TimingSample(long directMs, long helperMs) {
        long overheadMs() { return helperMs - directMs; }
    }

    record OperationTimings(String operation, List<TimingSample> samples) {
        long minDirect() { return samples.stream().mapToLong(TimingSample::directMs).min().orElse(0); }
        long meanDirect() { return (long) samples.stream().mapToLong(TimingSample::directMs).average().orElse(0); }
        long maxDirect() { return samples.stream().mapToLong(TimingSample::directMs).max().orElse(0); }
        long minHelper() { return samples.stream().mapToLong(TimingSample::helperMs).min().orElse(0); }
        long meanHelper() { return (long) samples.stream().mapToLong(TimingSample::helperMs).average().orElse(0); }
        long maxHelper() { return samples.stream().mapToLong(TimingSample::helperMs).max().orElse(0); }
        long minOverhead() { return samples.stream().mapToLong(TimingSample::overheadMs).min().orElse(0); }
        long meanOverhead() { return (long) samples.stream().mapToLong(TimingSample::overheadMs).average().orElse(0); }
        long maxOverhead() { return samples.stream().mapToLong(TimingSample::overheadMs).max().orElse(0); }
    }

    record FileSet(List<String> keys, List<String> s3Paths) {}

    // --- Generic timing harness ---

    private void assertStatusCompleted(JsonNode detail) {
        String status = detail.path("status-code").asText("");
        // Batch completion events use ALL_COMPLETED; per-file events use COMPLETED
        if (detail.has("file-count")) {
            assertEquals("ALL_COMPLETED", status,
                    "Batch event failed with status-code: " + status);
        } else {
            assertEquals("COMPLETED", status,
                    "SFTP operation failed with status-code: " + status);
        }
    }

    private long timeDirect(Supplier<String> call, String idField, int count, Duration timeout) {
        long start = System.currentTimeMillis();
        String jobId = call.get();
        trackForCleanup(jobId);
        if (count == 1) assertStatusCompleted(pollForRawEvent(idField, jobId, timeout));
        else pollForAllRawEvents(idField, jobId, count, timeout).forEach(this::assertStatusCompleted);
        return System.currentTimeMillis() - start;
    }

    private <R> long timeHelper(Supplier<R> call, Function<R, String> idExtractor,
                                String idField, int count, Duration timeout) {
        long start = System.currentTimeMillis();
        var result = call.get();
        String jobId = idExtractor.apply(result);
        trackForCleanup(jobId);
        if (count == 1) assertStatusCompleted(pollForEnrichedEvent(idField, jobId, timeout));
        else pollForAllEnrichedEvents(idField, jobId, count, timeout).forEach(this::assertStatusCompleted);
        return System.currentTimeMillis() - start;
    }

    // --- Benchmark loop template ---

    private void runBenchmark(String opName, long throttleMs, IntFunction<TimingSample> sampleProducer) {
        List<TimingSample> samples = new ArrayList<>();
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                TimingSample sample = sampleProducer.apply(i);
                samples.add(sample);
                LOG.info("[BENCHMARK] {} iteration {}/{}: direct={}ms, helper={}ms, overhead=+{}ms",
                        opName, i + 1, ITERATIONS, sample.directMs(), sample.helperMs(), sample.overheadMs());
                throttleGuard(throttleMs);
            }
        } catch (AssertionError | Exception e) {
            benchmarkFailed = true;
            throw e;
        }
        allTimings.add(new OperationTimings(opName, samples));
    }

    // --- Request builders ---

    private StartFileTransferRequest ftSendReq(String key) {
        return StartFileTransferRequest.builder()
                .connectorId(CONNECTOR_ID).sendFilePaths("/" + TEST_S3_BUCKET + "/" + key)
                .remoteDirectoryPath(REMOTE_DIR).build();
    }

    private StartFileTransferRequest ftMultiSendReq(List<String> paths) {
        return StartFileTransferRequest.builder()
                .connectorId(CONNECTOR_ID).sendFilePaths(paths).remoteDirectoryPath(REMOTE_DIR).build();
    }

    private StartFileTransferRequest ftRetrieveReq(List<String> remotePaths, String localDir) {
        return StartFileTransferRequest.builder()
                .connectorId(CONNECTOR_ID).retrieveFilePaths(remotePaths).localDirectoryPath(localDir).build();
    }

    private StartDirectoryListingRequest listingReq() {
        return StartDirectoryListingRequest.builder()
                .connectorId(CONNECTOR_ID).remoteDirectoryPath(REMOTE_DIR)
                .outputDirectoryPath("/" + TEST_S3_BUCKET + "/listing-tests").build();
    }

    private StartRemoteMoveRequest moveReq(String fileName) {
        return StartRemoteMoveRequest.builder()
                .connectorId(CONNECTOR_ID).sourcePath(REMOTE_DIR + "/" + fileName)
                .targetPath(REMOTE_DIR + "/" + fileName + "-moved").build();
    }

    private StartRemoteDeleteRequest deleteReq(String fileName) {
        return StartRemoteDeleteRequest.builder()
                .connectorId(CONNECTOR_ID).deletePath(REMOTE_DIR + "/" + fileName).build();
    }

    // --- Setup helpers ---

    private FileSet prepareFiles(String prefix, String uid) {
        List<String> keys = new ArrayList<>(), paths = new ArrayList<>();
        for (int f = 0; f < MULTI_FILE_COUNT; f++) {
            String key = "benchmark/" + prefix + "-" + uid + "/" + prefix + "-" + uid + "-file-" + f + ".txt";
            keys.add(key);
            paths.add("/" + TEST_S3_BUCKET + "/" + key);
            uploadTestFile(key, "x".repeat(100));
        }
        return new FileSet(keys, paths);
    }

    private void setupRemoteFile(String key) {
        uploadTestFile(key, "x".repeat(100));
        String transferId = transferClient.startFileTransfer(ftSendReq(key)).transferId();
        trackForCleanup(transferId);
        pollForRawEvent("transfer-id", transferId, POLL_TIMEOUT);
        cleanupS3(key);
    }

    // --- 5 single-operation benchmarks ---

    @Test @Order(1)
    void benchmarkSingleFileSend() {
        runBenchmark("SingleFileSend", THROTTLE_MS, i -> {
            String uid = UUID.randomUUID().toString().substring(0, 8);

            String dKey = "benchmark/ft-d-" + uid + ".txt";
            uploadTestFile(dKey, "x".repeat(100));
            throttleGuard(THROTTLE_MS);
            long directMs = timeDirect(
                    () -> transferClient.startFileTransfer(ftSendReq(dKey)).transferId(),
                    "transfer-id", 1, POLL_TIMEOUT);
            cleanupS3(dKey);

            throttleGuard(THROTTLE_MS);

            String hKey = "benchmark/ft-h-" + uid + ".txt";
            uploadTestFile(hKey, "x".repeat(100));
            throttleGuard(THROTTLE_MS);
            long helperMs = timeHelper(
                    () -> helper.startFileTransfer(ftSendReq(hKey), testMetadata("bench-ft")),
                    StartFileTransferResponse::transferId, "transfer-id", 1, POLL_TIMEOUT);
            cleanupS3(hKey);

            return new TimingSample(directMs, helperMs);
        });
    }

    @Test @Order(2)
    void benchmarkSingleFileRetrieve() {
        runBenchmark("SingleFileRetrieve", THROTTLE_MS, i -> {
            String uid = UUID.randomUUID().toString().substring(0, 8);

            // Setup: send a file to remote server
            String setupKey = "benchmark/fr-setup-" + uid + ".txt";
            setupRemoteFile(setupKey);
            String remotePath = REMOTE_DIR + "/fr-setup-" + uid + ".txt";
            throttleGuard(THROTTLE_MS);

            // Direct
            String dLocalDir = "/" + TEST_S3_BUCKET + "/benchmark/fr-d-" + uid;
            long directMs = timeDirect(
                    () -> transferClient.startFileTransfer(ftRetrieveReq(List.of(remotePath), dLocalDir)).transferId(),
                    "transfer-id", 1, POLL_TIMEOUT);
            cleanupS3Prefix("benchmark/fr-d-" + uid);

            throttleGuard(THROTTLE_MS);

            // Helper
            String hLocalDir = "/" + TEST_S3_BUCKET + "/benchmark/fr-h-" + uid;
            long helperMs = timeHelper(
                    () -> helper.startFileTransfer(ftRetrieveReq(List.of(remotePath), hLocalDir), testMetadata("bench-fr")),
                    StartFileTransferResponse::transferId, "transfer-id", 1, POLL_TIMEOUT);
            cleanupS3Prefix("benchmark/fr-h-" + uid);

            return new TimingSample(directMs, helperMs);
        });
    }

    @Test @Order(3)
    void benchmarkDirectoryListing() {
        runBenchmark("StartDirectoryListing", THROTTLE_MS, i -> {
            long directMs = timeDirect(
                    () -> transferClient.startDirectoryListing(listingReq()).listingId(),
                    "listing-id", 1, POLL_TIMEOUT);

            throttleGuard(THROTTLE_MS);

            long helperMs = timeHelper(
                    () -> helper.startDirectoryListing(listingReq(), testMetadata("bench-listing")),
                    StartDirectoryListingResponse::listingId, "listing-id", 1, POLL_TIMEOUT);

            return new TimingSample(directMs, helperMs);
        });
    }

    @Test @Order(4)
    void benchmarkRemoteMove() {
        runBenchmark("StartRemoteMove", THROTTLE_MS, i -> {
            String uid = UUID.randomUUID().toString().substring(0, 8);

            String dFile = "mv-d-" + uid + ".txt";
            setupRemoteFile("benchmark/" + dFile);
            throttleGuard(THROTTLE_MS);
            long directMs = timeDirect(
                    () -> transferClient.startRemoteMove(moveReq(dFile)).moveId(),
                    "move-id", 1, POLL_TIMEOUT);

            String hFile = "mv-h-" + uid + ".txt";
            setupRemoteFile("benchmark/" + hFile);
            throttleGuard(THROTTLE_MS);
            long helperMs = timeHelper(
                    () -> helper.startRemoteMove(moveReq(hFile), testMetadata("bench-move")),
                    StartRemoteMoveResponse::moveId, "move-id", 1, POLL_TIMEOUT);

            return new TimingSample(directMs, helperMs);
        });
    }

    @Test @Order(5)
    void benchmarkRemoteDelete() {
        runBenchmark("StartRemoteDelete", THROTTLE_MS, i -> {
            String uid = UUID.randomUUID().toString().substring(0, 8);

            String dFile = "del-d-" + uid + ".txt";
            setupRemoteFile("benchmark/" + dFile);
            throttleGuard(THROTTLE_MS);
            long directMs = timeDirect(
                    () -> transferClient.startRemoteDelete(deleteReq(dFile)).deleteId(),
                    "delete-id", 1, POLL_TIMEOUT);

            String hFile = "del-h-" + uid + ".txt";
            setupRemoteFile("benchmark/" + hFile);
            throttleGuard(THROTTLE_MS);
            long helperMs = timeHelper(
                    () -> helper.startRemoteDelete(deleteReq(hFile), testMetadata("bench-delete")),
                    StartRemoteDeleteResponse::deleteId, "delete-id", 1, POLL_TIMEOUT);

            return new TimingSample(directMs, helperMs);
        });
    }

    // --- 2 multi-file benchmarks ---

    @Test @Order(6)
    void benchmarkMultiFileSend() {
        List<TimingSample> defaultSamples = new ArrayList<>();
        List<TimingSample> wholeOnlySamples = new ArrayList<>();
        List<TimingSample> indWholeSamples = new ArrayList<>();

        try {
            for (int i = 0; i < ITERATIONS; i++) {
                String uid = UUID.randomUUID().toString().substring(0, 8);

                // Shared direct baseline
                FileSet d = prepareFiles("ms-d", uid);
                throttleGuard(LONG_THROTTLE_MS);
                long directMs = timeDirect(
                        () -> transferClient.startFileTransfer(ftMultiSendReq(d.s3Paths())).transferId(),
                        "transfer-id", MULTI_FILE_COUNT, MULTI_POLL_TIMEOUT);
                d.keys().forEach(this::cleanupS3);

                // Variant 1: default (individual events only)
                throttleGuard(LONG_THROTTLE_MS);
                FileSet h1 = prepareFiles("ms-h1", uid);
                throttleGuard(LONG_THROTTLE_MS);
                long helper1Ms = timeHelper(
                        () -> helper.startFileTransfer(ftMultiSendReq(h1.s3Paths()), testMetadata("bench-ms-default")),
                        StartFileTransferResponse::transferId, "transfer-id", MULTI_FILE_COUNT, MULTI_POLL_TIMEOUT);
                h1.keys().forEach(this::cleanupS3);
                defaultSamples.add(new TimingSample(directMs, helper1Ms));
                LOG.info("[BENCHMARK] MultiFileSend(10) iter {}/{}: direct={}ms, helper={}ms", i + 1, ITERATIONS, directMs, helper1Ms);

                // Variant 2: WHOLE_TRANSFER_COMPLETION_ONLY (1 batch event)
                throttleGuard(LONG_THROTTLE_MS);
                FileSet h2 = prepareFiles("ms-h2", uid);
                throttleGuard(LONG_THROTTLE_MS);
                long helper2Ms = timeHelper(
                        () -> helper.startFileTransfer(ftMultiSendReq(h2.s3Paths()), testMetadata("bench-ms-whole"),
                                FileTransferOptions.builder().emissionMode(EventEmissionMode.WHOLE_TRANSFER_COMPLETION_ONLY).build()),
                        StartFileTransferResponse::transferId, "transfer-id", 1, MULTI_POLL_TIMEOUT);
                h2.keys().forEach(this::cleanupS3);
                wholeOnlySamples.add(new TimingSample(directMs, helper2Ms));
                LOG.info("[BENCHMARK] MultiFileSend(10)-WholeOnly iter {}/{}: direct={}ms, helper={}ms", i + 1, ITERATIONS, directMs, helper2Ms);

                // Variant 3: INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION (N+1 events)
                throttleGuard(LONG_THROTTLE_MS);
                FileSet h3 = prepareFiles("ms-h3", uid);
                throttleGuard(LONG_THROTTLE_MS);
                long helper3Ms = timeHelper(
                        () -> helper.startFileTransfer(ftMultiSendReq(h3.s3Paths()), testMetadata("bench-ms-ind-whole"),
                                FileTransferOptions.builder().emissionMode(EventEmissionMode.INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION).build()),
                        StartFileTransferResponse::transferId, "transfer-id", MULTI_FILE_COUNT + 1, MULTI_POLL_TIMEOUT);
                h3.keys().forEach(this::cleanupS3);
                indWholeSamples.add(new TimingSample(directMs, helper3Ms));
                LOG.info("[BENCHMARK] MultiFileSend(10)-Ind+Whole iter {}/{}: direct={}ms, helper={}ms", i + 1, ITERATIONS, directMs, helper3Ms);

                throttleGuard(LONG_THROTTLE_MS);
            }
        } catch (AssertionError | Exception e) {
            benchmarkFailed = true;
            throw e;
        }
        allTimings.add(new OperationTimings("MultiFileSend(10)", defaultSamples));
        allTimings.add(new OperationTimings("MultiFileSend(10)-WholeOnly", wholeOnlySamples));
        allTimings.add(new OperationTimings("MultiFileSend(10)-Ind+Whole", indWholeSamples));
    }

    @Test @Order(7)
    void benchmarkMultiFileRetrieve() {
        List<TimingSample> defaultSamples = new ArrayList<>();
        List<TimingSample> wholeOnlySamples = new ArrayList<>();
        List<TimingSample> indWholeSamples = new ArrayList<>();

        try {
            for (int i = 0; i < ITERATIONS; i++) {
                String uid = UUID.randomUUID().toString().substring(0, 8);

                // Setup: send files to remote (shared across all variants)
                FileSet setup = prepareFiles("mr-setup", uid);
                List<String> remotePaths = new ArrayList<>();
                for (int f = 0; f < MULTI_FILE_COUNT; f++)
                    remotePaths.add(REMOTE_DIR + "/mr-setup-" + uid + "-file-" + f + ".txt");

                String setupId = transferClient.startFileTransfer(ftMultiSendReq(setup.s3Paths())).transferId();
                trackForCleanup(setupId);
                pollForAllRawEvents("transfer-id", setupId, MULTI_FILE_COUNT, MULTI_POLL_TIMEOUT);
                setup.keys().forEach(this::cleanupS3);
                throttleGuard(LONG_THROTTLE_MS);

                // Shared direct baseline
                String dLocalDir = "/" + TEST_S3_BUCKET + "/benchmark/mr-d-" + uid;
                long directMs = timeDirect(
                        () -> transferClient.startFileTransfer(ftRetrieveReq(remotePaths, dLocalDir)).transferId(),
                        "transfer-id", MULTI_FILE_COUNT, MULTI_POLL_TIMEOUT);
                cleanupS3Prefix("benchmark/mr-d-" + uid);

                // Variant 1: default (individual events only)
                throttleGuard(LONG_THROTTLE_MS);
                String h1LocalDir = "/" + TEST_S3_BUCKET + "/benchmark/mr-h1-" + uid;
                long helper1Ms = timeHelper(
                        () -> helper.startFileTransfer(ftRetrieveReq(remotePaths, h1LocalDir), testMetadata("bench-mr-default")),
                        StartFileTransferResponse::transferId, "transfer-id", MULTI_FILE_COUNT, MULTI_POLL_TIMEOUT);
                cleanupS3Prefix("benchmark/mr-h1-" + uid);
                defaultSamples.add(new TimingSample(directMs, helper1Ms));
                LOG.info("[BENCHMARK] MultiFileRetrieve(10) iter {}/{}: direct={}ms, helper={}ms", i + 1, ITERATIONS, directMs, helper1Ms);

                // Variant 2: WHOLE_TRANSFER_COMPLETION_ONLY (1 batch event)
                throttleGuard(LONG_THROTTLE_MS);
                String h2LocalDir = "/" + TEST_S3_BUCKET + "/benchmark/mr-h2-" + uid;
                long helper2Ms = timeHelper(
                        () -> helper.startFileTransfer(ftRetrieveReq(remotePaths, h2LocalDir), testMetadata("bench-mr-whole"),
                                FileTransferOptions.builder().emissionMode(EventEmissionMode.WHOLE_TRANSFER_COMPLETION_ONLY).build()),
                        StartFileTransferResponse::transferId, "transfer-id", 1, MULTI_POLL_TIMEOUT);
                cleanupS3Prefix("benchmark/mr-h2-" + uid);
                wholeOnlySamples.add(new TimingSample(directMs, helper2Ms));
                LOG.info("[BENCHMARK] MultiFileRetrieve(10)-WholeOnly iter {}/{}: direct={}ms, helper={}ms", i + 1, ITERATIONS, directMs, helper2Ms);

                // Variant 3: INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION (N+1 events)
                throttleGuard(LONG_THROTTLE_MS);
                String h3LocalDir = "/" + TEST_S3_BUCKET + "/benchmark/mr-h3-" + uid;
                long helper3Ms = timeHelper(
                        () -> helper.startFileTransfer(ftRetrieveReq(remotePaths, h3LocalDir), testMetadata("bench-mr-ind-whole"),
                                FileTransferOptions.builder().emissionMode(EventEmissionMode.INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION).build()),
                        StartFileTransferResponse::transferId, "transfer-id", MULTI_FILE_COUNT + 1, MULTI_POLL_TIMEOUT);
                cleanupS3Prefix("benchmark/mr-h3-" + uid);
                indWholeSamples.add(new TimingSample(directMs, helper3Ms));
                LOG.info("[BENCHMARK] MultiFileRetrieve(10)-Ind+Whole iter {}/{}: direct={}ms, helper={}ms", i + 1, ITERATIONS, directMs, helper3Ms);

                throttleGuard(LONG_THROTTLE_MS);
            }
        } catch (AssertionError | Exception e) {
            benchmarkFailed = true;
            throw e;
        }
        allTimings.add(new OperationTimings("MultiFileRetrieve(10)", defaultSamples));
        allTimings.add(new OperationTimings("MultiFileRetrieve(10)-WholeOnly", wholeOnlySamples));
        allTimings.add(new OperationTimings("MultiFileRetrieve(10)-Ind+Whole", indWholeSamples));
    }

    // --- Summary table ---

    @AfterAll
    void printTimingTable() {
        if (allTimings.isEmpty()) return;
        String fmt = "║ %-24s │ %5d / %5d / %5d       │ %5d / %5d / %5d       │ %+5d / %+5d / %+5d       ║%n";
        System.out.println();
        System.out.println("╔══════════════════════════╤═══════════════════════════╤═══════════════════════════╤═══════════════════════════╗");
        System.out.println("║ Operation                │ Direct (ms)               │ Helper (ms)               │ Overhead (ms)             ║");
        System.out.println("║                          │  min /  mean /  max       │  min /  mean /  max       │  min /  mean /  max       ║");
        System.out.println("╠══════════════════════════╪═══════════════════════════╪═══════════════════════════╪═══════════════════════════╣");
        for (OperationTimings t : allTimings) {
            System.out.printf(fmt, t.operation(),
                    t.minDirect(), t.meanDirect(), t.maxDirect(),
                    t.minHelper(), t.meanHelper(), t.maxHelper(),
                    t.minOverhead(), t.meanOverhead(), t.maxOverhead());
        }
        System.out.println("╚══════════════════════════╧═══════════════════════════╧═══════════════════════════╧═══════════════════════════╝");
        System.out.printf("                                                                        (%d iterations per operation)%n", ITERATIONS);
        System.out.println();
    }
}
