package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Pure-logic coverage of {@link NodeErrorStrategy}: parsing, the irreversible guard, backoff and
 * attempt counting.
 */
class NodeErrorStrategyTest {

    private static WorkflowNodeDto node(String type, Map<String, Object> config) {
        return new WorkflowNodeDto(
                "n1", type, Map.of(), Map.of("config", config, "label", "Node 1"));
    }

    private static Map<String, Object> retry(
            Object maxAttempts, Object backoffMs, String backoff, String retryOn) {
        Map<String, Object> r = new HashMap<>();
        if (maxAttempts != null) {
            r.put("maxAttempts", maxAttempts);
        }
        if (backoffMs != null) {
            r.put("backoffMs", backoffMs);
        }
        if (backoff != null) {
            r.put("backoff", backoff);
        }
        if (retryOn != null) {
            r.put("retryOn", retryOn);
        }
        return r;
    }

    private static NodeErrorStrategy.Retry retryOf(String type, Map<String, Object> config) {
        NodeErrorStrategy.Policy p = NodeErrorStrategy.parse(node(type, config));
        assertNotNull(p);
        assertNotNull(p.retry());
        return p.retry();
    }

    // ---- parsing
    // ----------------------------------------------------------------------------------------------

    @Test
    void nothingConfiguredOrExplicitFailIsNoPolicyAtAll() {
        assertNull(NodeErrorStrategy.parse(node("agent", Map.of())));
        assertNull(NodeErrorStrategy.parse(node("agent", Map.of("onError", "fail"))));
        assertNull(
                NodeErrorStrategy.parse(node("agent", Map.of("onError", "garbage"))),
                "unknown values degrade to fail");
        assertNull(NodeErrorStrategy.parse(node("agent", Map.of("retry", "not-a-map"))));
    }

    @Test
    void onErrorValuesParseCaseInsensitively() {
        assertEquals(
                NodeErrorStrategy.OnError.CONTINUE,
                NodeErrorStrategy.parse(node("agent", Map.of("onError", " Continue "))).onError());
        assertEquals(
                NodeErrorStrategy.OnError.BRANCH,
                NodeErrorStrategy.parse(node("agent", Map.of("onError", "BRANCH"))).onError());
    }

    @Test
    void retryConfigIsClampedAndDefaulted() {
        NodeErrorStrategy.Retry r =
                retryOf("agent", Map.of("retry", retry(99, 999_999, "exponential", "transient")));
        assertEquals(5, r.maxAttempts(), "maxAttempts is capped at 5");
        assertEquals(60_000L, r.backoffMs(), "backoffMs is capped at 60s");
        assertTrue(r.exponential());
        assertTrue(r.transientOnly());

        NodeErrorStrategy.Retry defaults =
                retryOf("agent", Map.of("retry", new HashMap<String, Object>()));
        assertEquals(3, defaults.maxAttempts());
        assertEquals(1_000L, defaults.backoffMs());
        assertFalse(defaults.exponential());
        assertFalse(defaults.transientOnly());

        NodeErrorStrategy.Retry low = retryOf("agent", Map.of("retry", retry(0, -5, null, null)));
        assertEquals(1, low.maxAttempts(), "never fewer than one attempt");
        assertEquals(0L, low.backoffMs());

        NodeErrorStrategy.Retry stringy =
                retryOf("agent", Map.of("retry", retry("4", "250", null, null)));
        assertEquals(4, stringy.maxAttempts());
        assertEquals(250L, stringy.backoffMs());
    }

    // ---- irreversible-node guard
    // ---------------------------------------------------------------------------------

    @Test
    void irreversibleNodeTypesAreNeverRetriedWithoutTheExplicitOptIn() {
        for (String type :
                List.of(
                        "bulk_email",
                        "tool",
                        "device_control",
                        "crm_lead",
                        "media",
                        "output",
                        "workflow")) {
            NodeErrorStrategy.Retry r = retryOf(type, Map.of("retry", retry(4, 0, null, null)));
            assertEquals(1, r.maxAttempts(), type + " must be limited to a single attempt");
            assertEquals(4, r.requestedAttempts());
            assertTrue(r.blockedByGuard(), type);
        }
    }

