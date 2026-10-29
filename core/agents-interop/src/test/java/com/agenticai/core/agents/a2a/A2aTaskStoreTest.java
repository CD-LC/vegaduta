package com.agenticai.core.agents.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Owner scoping and TTL behavior of the in-memory task store - the two
 * properties {@link A2aTaskStore}'s javadoc promises despite (and because of)
 * the store being per-JVM.
 */
class A2aTaskStoreTest {

    private static A2aTask task(String tenant, String subject) {
        return new A2aTask(UUID.randomUUID().toString(), "ctx", UUID.randomUUID(), tenant, subject, "m1", "hi");
    }

    @Test
    void findRequiresTheSameTenantAndSubject() {
        A2aTaskStore store = new A2aTaskStore(Duration.ofHours(1), 100);
        A2aTask task = task("tenant_a", "alice");
        store.put(task);

        assertTrue(store.find(task.id(), "tenant_a", "alice").isPresent());
        // Another tenant, another user in the same tenant, and a null-context
        // caller all get the same answer as a made-up id: nothing.
        assertTrue(store.find(task.id(), "tenant_b", "alice").isEmpty());
        assertTrue(store.find(task.id(), "tenant_a", "bob").isEmpty());
        assertTrue(store.find(task.id(), null, null).isEmpty());
        assertTrue(store.find("nope", "tenant_a", "alice").isEmpty());
        assertTrue(store.find(null, "tenant_a", "alice").isEmpty());
    }

    @Test
    void nullOwnerOnlyMatchesNullOwner() {
        // "insecure" profile: no TenantContext, no principal.
        A2aTaskStore store = new A2aTaskStore(Duration.ofHours(1), 100);
        A2aTask task = task(null, null);
        store.put(task);

        assertTrue(store.find(task.id(), null, null).isPresent());
        assertTrue(store.find(task.id(), "tenant_a", "alice").isEmpty());
    }

    @Test
    void sweepExpiresTasksPastTheTtlAndCancelsUnfinishedOnes() {
        A2aTaskStore store = new A2aTaskStore(Duration.ofMinutes(10), 100);
        A2aTask finished = task("t", "s");
        finished.markWorking();
        finished.markCompleted();
        A2aTask stuck = task("t", "s");
        stuck.markWorking();
        store.put(finished);
        store.put(stuck);

        store.sweep(Instant.now().plus(Duration.ofMinutes(11)));

        assertEquals(0, store.size());
        assertTrue(finished.isTerminal());
        assertEquals(A2aTaskState.CANCELED, stuck.snapshot().state(), "an orphaned turn is cut, not just forgotten");
    }

    @Test
    void sweepKeepsFreshTasks() {
        A2aTaskStore store = new A2aTaskStore(Duration.ofMinutes(10), 100);
        A2aTask fresh = task("t", "s");
        store.put(fresh);

        store.sweep(Instant.now());

        assertEquals(1, store.size());
        assertFalse(fresh.isTerminal());
    }

    @Test
    void listForTenantCrossesSubjectsButNeverTenants() {
        A2aTaskStore store = new A2aTaskStore(Duration.ofHours(1), 100);
        A2aTask alice = task("tenant_a", "alice");
        A2aTask bob = task("tenant_a", "bob");
        A2aTask otherTenant = task("tenant_b", "alice");
        A2aTask outbound = task("tenant_a", "carol");
        store.put(alice);
        store.put(bob);
        store.put(otherTenant);
        store.put(outbound);

        assertEquals(3, store.listForTenant("tenant_a").size());
        assertEquals(1, store.listForTenant("tenant_b").size());
        assertTrue(store.listForTenant("tenant_c").isEmpty());
    }

    @Test
    void findForTenantIgnoresTheSubjectButStillScopesTheTenant() {
        A2aTaskStore store = new A2aTaskStore(Duration.ofHours(1), 100);
        A2aTask task = task("tenant_a", "alice");
        store.put(task);

        // Any subject in the right tenant sees the record (console manager role);
        // a manager in another tenant gets the same answer as a made-up id.
        assertTrue(store.findForTenant(task.id(), "tenant_a").isPresent());
        assertTrue(store.findForTenant(task.id(), "tenant_b").isEmpty());
        assertTrue(store.findForTenant(null, "tenant_a").isEmpty());
        assertTrue(store.findForTenant("nope", "tenant_a").isEmpty());
    }

    @Test
    void overCapacityEvictsOldestTerminalTasksButNeverInFlightOnes() {
        A2aTaskStore store = new A2aTaskStore(Duration.ofHours(1), 2);
        A2aTask done1 = task("t", "s");
        done1.markWorking();
        done1.markCompleted();
        A2aTask inFlight = task("t", "s");
        inFlight.markWorking();
        A2aTask done2 = task("t", "s");
        done2.markWorking();
        done2.markCompleted();
        store.put(done1);
        store.put(inFlight);
        store.put(done2);

        store.sweep(Instant.now());

        assertEquals(2, store.size());
        assertTrue(store.find(inFlight.id(), "t", "s").isPresent(), "in-flight is never evicted for capacity");
    }
}
