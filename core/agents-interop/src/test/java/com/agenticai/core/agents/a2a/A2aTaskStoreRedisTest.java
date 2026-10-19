package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@code app.a2a.task-store=redis}: two {@link A2aTaskStore} instances share ONE
 * fake Redis (a map behind a mocked {@link ValueOperations}) and stand in for
 * two core replicas. Pins the cross-replica contract (GetTask/CancelTask from
 * the non-owner), tenant scoping of the key, and the fail-open posture.
 */
class A2aTaskStoreRedisTest {

    private static final Duration TTL = Duration.ofHours(1);

    private final Map<String, String> data = new HashMap<>();
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private A2aTaskStore replicaA;
    private A2aTaskStore replicaB;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(inv -> data.get(inv.<String>getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            data.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        replicaA = new A2aTaskStore(TTL, 100, redis);
        replicaB = new A2aTaskStore(TTL, 100, redis);
    }

    private static A2aTask task(String tenant, String subject) {
        return new A2aTask(UUID.randomUUID().toString(), "ctx", UUID.randomUUID(), tenant, subject, "m1", "hi");
    }

    @Test
    void aTaskCreatedOnReplicaAIsReadableFromReplicaB() {
        A2aTask task = task("tenant_a", "alice");
        replicaA.put(task);
        task.markWorking();
        task.appendDelta("hello");
        task.markCompleted();
        replicaA.save(task);

        A2aTask remote = replicaB.find(task.id(), "tenant_a", "alice").orElseThrow();

        assertEquals(A2aTaskState.COMPLETED, remote.snapshot().state());
        assertEquals("hello", remote.snapshot().reply());
        assertEquals(task.artifactId(), remote.artifactId());
        assertEquals(task.agentId(), remote.agentId());
        assertTrue(remote.completion().isDone(), "a terminal restored task must not park a waiter");
        verify(values, atLeastOnce()).set(eq(A2aTaskStore.key("tenant_a", task.id())), anyString(), eq(TTL));
    }

    @Test
    void remoteLookupIsTenantAndSubjectScopedAndIndistinguishableFromMissing() {
        A2aTask task = task("tenant_a", "alice");
        replicaA.put(task);

        assertTrue(replicaB.find(task.id(), "tenant_b", "alice").isEmpty(), "other tenant computes another key");
        assertTrue(replicaB.find(task.id(), "tenant_a", "bob").isEmpty());
        assertTrue(replicaB.find(task.id(), null, null).isEmpty());
        assertTrue(replicaB.find("nope", "tenant_a", "alice").isEmpty());
        assertTrue(replicaB.find(task.id(), "tenant_a", "alice").isPresent());
    }

    @Test
    void nullTenantTasksUseTheirOwnKeySegment() {
        A2aTask task = task(null, null);
        replicaA.put(task);

        assertTrue(replicaB.find(task.id(), null, null).isPresent());
        assertTrue(replicaB.find(task.id(), "tenant_a", "alice").isEmpty());
    }

    @Test
    void cancelFromTheNonOwnerSticksAndTheOwnerCutsItsTurnOnNextLookup() {
        A2aTask task = task("tenant_a", "alice");
        replicaA.put(task);
        task.markWorking();
        replicaA.save(task);

        A2aTask remote = replicaB.find(task.id(), "tenant_a", "alice").orElseThrow();
        assertTrue(remote.cancel());
        replicaB.save(remote);

        // The owner's turn finishing a moment later must not resurrect the task.
        task.markCompleted();
        replicaA.save(task);
        assertEquals(A2aTaskState.CANCELED, replicaB.find(task.id(), "tenant_a", "alice").orElseThrow().snapshot().state());

        // ...and the owner applies the cancel locally when the task is looked up there.
        A2aTask other = task("tenant_a", "alice");
        replicaA.put(other);
        other.markWorking();
        replicaA.save(other);
        A2aTask otherRemote = replicaB.find(other.id(), "tenant_a", "alice").orElseThrow();
        otherRemote.cancel();
        replicaB.save(otherRemote);
        assertEquals(A2aTaskState.CANCELED, replicaA.find(other.id(), "tenant_a", "alice").orElseThrow().snapshot().state());
    }

    @Test
    void redisErrorsFailOpenToTheInMemoryCopy() {
        doThrow(new RedisConnectionFailureException("down")).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        A2aTask task = task("tenant_a", "alice");

        replicaA.put(task);

        assertTrue(replicaA.find(task.id(), "tenant_a", "alice").isPresent(), "local copy still answers");
        assertFalse(replicaB.find(task.id(), "tenant_a", "alice").isPresent(), "remote miss, not an exception");
    }

    @Test
    void memoryModeNeverTouchesRedis() {
        A2aTaskStore memory = new A2aTaskStore(TTL, 100);
        A2aTask task = task("tenant_a", "alice");
        memory.put(task);
        memory.save(task);

        assertTrue(memory.find(task.id(), "tenant_a", "alice").isPresent());
        assertTrue(data.isEmpty());
    }

    // ---- durability: orphan reconciliation, heartbeat, remote cancel ------------------

    private A2aTaskStore restartedWithStaleAfter(Duration staleAfter) {
        return new A2aTaskStore(TTL, 100, redis, Duration.ofSeconds(10), staleAfter);
    }

    @Test
    void workingTaskOfAPreviousInstanceIsFailedAsInterruptedByRestart() throws Exception {
        A2aTask task = task("tenant_a", "alice");
        replicaA.put(task);
        task.markWorking();
        replicaA.save(task);

        A2aTaskStore restarted = restartedWithStaleAfter(Duration.ofMillis(5));
        Thread.sleep(30);

        assertTrue(restarted.reconcileKey(A2aTaskStore.key("tenant_a", task.id())));

        A2aTask seen = restarted.find(task.id(), "tenant_a", "alice").orElseThrow();
        assertEquals(A2aTaskState.FAILED, seen.snapshot().state());
        assertEquals("interrupted by restart", seen.snapshot().failureMessage());
        // A second pass is a no-op: the record is terminal now.
        assertFalse(restarted.reconcileKey(A2aTaskStore.key("tenant_a", task.id())));
    }

    @Test
    void staleWorkingTaskIsAlsoFailedLazilyOnRead() throws Exception {
        A2aTask task = task("tenant_a", "alice");
        replicaA.put(task);
        task.markWorking();
        replicaA.save(task);

        A2aTaskStore restarted = restartedWithStaleAfter(Duration.ofMillis(5));
        Thread.sleep(30);

        A2aTask seen = restarted.find(task.id(), "tenant_a", "alice").orElseThrow();
        assertEquals(A2aTaskState.FAILED, seen.snapshot().state());
        assertEquals("interrupted by restart", seen.snapshot().failureMessage());
    }

    @Test
    void taskWithAFreshHeartbeatOrTerminalStateIsNotTouched() {
        A2aTask working = task("tenant_a", "alice");
        replicaA.put(working);
        working.markWorking();
        replicaA.save(working);
        A2aTask done = task("tenant_a", "alice");
        replicaA.put(done);
        done.markWorking();
        done.markCompleted();
        replicaA.save(done);

        assertFalse(replicaB.reconcileKey(A2aTaskStore.key("tenant_a", working.id())));
        assertFalse(replicaB.reconcileKey(A2aTaskStore.key("tenant_a", done.id())));
        assertEquals(A2aTaskState.WORKING,
            replicaB.find(working.id(), "tenant_a", "alice").orElseThrow().snapshot().state());
        assertEquals(A2aTaskState.COMPLETED,
            replicaB.find(done.id(), "tenant_a", "alice").orElseThrow().snapshot().state());
    }

    @Test
    void anInstanceNeverFailsItsOwnLiveTasks() throws Exception {
        A2aTaskStore self = restartedWithStaleAfter(Duration.ofMillis(5));
        A2aTask task = task("tenant_a", "alice");
        self.put(task);
        task.markWorking();
        self.save(task);
        Thread.sleep(30);

        assertFalse(self.reconcileKey(A2aTaskStore.key("tenant_a", task.id())));
    }

    @Test
    void heartbeatKeepsALongRunningTaskFromLookingOrphaned() throws Exception {
        A2aTask task = task("tenant_a", "alice");
        replicaA.put(task);
        task.markWorking();
        replicaA.save(task);
        A2aTaskStore peer = restartedWithStaleAfter(Duration.ofMillis(400));

        Thread.sleep(300);
        replicaA.heartbeat();
        Thread.sleep(200);

        assertFalse(peer.reconcileKey(A2aTaskStore.key("tenant_a", task.id())),
            "500ms since the state change but only 200ms since the heartbeat");
        assertEquals(A2aTaskState.WORKING, peer.find(task.id(), "tenant_a", "alice").orElseThrow().snapshot().state());
    }

    @Test
    void cancelFromTheNonOwnerIsPublishedAndTheOwnerCutsTheTurnOnReceipt() {
        A2aTask task = task("tenant_a", "alice");
        replicaA.put(task);
        task.markWorking();
        replicaA.save(task);

        A2aTask remote = replicaB.find(task.id(), "tenant_a", "alice").orElseThrow();
        assertTrue(remote.cancel());
        replicaB.save(remote);

        String key = A2aTaskStore.key("tenant_a", task.id());
        verify(redis).convertAndSend(A2aTaskStore.CANCEL_CHANNEL, key);
        assertFalse(task.isTerminal(), "owner has not heard yet");

        replicaA.onCancelMessage(key);

        assertEquals(A2aTaskState.CANCELED, task.snapshot().state());
        assertTrue(task.completion().isDone());
    }

    @Test
    void ownerHeartbeatAppliesARemoteCancelEvenWithoutPubSubAndNeverResurrectsIt() {
        A2aTask task = task("tenant_a", "alice");
        replicaA.put(task);
        task.markWorking();
        replicaA.save(task);
        A2aTask remote = replicaB.find(task.id(), "tenant_a", "alice").orElseThrow();
        remote.cancel();
        replicaB.save(remote);

        replicaA.heartbeat();

        assertEquals(A2aTaskState.CANCELED, task.snapshot().state());
        assertEquals(A2aTaskState.CANCELED,
            replicaB.find(task.id(), "tenant_a", "alice").orElseThrow().snapshot().state());
    }

    @Test
    void ownerCancelsItsLocalTurnWhenAPeerFailedItAsOrphaned() throws Exception {
        A2aTask task = task("tenant_a", "alice");
        replicaA.put(task);
        task.markWorking();
        replicaA.save(task);
        A2aTaskStore peer = restartedWithStaleAfter(Duration.ofMillis(5));
        Thread.sleep(30);
        assertTrue(peer.reconcileKey(A2aTaskStore.key("tenant_a", task.id())));

        replicaA.heartbeat();

        assertTrue(task.isTerminal(), "the live owner must stop the turn once a peer failed its record");
        assertEquals(A2aTaskState.FAILED,
            peer.find(task.id(), "tenant_a", "alice").orElseThrow().snapshot().state());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconcileScanFailsOrphansAndLeavesTheRest() throws Exception {
        A2aTask orphan = task("tenant_a", "alice");
        replicaA.put(orphan);
        orphan.markWorking();
        replicaA.save(orphan);
        A2aTask done = task("tenant_a", "alice");
        replicaA.put(done);
        done.markWorking();
        done.markCompleted();
        replicaA.save(done);
        java.util.Iterator<String> it = java.util.List.of(
            A2aTaskStore.key("tenant_a", orphan.id()), A2aTaskStore.key("tenant_a", done.id()),
            "a2a:task:tenant_a:does-not-exist").iterator();
        org.springframework.data.redis.core.Cursor<String> cursor =
            mock(org.springframework.data.redis.core.Cursor.class);
        when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
        when(cursor.next()).thenAnswer(inv -> it.next());
        when(redis.scan(any(org.springframework.data.redis.core.ScanOptions.class))).thenReturn(cursor);
        A2aTaskStore restarted = restartedWithStaleAfter(Duration.ofMillis(5));
        Thread.sleep(30);

        restarted.reconcileOrphans();

        assertEquals(A2aTaskState.FAILED,
            restarted.find(orphan.id(), "tenant_a", "alice").orElseThrow().snapshot().state());
        assertEquals(A2aTaskState.COMPLETED,
            restarted.find(done.id(), "tenant_a", "alice").orElseThrow().snapshot().state());
        verify(cursor).close();
    }

    @Test
    void heartbeatAndReconcileAreNoOpsInMemoryMode() {
        A2aTaskStore memory = new A2aTaskStore(TTL, 100);
        A2aTask task = task("tenant_a", "alice");
        memory.put(task);
        memory.heartbeat();
        memory.reconcileOrphans();
        memory.onCancelMessage("a2a:task:tenant_a:" + task.id());

        assertFalse(task.isTerminal());
        assertTrue(data.isEmpty());
    }
}
