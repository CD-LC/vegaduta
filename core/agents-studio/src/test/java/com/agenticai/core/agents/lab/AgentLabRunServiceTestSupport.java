package com.agenticai.core.agents.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.lab.AgentLabDtos.LabCellRequest;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunDocument;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunRequest;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunResponse;
import com.agenticai.core.agents.lab.AgentLabJudge.ScoreVerdict;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import com.agenticai.core.agents.turn.AgentLabCompletionService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.TenantStatusGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared fixture for the {@link AgentLabRunService} tests: in-memory store, executors, mocks and request builders. Split from the former AgentLabRunServiceTest. */
abstract class AgentLabRunServiceTestSupport {

    static final String TENANT = "t1";

    static final String USER = "u1";

    static final long T0 = 1_790_000_000_000L;

    // ---- fakes --------------------------------------------------------------

    /** The store contract in two maps and a set, plus switches for the failures Redis can have. */
    static final class InMemoryStore implements AgentLabRunStore {

        final Map<String, LabRunDocument> runs = new HashMap<>();
        final Map<String, String> locks = new HashMap<>();
        final Set<String> cancelFlags = new HashSet<>();
        /** Liveness marks. No TTL here: a test that wants the JVM "gone" clears the set. */
        final Set<String> alive = new HashSet<>();
        final List<String> statusWrites = new ArrayList<>();
        boolean failSave;
        boolean failFind;
        boolean failMarkAlive;
        /** Runs once, between a reader's read and its compare-and-set: the worker's racing write. */
        Runnable beforeReplace;

        @Override
        public void save(String tenantId, LabRunDocument doc) {
            if (failSave) {
                throw new IllegalStateException("redis down");
            }
            runs.put(tenantId + ":" + doc.run().runId(), doc);
            statusWrites.add(doc.run().status());
        }

        @Override
        public Optional<LabRunDocument> find(String tenantId, String runId) {
            if (failFind) {
                throw new IllegalStateException("redis down");
            }
            return Optional.ofNullable(runs.get(tenantId + ":" + runId));
        }

        @Override
        public void delete(String tenantId, String runId) {
            runs.remove(tenantId + ":" + runId);
        }

        @Override
        public boolean acquireLock(String tenantId, String subject, String runId) {
            return locks.putIfAbsent(tenantId + ":" + subject, runId) == null;
        }

        @Override
        public void releaseLock(String tenantId, String subject, String runId) {
            locks.remove(tenantId + ":" + subject, runId);
        }

        @Override
        public Optional<String> lockHolder(String tenantId, String subject) {
            return Optional.ofNullable(locks.get(tenantId + ":" + subject));
        }

        @Override
        public void requestCancel(String tenantId, String runId) {
            cancelFlags.add(tenantId + ":" + runId);
        }

        @Override
        public boolean isCancelRequested(String tenantId, String runId) {
            return cancelFlags.contains(tenantId + ":" + runId);
        }

        @Override
        public boolean replaceIfUnchanged(String tenantId, LabRunDocument expected, LabRunDocument replacement) {
            if (failSave) {
                throw new IllegalStateException("redis down");
            }
            if (beforeReplace != null) {
                Runnable racing = beforeReplace;
                beforeReplace = null;
                racing.run();
            }
            LabRunDocument stored = runs.get(tenantId + ":" + expected.run().runId());
            if (stored == null
                || !stored.run().status().equals(expected.run().status())
                || stored.run().updatedAtMs() != expected.run().updatedAtMs()
                || stored.run().completedCalls() != expected.run().completedCalls()) {
                return false;
            }
            runs.put(tenantId + ":" + replacement.run().runId(), replacement);
            statusWrites.add(replacement.run().status());
            return true;
        }

        @Override
        public void markAlive(String tenantId, String runId) {
            if (failMarkAlive) {
                throw new IllegalStateException("redis down");
            }
            alive.add(tenantId + ":" + runId);
        }

        @Override
        public boolean isAlive(String tenantId, String runId) {
            return alive.contains(tenantId + ":" + runId);
        }

        LabRunDocument only() {
            assertEquals(1, runs.size(), "expected exactly one stored run");
            return runs.values().iterator().next();
        }
    }

