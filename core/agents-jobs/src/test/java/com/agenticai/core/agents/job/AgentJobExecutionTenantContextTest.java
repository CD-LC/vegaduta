package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * The regression the contract calls the highest-probability failure in this whole workstream.
 *
 * <p>{@link TenantContext} is a plain {@code ThreadLocal} and the job pool is made of POOLED,
 * REUSED platform threads. That combination has exactly two failure modes, and both are tested
 * here:
 *
 * <ol>
 *   <li>the context is not established before the first repository read (the 2026-08-10
 *       document-generation incident: every job sat in its initial state for three days because the
 *       read hit the empty {@code public} schema and "successfully" found nothing to do);
 *   <li>the context is not restored afterwards, so tenant A's identity serves tenant B's job on the
 *       very next task the same thread picks up.
 * </ol>
 *
 * <p><b>The single-thread executor is load-bearing.</b> On a multi-threaded pool the second job
 * would very likely land on a fresh thread and the test would pass while proving nothing. Forcing
 * both jobs onto one thread is what makes leakage observable at all.
 */
class AgentJobExecutionTenantContextTest {

    private final ExecutorService oneThread = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        oneThread.shutdownNow();
        TenantContext.clear();
    }

    @Test
    void aPooledThreadNeverCarriesOneTenantsContextIntoTheNextJob() throws Exception {
        AtomicReference<String> tenantSeenByAlpha = new AtomicReference<>();
        AtomicReference<String> subjectSeenByAlpha = new AtomicReference<>();
        AtomicReference<String> tenantSeenByBeta = new AtomicReference<>();
        AtomicReference<String> subjectSeenByBeta = new AtomicReference<>();

        AgentJob alpha = AgentJobTestSupport.queuedJob("t_alpha", "alice@example.com", 60, 15);
        AgentJob beta = AgentJobTestSupport.queuedJob("t_beta", "bob@example.com", 60, 15);

        AgentJobExecution alphaExecution =
                executionObserving(alpha, tenantSeenByAlpha, subjectSeenByAlpha);
        AgentJobExecution betaExecution =
                executionObserving(beta, tenantSeenByBeta, subjectSeenByBeta);

        oneThread
                .submit(() -> alphaExecution.run(alpha.getId(), "t_alpha"))
                .get(20, TimeUnit.SECONDS);
        oneThread.submit(() -> betaExecution.run(beta.getId(), "t_beta")).get(20, TimeUnit.SECONDS);

        assertEquals("t_alpha", tenantSeenByAlpha.get());
        assertEquals(
                "t_beta",
                tenantSeenByBeta.get(),
                "the second job on the same thread saw the first job's tenant");

        // The job runs with the REQUESTER'S frozen authority - never the
        // bootstrap identity used for the load/claim reads, and never elevated
        // system authority.
        assertEquals("alice@example.com", subjectSeenByAlpha.get());
        assertEquals("bob@example.com", subjectSeenByBeta.get());

        boolean stillSet =
                oneThread
                        .submit(() -> TenantContext.current().isPresent())
                        .get(10, TimeUnit.SECONDS);
        assertFalse(
                stillSet,
                "the worker thread was returned to the pool still carrying a TenantContext");
    }

    @Test
    void aPreExistingContextOnTheCallingThreadIsRestoredNotCleared() throws Exception {
        // The in-memory dispatch path runs on a thread that may already have a
        // context of its own; save-and-restore rather than set-and-clear is what
        // keeps that thread usable afterwards.
        AgentJob job = AgentJobTestSupport.queuedJob("t_alpha", "alice@example.com", 60, 15);
        AgentJobExecution execution =
                executionObserving(job, new AtomicReference<>(), new AtomicReference<>());

        TenantContext caller =
                TenantContext.of("t_caller", "caller@example.com", Set.of("ROLE_user"));
        TenantContext.set(caller);

        execution.run(job.getId(), "t_alpha");

        Optional<TenantContext> restored = TenantContext.current();
        assertTrue(restored.isPresent());
        assertEquals("t_caller", restored.get().getTenantId());
        assertEquals("caller@example.com", restored.get().getSubject());
    }

    /**
     * Builds an execution whose chat call records the {@link TenantContext} the agent turn actually
     * observes - i.e. the context as it stands after the claim, which is the only one that matters.
     */
    private AgentJobExecution executionObserving(
            AgentJob job, AtomicReference<String> tenantSeen, AtomicReference<String> subjectSeen) {
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        ToolCallLogRepository toolCallLogRepository = mock(ToolCallLogRepository.class);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(
                        invocation -> {
                            TenantContext observed = TenantContext.current().orElse(null);
                            tenantSeen.set(observed == null ? null : observed.getTenantId());
                            subjectSeen.set(observed == null ? null : observed.getSubject());
                            return Flux.just("done");
                        });
        return new AgentJobExecution(
                repository,
                chatService,
                toolCallLogRepository,
                AgentJobTestSupport.properties(),
                new SimpleMeterRegistry(),
                mock(AgentJobProgressService.class));
    }
}
