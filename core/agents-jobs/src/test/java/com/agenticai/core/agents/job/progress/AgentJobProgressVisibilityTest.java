package com.agenticai.core.agents.job.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobExecution;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.job.dto.AgentJobProgressNoteView;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

/**
 * PR-7's first acceptance criterion, taken literally: <b>a long job's progress is visible WHILE IT
 * RUNS.</b>
 *
 * <p>"While it runs" is the entire assertion and the reason this test reads the progress trail from
 * inside the model turn rather than after it. A note that is only readable once the job has
 * finished answers nothing - by then the job's own result says more than any progress line could,
 * and the question progress reporting exists for ("is this thing alive or hung?") is no longer
 * being asked.
 *
 * <p>It also locks the wiring that was missing until this workstream: {@code report_progress} must
 * actually be in the turn's {@code extraTools}. That callback existed, the seam existed, and
 * nothing connected them - so {@code agent_job_progress} was guaranteed empty and a ten-minute job
 * stayed indistinguishable from a hung one.
 */
class AgentJobProgressVisibilityTest {

    private static final String NOTE = "Reconciling 340 invoices against the bank statement";

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aNoteWrittenMidTurnIsReadableWhileTheJobIsStillRunning() {
        AgentJob job = queuedJob();
        AgentJobRepository jobRepository = repositoryFor(job);
        AgentJobProgressService progressService = inMemoryProgressService(jobRepository);

        AtomicReference<AgentJobStatus> statusWhileRunning = new AtomicReference<>();
        AtomicReference<List<AgentJobProgressNoteView>> notesWhileRunning =
                new AtomicReference<>(List.of());
        AtomicReference<String> toolResult = new AtomicReference<>();

        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(
                        invocation -> {
                            List<ToolCallback> extraTools =
                                    invocation.<ChatTurnRequest>getArgument(1).extraTools();
                            ToolCallback reportProgress =
                                    extraTools.stream()
                                            .filter(
                                                    tool ->
                                                            "report_progress"
                                                                    .equals(
                                                                            tool.getToolDefinition()
                                                                                    .name()))
                                            .findFirst()
                                            .orElseThrow(
                                                    () ->
                                                            new AssertionError(
                                                                    "report_progress was not"
                                                                        + " offered to the turn -"
                                                                        + " the job runner is not"
                                                                        + " wiring it"));

                            toolResult.set(
                                    reportProgress.call(
                                            "{\"note\":\"" + NOTE + "\",\"percent\":40}"));
                            // Read the trail from INSIDE the turn. Captured rather than
                            // asserted here: an assertion error thrown from a mock answer
                            // would be swallowed by the runner's own error handling and
                            // reported as a failed job, which says nothing about progress.
                            statusWhileRunning.set(job.getStatus());
                            notesWhileRunning.set(progressService.notesFor(job.getId()));
                            return Flux.just("done");
                        });

        new AgentJobExecution(
                        jobRepository,
                        chatService,
                        unlimitedToolCalls(),
                        properties(),
                        new SimpleMeterRegistry(),
                        progressService)
                .run(job.getId(), "t_test");

        assertEquals(
                AgentJobStatus.RUNNING,
                statusWhileRunning.get(),
                "the note must have been readable while the job was still live, not only after it"
                        + " finished");
        assertEquals(1, notesWhileRunning.get().size());
        assertEquals(NOTE, notesWhileRunning.get().get(0).note());
        assertEquals(Integer.valueOf(40), notesWhileRunning.get().get(0).percent());
        assertEquals(ProgressSource.AGENT, notesWhileRunning.get().get(0).source());
        assertNotNull(toolResult.get());
        assertTrue(
                toolResult.get().contains("\"recorded\":true"),
                "the model must be told truthfully whether its note landed");

        // The denormalized copy on agent_job is what makes "what are these fifty
        // jobs doing right now" one query instead of fifty.
        verify(jobRepository).updateProgressNote(eq(job.getId()), eq(NOTE), any(Instant.class));
        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
    }