    @Test
    void retryUnsafeLiftsTheGuardOnTheNodeOrInsideTheRetryBlock() {
        Map<String, Object> onNode = new HashMap<>();
        onNode.put("retry", retry(4, 0, null, null));
        onNode.put("retryUnsafe", true);
        NodeErrorStrategy.Retry r = retryOf("bulk_email", onNode);
        assertEquals(4, r.maxAttempts());
        assertFalse(r.blockedByGuard());

        Map<String, Object> inside = retry(3, 0, null, null);
        inside.put("retryUnsafe", "true");
        assertEquals(3, retryOf("tool", Map.of("retry", inside)).maxAttempts());
    }

    @Test
    void httpIsIrreversibleExceptForReadMethods() {
        assertFalse(
                NodeErrorStrategy.isIrreversible(node("http", Map.of())), "default method is GET");
        assertFalse(NodeErrorStrategy.isIrreversible(node("http", Map.of("method", "get"))));
        assertFalse(NodeErrorStrategy.isIrreversible(node("http", Map.of("method", "HEAD"))));
        assertTrue(NodeErrorStrategy.isIrreversible(node("http", Map.of("method", "POST"))));
        assertTrue(NodeErrorStrategy.isIrreversible(node("http", Map.of("method", "delete"))));
        assertTrue(
                NodeErrorStrategy.isIrreversible(node("http", Map.of("method", "{{input.m}}"))),
                "unknown = unsafe");

        assertEquals(
                1,
                retryOf("http", Map.of("method", "POST", "retry", retry(3, 0, null, null)))
                        .maxAttempts());
        assertEquals(
                3,
                retryOf("http", Map.of("method", "GET", "retry", retry(3, 0, null, null)))
                        .maxAttempts());
    }

    @Test
    void agentKnowledgeCodeAndConditionNodesAreRetryable() {
        for (String type : List.of("agent", "knowledge", "code", "condition", "supervisor")) {
            assertFalse(NodeErrorStrategy.isIrreversible(node(type, Map.of())), type);
        }
    }

    // ---- backoff
    // -----------------------------------------------------------------------------------------------

    @Test
    void fixedBackoffIsConstantAndExponentialDoublesUpToTheCap() {
        NodeErrorStrategy.Retry fixed =
                retryOf("agent", Map.of("retry", retry(5, 700, "fixed", null)));
        assertEquals(700, NodeErrorStrategy.delayMillis(fixed, 1));
        assertEquals(700, NodeErrorStrategy.delayMillis(fixed, 4));

        NodeErrorStrategy.Retry exp =
                retryOf("agent", Map.of("retry", retry(5, 1000, "exponential", null)));
        assertEquals(1_000, NodeErrorStrategy.delayMillis(exp, 1));
        assertEquals(2_000, NodeErrorStrategy.delayMillis(exp, 2));
        assertEquals(4_000, NodeErrorStrategy.delayMillis(exp, 3));
        assertEquals(8_000, NodeErrorStrategy.delayMillis(exp, 4));
        assertEquals(
                60_000, NodeErrorStrategy.delayMillis(exp, 30), "capped, and no shift overflow");
    }

    // ---- which failures retry
    // ----------------------------------------------------------------------------------

    @Test
    void quotaRejectionsAreNeverRetried() {
        NodeErrorStrategy.Retry all = retryOf("agent", Map.of("retry", retry(3, 0, null, "all")));
        assertFalse(
                NodeErrorStrategy.shouldRetry(all, new TenantLimitExceededException("over quota")));
        assertTrue(NodeErrorStrategy.shouldRetry(all, new RuntimeException("anything")));
    }

    @Test
    void transientClassificationRecognisesTimeoutsOverloadAndNetworkErrors() {
        assertTrue(NodeErrorStrategy.isTransient(new RuntimeException("Read timed out")));
        assertTrue(NodeErrorStrategy.isTransient(new RuntimeException("HTTP 503 from provider")));
        assertTrue(NodeErrorStrategy.isTransient(new RuntimeException("429 Too Many Requests")));
        assertTrue(
                NodeErrorStrategy.isTransient(
                        new RuntimeException("wrapper", new IOException("connection reset"))));
        assertTrue(NodeErrorStrategy.isTransient(new java.util.concurrent.TimeoutException("x")));
        assertTrue(
                NodeErrorStrategy.isTransient(
                        new IllegalStateException("The model is overloaded")));

        assertFalse(
                NodeErrorStrategy.isTransient(
                        new IllegalArgumentException("agentId is not a UUID")));
        assertFalse(
                NodeErrorStrategy.isTransient(new RuntimeException("Agent 5030 not found")),
                "5030 is not 503");
        assertFalse(NodeErrorStrategy.isTransient(new RuntimeException("bad configuration")));
    }

