package com.agenticai.core.providers.localmodels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.net.SsrfGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The gateway and the pull jobs against a fake Ollama on a loopback port. */
class LocalModelOllamaGatewayAndPullTest {

    private FakeOllama fake;
    private LocalModelOllamaGateway gateway;
    private LocalModelPullService pulls;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeOllama();
        gateway = new LocalModelOllamaGateway(fake.allowingGuard(), fake.baseUrl() + "/");
        pulls = new LocalModelPullService(gateway);
    }

    @AfterEach
    void stop() {
        pulls.shutdown();
        gateway.close();
        fake.close();
    }

    private LocalModelPullService.Status await(String jobId, String wanted) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        LocalModelPullService.Status status;
        do {
            status = pulls.status(jobId).orElseThrow();
            if (status.status().equals(wanted)) {
                return status;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("job never reached " + wanted + ", last: " + status);
    }

    // --- tags ---

    @Test
    void tagsListsInstalledModelsAndKnowsTheLatestAlias() {
        fake.tagsJson = "{\"models\":[{\"name\":\"qwen3.5:4b\"},{\"name\":\"llama3.2:latest\"},{\"model\":\"granite4.2:3b\"}]}";

        LocalModelOllamaGateway.Tags tags = gateway.tags();

        assertTrue(tags.reachable());
        assertEquals(List.of("qwen3.5:4b", "llama3.2:latest", "granite4.2:3b"), tags.installed());
        assertTrue(tags.has("llama3.2"), "a bare name matches :latest");
        assertTrue(tags.has("QWEN3.5:4B"));
        assertFalse(tags.has("qwen3.5:9b"));
        assertFalse(tags.has(null));
    }

    @Test
    void aServerErrorOrAnUnreachableServerIsReportedNotThrown() {
        fake.tagsStatus = 500;
        assertFalse(gateway.tags().reachable());

        fake.close();
        assertFalse(gateway.tags().reachable());
    }

    @Test
    void theSsrfGuardStillAppliesToTheConfiguredUrl() {
        // The default guard refuses loopback; only an operator allow-list entry lets it through.
        LocalModelOllamaGateway refused = new LocalModelOllamaGateway(SsrfGuard.strict(), fake.baseUrl());
        try {
            assertFalse(refused.tags().reachable());
            assertThrows(LocalModelOllamaGateway.PullException.class, () -> refused.pull("qwen3.5:4b", e -> {}));
            assertTrue(fake.pullBodies.isEmpty(), "nothing was sent to the refused host");
        } finally {
            refused.close();
        }
    }

    // --- pull ---

    @Test
    void aPullStreamsProgressAndStopsAtSuccess() throws Exception {
        fake.pullLines = List.of(
                "{\"status\":\"pulling manifest\"}",
                "{\"status\":\"pulling aaa\",\"digest\":\"aaa\",\"total\":100,\"completed\":50}",
                "{\"status\":\"success\"}");
        List<String> statuses = new ArrayList<>();

        gateway.pull("qwen3.5:4b", e -> statuses.add(e.path("status").asText()));

        assertEquals(List.of("pulling manifest", "pulling aaa", "success"), statuses);
        assertTrue(fake.pullBodies.get(0).contains("\"model\":\"qwen3.5:4b\""), fake.pullBodies.get(0));
        assertTrue(fake.pullBodies.get(0).contains("\"stream\":true"));
    }

    @Test
    void anErrorLineAndANonSuccessStatusBecomeAPullException() {
        fake.pullLines = List.of("{\"status\":\"pulling manifest\"}", "{\"error\":\"disk full\"}");
        var e1 = assertThrows(LocalModelOllamaGateway.PullException.class, () -> gateway.pull("a:1", x -> {}));
        assertEquals("disk full", e1.getMessage());

        fake.pullStatus = 404;
        var e2 = assertThrows(LocalModelOllamaGateway.PullException.class, () -> gateway.pull("a:1", x -> {}));
        assertTrue(e2.getMessage().contains("does not exist"), e2.getMessage());

        fake.pullStatus = 200;
        fake.pullLines = List.of("{\"status\":\"pulling manifest\"}");
        var e3 = assertThrows(LocalModelOllamaGateway.PullException.class, () -> gateway.pull("a:1", x -> {}));
        assertTrue(e3.getMessage().contains("closed the download"), e3.getMessage());
    }

    // --- jobs ---

    @Test
    void aJobRunsToDoneWithPercentFromTheLayerBytes() throws Exception {
        fake.pullLines = List.of(
                "{\"status\":\"pulling manifest\"}",
                "{\"status\":\"pulling aaa\",\"digest\":\"aaa\",\"total\":1000,\"completed\":250}",
                "{\"status\":\"pulling bbb\",\"digest\":\"bbb\",\"total\":1000,\"completed\":750}",
                "{\"status\":\"success\"}");
        CountDownLatch release = new CountDownLatch(1);
        fake.holdPull = release;

        String id = pulls.start("qwen3.5:4b");
        LocalModelPullService.Status running = await(id, "running");
        assertEquals("running", running.status());
        release.countDown();
        LocalModelPullService.Status done = await(id, "done");

        assertEquals(100, done.percent());
        assertEquals("Installed", done.message());
    }

    @Test
    void percentIsCappedAt99UntilOllamaSaysSuccess() throws Exception {
        fake.pullLines = List.of(
                "{\"status\":\"pulling aaa\",\"digest\":\"aaa\",\"total\":100,\"completed\":100}",
                "{\"status\":\"verifying sha256 digest\"}",
                "{\"status\":\"success\"}");
        CountDownLatch release = new CountDownLatch(1);
        fake.holdPull = release;

        String id = pulls.start("qwen3.5:4b");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pulls.status(id).orElseThrow().percent() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(99, pulls.status(id).orElseThrow().percent());
        release.countDown();
        assertEquals(100, await(id, "done").percent());
    }

    @Test
    void aFailedPullReportsTheServersReason() throws Exception {
        fake.pullLines = List.of("{\"error\":\"pull model manifest: file does not exist\"}");

        LocalModelPullService.Status failed = await(pulls.start("nope:1b"), "failed");

        assertTrue(failed.message().contains("does not exist"), failed.message());
    }

    @Test
    void askingForATagThatIsAlreadyDownloadingJoinsTheSameJob() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        fake.holdPull = release;
        fake.pullLines = List.of("{\"status\":\"pulling manifest\"}", "{\"status\":\"success\"}");

        String first = pulls.start("qwen3.5:4b");
        await(first, "running");
        String second = pulls.start("QWEN3.5:4B");
        release.countDown();

        assertEquals(first, second);
        await(first, "done");
        assertEquals(1, fake.pullBodies.size());
    }

    @Test
    void moreThanTwoActiveDownloadsAreRefused() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        fake.holdPull = release;
        fake.pullLines = List.of("{\"status\":\"pulling manifest\"}", "{\"status\":\"success\"}");

        String a = pulls.start("a:1b");
        String b = pulls.start("b:1b");
        assertThrows(LocalModelPullService.TooManyPullsException.class, () -> pulls.start("c:1b"));
        release.countDown();
        await(a, "done");
        await(b, "done");

        // Room again once they finished.
        assertTrue(pulls.status(pulls.start("c:1b")).isPresent());
    }

    @Test
    void anUnknownJobIdIsEmpty() {
        assertEquals(Optional.empty(), pulls.status("nope"));
    }
}