    /**
     * {@code extraTools} are passed RAW - they skip the {@code guardAndLog} wrapping the resolved
     * tool list gets - and they execute later on a Reactor thread that does not inherit a plain
     * {@code ThreadLocal}. Unwrapped, every progress write would land in the empty {@code public}
     * schema while the job's own trace stayed permanently empty, reporting no error at all.
     */
    @Test
    void theProgressToolIsHandedOverWithTheTenantContextAlreadyCaptured() {
        AgentJob job = queuedJob();
        AgentJobRepository jobRepository = repositoryFor(job);
        AtomicReference<String> tenantSeenByTheTool = new AtomicReference<>();

        AgentJobProgressService progressService = mock(AgentJobProgressService.class);
        when(progressService.tryRecord(any(), anyString(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            tenantSeenByTheTool.set(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse(null));
                            return true;
                        });

        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenAnswer(
                        invocation -> {
                            List<ToolCallback> extraTools =
                                    invocation.<ChatTurnRequest>getArgument(1).extraTools();
                            // Cleared first, standing in for the Reactor scheduler thread
                            // that never had the context in the first place. The wrapper is
                            // what has to put it back.
                            TenantContext.clear();
                            extraTools.get(0).call("{\"note\":\"" + NOTE + "\"}");
                            return Flux.just("done");
                        });

        new AgentJobExecution(
                        jobRepository,
                        chatService,
                        unlimitedToolCalls(),
                        properties(),
                        new SimpleMeterRegistry(),
                        progressService)
                .run(job.getId(), "t_test");

        assertEquals(
                "t_test",
                tenantSeenByTheTool.get(),
                "the progress write ran with no tenant context - it would have gone to the public"
                        + " schema");
    }

    // --------------------------------------------------------------- internals

    private static AgentJob queuedJob() {
        return AgentJob.queued(
                UUID.randomUUID(),
                "t_test",
                UUID.randomUUID(),
                "Reconcile yesterday's invoices",
                "alice@example.com",
                "alice@example.com",
                Set.of("ROLE_user"),
                AgentJobOrigin.API,
                null,
                60,
                1,
                15,
                null);
    }

    /**
     * Mirrors {@code claim} onto the in-memory entity, exactly as the real conditional UPDATE does
     * to the row - without it the entity stays QUEUED and the terminal transition throws for
     * reasons unrelated to progress.
     */
    private static AgentJobRepository repositoryFor(AgentJob job) {
        AgentJobRepository repository = mock(AgentJobRepository.class);
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        when(repository.claim(eq(job.getId()), anyString(), any(Instant.class), any(Instant.class)))
                .thenAnswer(
                        invocation -> {
                            job.markRunning(invocation.getArgument(1), invocation.getArgument(2));
                            return 1;
                        });
        when(repository.save(any(AgentJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.isCancelRequested(job.getId())).thenReturn(Optional.of(false));
        return repository;
    }

    /** The real service over an in-memory note table, so the read path is genuinely exercised. */
    private static AgentJobProgressService inMemoryProgressService(
            AgentJobRepository jobRepository) {
        List<AgentJobProgressNote> stored = new ArrayList<>();
        AgentJobProgressNoteRepository noteRepository = mock(AgentJobProgressNoteRepository.class);
        when(noteRepository.save(any(AgentJobProgressNote.class)))
                .thenAnswer(
                        invocation -> {
                            stored.add(invocation.getArgument(0));
                            return invocation.getArgument(0);
                        });
        when(noteRepository.countByJobId(any(UUID.class)))
                .thenAnswer(invocation -> (long) stored.size());
        when(noteRepository.findFirstByJobIdOrderByCreatedAtDescIdDesc(any(UUID.class)))
                .thenAnswer(
                        invocation ->
                                stored.isEmpty()
                                        ? Optional.empty()
                                        : Optional.of(stored.get(stored.size() - 1)));
        when(noteRepository.findByJobIdOrderByCreatedAtAscIdAsc(any(UUID.class)))
                .thenReturn(stored);
        return new AgentJobProgressService(noteRepository, jobRepository, properties());
    }

    private static ToolCallLogRepository unlimitedToolCalls() {
        ToolCallLogRepository toolCallLogRepository = mock(ToolCallLogRepository.class);
        when(toolCallLogRepository.countByAgentIdAndSessionId(any(), any())).thenReturn(0L);
        return toolCallLogRepository;
    }

    private static AgentJobProperties properties() {
        AgentJobProperties properties = new AgentJobProperties();
        properties.setMaxTurns(1);
        return properties;
    }

    /** Guards the assumption the whole trace surface is built on. */
    @Test
    void aJobsSessionIdIsDerivedFromItsOwnId() {
        AgentJob job = queuedJob();
        assertEquals(AgentJobSession.sessionIdFor(job.getId()), job.getSessionId());
        assertTrue(job.getSessionId().startsWith(AgentJobSession.PREFIX));
    }
}
