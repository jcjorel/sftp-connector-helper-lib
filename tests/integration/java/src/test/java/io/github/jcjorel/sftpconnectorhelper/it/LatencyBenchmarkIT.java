package io.github.jcjorel.sftpconnectorhelper.it;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jcjorel.sftpconnectorhelper.SftpOperationResult;
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
        assertEquals("COMPLETED", status,
                "SFTP operation failed with status-code: " + status);
    }

    private long timeDirect(Supplier<String> call, String idField, int count, Duration timeout) {
        long start = System.currentTimeMillis();
        String jobId = call.get();
        trackForCleanup(jobId);
        if (count == 1) assertStatusCompleted(pollForRawEvent(idField, jobId, timeout));
        else pollForAllRawEvents(idField, jobId, count, timeout).forEach(this::assertStatusCompleted);
        return System.currentTimeMillis() - start;
    }

    @SuppressWarnings("unchecked")
    private <R> long timeHelper(Supplier<SftpOperationResult<R>> call, Function<R, String> idExtractor,
                                String idField, int count, Duration timeout) {
        long start = System.currentTimeMillis();
        var result = call.get();
        assertInstanceOf(SftpOperationResult.Success.class, result);
        String jobId = idExtractor.apply(((SftpOperationResult.Success<R>) result).response());
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

    // --- 4 single-operation benchmarks ---

    @Test @Order(1)
    void benchmarkFileTransfer() {
        runBenchmark("StartFileTransfer", THROTTLE_MS, i -> {
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

    @Test @Order(3)
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

    @Test @Order(4)
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

    @Test @Order(5)
    void benchmarkMultiFileSend() {
        runBenchmark("MultiFileSend(10)", LONG_THROTTLE_MS, i -> {
            String uid = UUID.randomUUID().toString().substring(0, 8);

            FileSet d = prepareFiles("ms-d", uid);
            throttleGuard(LONG_THROTTLE_MS);
            long directMs = timeDirect(
                    () -> transferClient.startFileTransfer(ftMultiSendReq(d.s3Paths())).transferId(),
                    "transfer-id", MULTI_FILE_COUNT, MULTI_POLL_TIMEOUT);
            d.keys().forEach(this::cleanupS3);

            throttleGuard(LONG_THROTTLE_MS);

            FileSet h = prepareFiles("ms-h", uid);
            throttleGuard(LONG_THROTTLE_MS);
            long helperMs = timeHelper(
                    () -> helper.startFileTransfer(ftMultiSendReq(h.s3Paths()), testMetadata("bench-multi-send")),
                    StartFileTransferResponse::transferId, "transfer-id", MULTI_FILE_COUNT, MULTI_POLL_TIMEOUT);
            h.keys().forEach(this::cleanupS3);

            return new TimingSample(directMs, helperMs);
        });
    }

    @Test @Order(6)
    void benchmarkMultiFileRetrieve() {
        runBenchmark("MultiFileRetrieve(10)", LONG_THROTTLE_MS, i -> {
            String uid = UUID.randomUUID().toString().substring(0, 8);

            // Setup: send files to remote
            FileSet setup = prepareFiles("mr-setup", uid);
            List<String> remotePaths = new ArrayList<>();
            for (int f = 0; f < MULTI_FILE_COUNT; f++)
                remotePaths.add(REMOTE_DIR + "/mr-setup-" + uid + "-file-" + f + ".txt");

            String setupId = transferClient.startFileTransfer(ftMultiSendReq(setup.s3Paths())).transferId();
            trackForCleanup(setupId);
            pollForAllRawEvents("transfer-id", setupId, MULTI_FILE_COUNT, MULTI_POLL_TIMEOUT);
            setup.keys().forEach(this::cleanupS3);
            throttleGuard(LONG_THROTTLE_MS);

            // Direct
            String dLocalDir = "/" + TEST_S3_BUCKET + "/benchmark/mr-d-" + uid;
            long directMs = timeDirect(
                    () -> transferClient.startFileTransfer(ftRetrieveReq(remotePaths, dLocalDir)).transferId(),
                    "transfer-id", MULTI_FILE_COUNT, MULTI_POLL_TIMEOUT);
            cleanupS3Prefix("benchmark/mr-d-" + uid);

            throttleGuard(LONG_THROTTLE_MS);

            // Helper
            String hLocalDir = "/" + TEST_S3_BUCKET + "/benchmark/mr-h-" + uid;
            long helperMs = timeHelper(
                    () -> helper.startFileTransfer(ftRetrieveReq(remotePaths, hLocalDir), testMetadata("bench-multi-retrieve")),
                    StartFileTransferResponse::transferId, "transfer-id", MULTI_FILE_COUNT, MULTI_POLL_TIMEOUT);
            cleanupS3Prefix("benchmark/mr-h-" + uid);

            return new TimingSample(directMs, helperMs);
        });
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