    /** Runs the worker on the calling thread, inside {@code start}. */
    static final class InlineExecutor extends AgentLabExecutor {
        @Override
        public boolean trySubmit(Runnable runBody) {
            runBody.run();
            return true;
        }
    }

    /** Accepts the run and never runs it: a run that stays QUEUED because both workers are busy. */
    static class ParkingExecutor extends AgentLabExecutor {
        final List<Runnable> parked = new ArrayList<>();

        @Override
        public boolean trySubmit(Runnable runBody) {
            parked.add(runBody);
            return true;
        }

        @Override
        public boolean remove(Runnable runBody) {
            return parked.remove(runBody);
        }
    }

    static final class SaturatedExecutor extends AgentLabExecutor {
        @Override
        public boolean trySubmit(Runnable runBody) {
            return false;
        }
    }

    // ---- fixture ------------------------------------------------------------

    final AgentService agentService = mock(AgentService.class);

    final TenantLimits limits = mock(TenantLimits.class);

    final TenantStatusGuard statusGuard = mock(TenantStatusGuard.class);

    final AgentLabCompletionService completion = mock(AgentLabCompletionService.class);

    final AgentLabJudge judge = mock(AgentLabJudge.class);

    final InMemoryStore store = new InMemoryStore();

    final AtomicLong now = new AtomicLong(T0);

    final UUID agentId = UUID.randomUUID();

    final List<AgentLabExecutor> executors = new ArrayList<>();

    AgentLabRunService service;

    @BeforeEach
    void setUp() {
        when(limits.storedPlan(TENANT)).thenReturn(TenantPlan.PREMIUM);
        when(statusGuard.isUsable(TENANT)).thenReturn(true);
        // Null-safe: a test that re-stubs complete(any()) invokes this answer once with a null argument.
        when(completion.complete(any())).thenAnswer(call ->
            request(call) == null ? null : "answer to " + request(call).attributionSessionId());
        when(judge.score(any(), any(), any(), any(), any(), any()))
            .thenReturn(new ScoreVerdict(true, 0.75, "Fine."));
        when(judge.same(any(), any(), any(), any(), any(), any())).thenReturn(Boolean.TRUE);
        service = serviceWith(new InlineExecutor());
        bind();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        executors.forEach(AgentLabExecutor::shutdown);
    }

    AgentLabRunService serviceWith(AgentLabExecutor executor) {
        return serviceWith(executor, true);
    }

    AgentLabRunService serviceWith(AgentLabExecutor executor, boolean freePlanEnabled) {
        executors.add(executor);
        return new AgentLabRunService(
            agentService, limits, statusGuard, completion, judge, store, executor, now::get,
            freePlanEnabled);
    }

    /** The inline worker clears the thread's TenantContext when it ends, as it must; put the request's back. */
    static void bind() {
        TenantContext.set(TenantContext.of(TENANT, USER, Set.of("tenant_user")));
    }

    static LabCompletionRequest request(org.mockito.invocation.InvocationOnMock call) {
        return call.getArgument(0);
    }

    static LabCellRequest cell(String key, Integer promptIndex) {
        return new LabCellRequest(key, promptIndex, "What is the refund window?", null, null);
    }

    static List<LabCellRequest> cells(int count) {
        List<LabCellRequest> cells = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            cells.add(cell("k" + i, null));
        }
        return cells;
    }

    static LabRunRequest consistency(int count, String similarity) {
        return new LabRunRequest("CONSISTENCY", List.of(), cells(count), "NONE", similarity);
    }

    static LabRunRequest knockout(List<String> prompts, List<LabCellRequest> cells) {
        return new LabRunRequest("KNOCKOUT", prompts, cells, "JUDGE", "NONE");
    }

    LabRunResponse startAndRead(LabRunRequest request) {
        LabRunResponse started = service.start(agentId, request, USER);
        bind();
        return service.get(agentId, started.runId(), USER);
    }

    static ResponseStatusException assertStatus(HttpStatus expected, Runnable call) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(expected.value(), e.getStatusCode().value());
        return e;
    }

    void assertRejected(LabRunRequest request, String messagePart) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.start(agentId, request, USER));
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
        assertTrue(store.runs.isEmpty());
        assertTrue(store.locks.isEmpty());
        verifyNoInteractions(completion);
    }
}