    @Test
    void transientOnlySkipsDeterministicFailures() {
        NodeErrorStrategy.Retry onlyTransient =
                retryOf("agent", Map.of("retry", retry(3, 0, null, "transient")));
        assertFalse(
                NodeErrorStrategy.shouldRetry(
                        onlyTransient, new RuntimeException("bad configuration")));
        assertTrue(
                NodeErrorStrategy.shouldRetry(
                        onlyTransient, new RuntimeException("upstream timeout")));
    }

    // ---- attempt counting
    // ---------------------------------------------------------------------------------------

    private static <T> T run(
            NodeErrorStrategy.Retry retry,
            AtomicInteger calls,
            List<NodeErrorStrategy.Attempt> failed,
            List<Long> slept,
            java.util.function.Supplier<T> body) {
        return NodeErrorStrategy.runWithRetry(
                retry,
                () -> {
                    calls.incrementAndGet();
                    return body.get();
                },
                slept::add,
                Instant::now,
                failed);
    }

    @Test
    void succeedsOnTheThirdAttemptAfterTwoTracedFailures() {
        NodeErrorStrategy.Retry retry =
                retryOf("agent", Map.of("retry", retry(3, 200, "exponential", null)));
        AtomicInteger calls = new AtomicInteger();
        List<NodeErrorStrategy.Attempt> failed = new ArrayList<>();
        List<Long> slept = new ArrayList<>();

        String result =
                run(
                        retry,
                        calls,
                        failed,
                        slept,
                        () -> {
                            if (calls.get() < 3) {
                                throw new RuntimeException("flaky " + calls.get());
                            }
                            return "done";
                        });

        assertEquals("done", result);
        assertEquals(3, calls.get());
        assertEquals(2, failed.size());
        assertEquals(1, failed.get(0).number());
        assertEquals(3, failed.get(0).maxAttempts());
        assertEquals("flaky 1", failed.get(0).error());
        assertEquals(List.of(200L, 400L), slept, "exponential waits after attempt 1 and 2");
    }

    @Test
    void exhaustingAllAttemptsRethrowsTheLastFailureAfterMaxAttemptsMinusOneRetries() {
        NodeErrorStrategy.Retry retry = retryOf("agent", Map.of("retry", retry(4, 0, null, null)));
        AtomicInteger calls = new AtomicInteger();
        List<NodeErrorStrategy.Attempt> failed = new ArrayList<>();

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                run(
                                        retry,
                                        calls,
                                        failed,
                                        new ArrayList<>(),
                                        () -> {
                                            throw new RuntimeException("always " + calls.get());
                                        }));

