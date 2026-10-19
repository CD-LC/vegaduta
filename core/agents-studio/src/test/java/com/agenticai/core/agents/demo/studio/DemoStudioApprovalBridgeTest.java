package com.agenticai.core.agents.demo.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.demo.studio.DemoStudioWorkflowRunner.Definition;
import com.agenticai.core.agents.demo.studio.DemoStudioWorkflowRunner.Step;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@code app.demo.studio.approval-store=redis}: a studio run parked on an approval step in replica A is released by a
 * decision POSTed to replica B. Two runners share one in-memory fake of the Redis commands the bridge uses (not run
 * against a real Redis).
 */
class DemoStudioApprovalBridgeTest {

    private final UUID agentId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    // Shared by the two runner threads and the test thread, so it must be thread-safe.
    private final Map<String, String> redisValues = new ConcurrentHashMap<>();
    /** Counted down when the owner replica marks a run pending in (fake) Redis, i.e. when it has parked. */
    private final CountDownLatch parked = new CountDownLatch(1);
    private final AtomicReference<String> parkedRunKey = new AtomicReference<>();
    private DemoStudioService studio;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private DemoSession session;
    private DemoStudioWorkflowRunner replicaA;
    private DemoStudioWorkflowRunner replicaB;
    private DemoStudioWorkflowRunner replicaWithoutBridge;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void twoReplicasOneRedis() {
        studio = mock(DemoStudioService.class);
        DemoSessionService sessions = mock(DemoSessionService.class);
        DemoStudioRunRepository runs = mock(DemoStudioRunRepository.class);
        session = mock(DemoSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getMessageCount()).thenReturn(0);
        when(sessions.messageLimit()).thenReturn(20);
        when(runs.save(any(DemoStudioRun.class))).thenAnswer(inv -> {
            DemoStudioRun run = inv.getArgument(0);
            if (run.getId() == null) {
                java.lang.reflect.Field id = DemoStudioRun.class.getDeclaredField("id");
                id.setAccessible(true);
                id.set(run, UUID.randomUUID());
            }
            return run;
        });
        DemoStudioModerationService moderation = mock(DemoStudioModerationService.class);

        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.doAnswer(call -> {
            String redisKey = call.getArgument(0);
            redisValues.put(redisKey, call.getArgument(1));
            if (redisKey.startsWith(DemoStudioApprovalBridge.PENDING_PREFIX)) {
                parkedRunKey.set(redisKey);
                parked.countDown();
            }
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenAnswer(call -> redisValues.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
        when(ops.get(anyString())).thenAnswer(call -> redisValues.get(call.<String>getArgument(0)));
        when(redis.hasKey(anyString())).thenAnswer(call -> redisValues.containsKey(call.<String>getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(call -> redisValues.remove(call.<String>getArgument(0)) != null);

        replicaA = new DemoStudioWorkflowRunner(studio, sessions, runs, new ObjectMapper(), moderation);
        replicaB = new DemoStudioWorkflowRunner(studio, sessions, runs, new ObjectMapper(), moderation);
        replicaWithoutBridge = new DemoStudioWorkflowRunner(studio, sessions, runs, new ObjectMapper(), moderation);
        replicaA.useApprovalBridge(new DemoStudioApprovalBridge(redis));
        replicaB.useApprovalBridge(new DemoStudioApprovalBridge(redis));
    }

    private Definition approvalWorkflow() {
        return new Definition(List.of(
            new Step("t", "trigger", "Input", Map.of()),
            new Step("a", "agent", "Agent a", Map.of("agentId", agentId.toString(), "instruction", "{{input}}")),
            new Step("ok", "approval", "Sign off", Map.of("message", "Publish?")),
            new Step("o", "output", "Out", Map.of())));
    }

    private CompletableFuture<List<Map<String, Object>>> startRunOnA() {
        when(studio.runMissionBlocking(any(), any(), anyString(), anyString(), any())).thenReturn("draft");
        DemoStudioWorkflow workflow = new DemoStudioWorkflow(
            sessionId, "203.0.113.9", "wf", replicaA.serialize(approvalWorkflow()), java.time.Instant.now());
        ObjectMapper mapper = new ObjectMapper();
        return CompletableFuture.supplyAsync(() -> replicaA.run(session, workflow, "topic")
            .map(line -> {
                try {
                    return mapper.readValue(line, new TypeReference<Map<String, Object>>() { });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })
            .collectList()
            .block(Duration.ofSeconds(20)));
    }

    /** Blocks (latch, not polling) until replica A has parked a run and marked it pending in Redis. */
    private UUID parkedRunIdOnA() throws Exception {
        assertTrue(parked.await(10, TimeUnit.SECONDS), "no run parked on approval in replica A");
        return UUID.fromString(parkedRunKey.get().substring(DemoStudioApprovalBridge.PENDING_PREFIX.length()));
    }

    @Test
    void aDecisionPostedToAnotherReplicaReleasesTheParkedRun() throws Exception {
        CompletableFuture<List<Map<String, Object>>> result = startRunOnA();
        UUID runId = parkedRunIdOnA();
        // The pending marker is visible to the other replica before anyone decides (the latch fired on it).
        assertTrue(redisValues.containsKey(DemoStudioApprovalBridge.PENDING_PREFIX + runId));

        assertTrue(replicaB.decide(runId, true), "replica B has no local waiter, the Redis marker must accept it");

        List<Map<String, Object>> events = result.get(20, TimeUnit.SECONDS);
        assertTrue(events.stream().anyMatch(e -> "approval".equals(e.get("type")) && Boolean.TRUE.equals(e.get("approved"))));
        assertEquals("COMPLETED", events.get(events.size() - 1).get("status"));
        // Both keys are gone once the run ends, so a late second decision is not accepted.
        assertFalse(replicaB.decide(runId, false));
    }

    @Test
    void aSecondDecisionWhileTheRunIsStillParkedIsNotAccepted() throws Exception {
        CompletableFuture<List<Map<String, Object>>> result = startRunOnA();
        UUID runId = parkedRunIdOnA();

        assertTrue(replicaB.decide(runId, false));
        assertFalse(replicaB.decide(runId, true), "SET NX: the first decision wins");

        List<Map<String, Object>> events = result.get(20, TimeUnit.SECONDS);
        assertEquals("REJECTED", events.get(events.size() - 1).get("status"));
    }

    @Test
    void withoutTheBridgeADecisionOnAnotherReplicaIsStillNotAccepted() throws Exception {
        CompletableFuture<List<Map<String, Object>>> result = startRunOnA();
        UUID runId = parkedRunIdOnA();

        assertFalse(replicaWithoutBridge.decide(runId, true), "default behaviour: only the owning replica can decide");

        assertTrue(replicaA.decide(runId, true)); // release the run so the test can finish
        assertEquals("COMPLETED", result.get(20, TimeUnit.SECONDS).stream().reduce((first, second) -> second).get().get("status"));
    }

    @Test
    void aRedisFailureAnswersNoPendingRunAndNoDecision() {
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(ops.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        DemoStudioApprovalBridge bridge = new DemoStudioApprovalBridge(redis);

        assertFalse(bridge.offer(UUID.randomUUID(), true));
        assertNull(bridge.takeDecision(UUID.randomUUID()));
    }

    @Test
    void aHangingRedisIsBoundedAndAnswersNoPendingRun() {
        CountDownLatch neverReleased = new CountDownLatch(1);
        when(redis.hasKey(anyString())).thenAnswer(call -> {
            neverReleased.await(); // black-holed connection: the real Lettuce call would wait 60 s
            return true;
        });
        DemoStudioApprovalBridge bridge = new DemoStudioApprovalBridge(redis);

        long started = System.nanoTime();
        assertFalse(bridge.offer(UUID.randomUUID(), true));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 5000, "the controller thread must not wait for a hung Redis, waited " + elapsedMillis);
    }
}