        assertEquals("always 4", thrown.getMessage());
        assertEquals(4, calls.get());
        assertEquals(
                3,
                failed.size(),
                "the final failure is rethrown, not recorded as a retried attempt");
    }

    @Test
    void anIrreversibleNodeRunsExactlyOnceEvenWhenThreeAttemptsWereRequested() {
        NodeErrorStrategy.Retry retry =
                retryOf("bulk_email", Map.of("retry", retry(3, 0, null, null)));
        AtomicInteger calls = new AtomicInteger();

        assertThrows(
                RuntimeException.class,
                () ->
                        run(
                                retry,
                                calls,
                                new ArrayList<>(),
                                new ArrayList<>(),
                                () -> {
                                    throw new RuntimeException("smtp down");
                                }));

        assertEquals(1, calls.get(), "a duplicated send is worse than a failure");
    }

    @Test
    void withTheOptInAnIrreversibleNodeIsRetried() {
        Map<String, Object> config = new HashMap<>();
        config.put("retry", retry(3, 0, null, null));
        config.put("retryUnsafe", true);
        NodeErrorStrategy.Retry retry = retryOf("bulk_email", config);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(
                RuntimeException.class,
                () ->
                        run(
                                retry,
                                calls,
                                new ArrayList<>(),
                                new ArrayList<>(),
                                () -> {
                                    throw new RuntimeException("smtp down");
                                }));

        assertEquals(3, calls.get());
    }

    @Test
    void transientOnlyStopsImmediatelyOnADeterministicFailure() {
        NodeErrorStrategy.Retry retry =
                retryOf("agent", Map.of("retry", retry(5, 0, null, "transient")));
        AtomicInteger calls = new AtomicInteger();

        assertThrows(
                RuntimeException.class,
                () ->
                        run(
                                retry,
                                calls,
                                new ArrayList<>(),
                                new ArrayList<>(),
                                () -> {
                                    throw new IllegalArgumentException("agentId is invalid");
                                }));

        assertEquals(1, calls.get());
    }

    @Test
    void aQuotaRejectionIsNotRetriedEvenUnderRetryOnAll() {
        NodeErrorStrategy.Retry retry = retryOf("agent", Map.of("retry", retry(5, 0, null, "all")));
        AtomicInteger calls = new AtomicInteger();

        assertThrows(
                TenantLimitExceededException.class,
                () ->
                        run(
                                retry,
                                calls,
                                new ArrayList<>(),
                                new ArrayList<>(),
                                () -> {
                                    throw new TenantLimitExceededException("over quota");
                                }));

        assertEquals(1, calls.get());
    }

    @Test
    void anInterruptedBackoffStopsRetryingAndKeepsTheInterruptFlag() {
        NodeErrorStrategy.Retry retry =
                retryOf("agent", Map.of("retry", retry(5, 500, null, null)));
        AtomicInteger calls = new AtomicInteger();

        try {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            NodeErrorStrategy.runWithRetry(
                                    retry,
                                    () -> {
                                        calls.incrementAndGet();
                                        throw new RuntimeException("x");
                                    },
                                    millis -> {
                                        throw new InterruptedException("stop");
                                    },
                                    Instant::now,
                                    new ArrayList<>()));
            assertEquals(1, calls.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // clear the flag so it cannot leak into other tests
        }
    }

    // ---- trace entries & continue output
    // ------------------------------------------------------------------------

    @Test
    void everyRetriedAttemptBecomesAnErrorTraceEntry() {
        NodeErrorStrategy.Retry retry =
                retryOf("agent", Map.of("retry", retry(3, 100, null, null)));
        AtomicInteger calls = new AtomicInteger();
        List<NodeErrorStrategy.Attempt> failed = new ArrayList<>();
        run(
                retry,
                calls,
                failed,
                new ArrayList<>(),
                () -> {
                    if (calls.get() < 3) {
                        throw new RuntimeException("boom");
                    }
                    return "ok";
                });

        List<NodeTraceEntry> entries =
                NodeErrorStrategy.attemptEntries(node("agent", Map.of()), failed, retry, false);

        assertEquals(2, entries.size());
        assertEquals("ERROR", entries.get(0).status());
        assertEquals("Node 1 (attempt 1/3)", entries.get(0).label());
        assertTrue(entries.get(0).summary().contains("Attempt 1/3 failed: boom"));
        assertTrue(entries.get(1).summary().contains("retrying in 100 ms"));
    }

    @Test
    void aGuardBlockedFailureGetsAnExplanatoryEntryOnlyWhenItFailedForGood() {
        WorkflowNodeDto n = node("bulk_email", Map.of("retry", retry(3, 0, null, null)));
        NodeErrorStrategy.Retry retry = NodeErrorStrategy.parse(n).retry();

        assertTrue(NodeErrorStrategy.attemptEntries(n, List.of(), retry, false).isEmpty());
        List<NodeTraceEntry> entries = NodeErrorStrategy.attemptEntries(n, List.of(), retry, true);
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).summary().contains("retryUnsafe=true"));
        assertTrue(entries.get(0).summary().contains("3 attempts were reduced to 1"));
    }

    @Test
    void continueOutputIsTheErrorAndNodeIdObject() throws Exception {
        String json =
                NodeErrorStrategy.continueOutput(
                        com.agenticai.core.json.JsonMappers.plain(),
                        node("agent", Map.of()),
                        "it broke");
        JsonNode parsed = com.agenticai.core.json.JsonMappers.plain().readTree(json);
        assertEquals("it broke", parsed.get("error").asString());
        assertEquals("n1", parsed.get("nodeId").asString());
        assertEquals(2, parsed.size());

        JsonNode blank =
                com.agenticai.core.json.JsonMappers.plain()
                        .readTree(
                                NodeErrorStrategy.continueOutput(
                                        com.agenticai.core.json.JsonMappers.plain(),
                                        node("agent", Map.of()),
                                        " "));
        assertEquals("Node failed.", blank.get("error").asString());
    }
}
