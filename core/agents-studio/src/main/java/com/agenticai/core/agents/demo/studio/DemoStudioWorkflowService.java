package com.agenticai.core.agents.demo.studio;

import com.agenticai.core.agents.demo.studio.DemoStudioService.StudioException;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

/**
 * Persistence facade for the /try Agent Studio's saved workflows and their runs. Owns the
 * workflow/run repositories so {@link DemoStudioController} never touches them (or the JPA
 * entities) directly; ownership checks (a sandbox only ever sees its own rows) live here too.
 */
@Service
public class DemoStudioWorkflowService {

    public record WorkflowView(
            UUID id,
            String name,
            DemoStudioWorkflowRunner.Definition definition,
            boolean mine,
            Instant createdAt,
            Instant updatedAt) {}

    public record RunView(
            UUID id,
            String status,
            String input,
            String output,
            Object trace,
            Instant startedAt,
            Instant finishedAt) {}

    private final DemoStudioWorkflowRepository workflowRepository;
    private final DemoStudioRunRepository runRepository;
    private final DemoStudioWorkflowRunner runner;
    private final DemoStudioStreams streams;
    private final int maxWorkflowsPerSession;

    public DemoStudioWorkflowService(
            DemoStudioWorkflowRepository workflowRepository,
            DemoStudioRunRepository runRepository,
            DemoStudioWorkflowRunner runner,
            ObjectMapper objectMapper,
            DemoStudioFairUse fairUse,
            @Value("${app.demo.studio.max-workflows-per-session:10}") int maxWorkflowsPerSession) {
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
        this.runner = runner;
        this.streams = new DemoStudioStreams(objectMapper, fairUse);
        this.maxWorkflowsPerSession = maxWorkflowsPerSession;
    }

    public int maxWorkflowsPerSession() {
        return maxWorkflowsPerSession;
    }

    @Transactional(readOnly = true)
    public List<WorkflowView> list(DemoSession session) {
        // Own workflows only - never another sandbox's, even from the same network address.
        return workflowRepository
                .findBySessionIdInOrderByCreatedAtAsc(List.of(session.getId()))
                .stream()
                .map(w -> toView(w, session))
                .toList();
    }

    public WorkflowView create(
            DemoSession session, String name, DemoStudioWorkflowRunner.Definition definition) {
        if (workflowRepository.countBySessionId(session.getId()) >= maxWorkflowsPerSession) {
            throw new StudioException(
                    "workflow_cap",
                    429,
                    "This sandbox can hold "
                            + maxWorkflowsPerSession
                            + " workflows. Delete one, or create a free account for more.");
        }
        DemoStudioWorkflowRunner.Definition def = validated(session, definition);
        runner.moderate(session, name, def);
        DemoStudioWorkflow saved =
                workflowRepository.save(
                        new DemoStudioWorkflow(
                                session.getId(),
                                session.getCreatedIp(),
                                name.strip(),
                                runner.serialize(def),
                                Instant.now()));
        return toView(saved, session);
    }

    public WorkflowView update(
            DemoSession session,
            UUID workflowId,
            String name,
            DemoStudioWorkflowRunner.Definition definition) {
        DemoStudioWorkflow workflow = requireWorkflow(session, workflowId);
        DemoStudioWorkflowRunner.Definition def = validated(session, definition);
        runner.moderate(session, name, def);
        workflow.update(name.strip(), runner.serialize(def), Instant.now());
        return toView(workflowRepository.save(workflow), session);
    }

    @Transactional
    public void delete(DemoSession session, UUID workflowId) {
        workflowRepository.delete(requireWorkflow(session, workflowId));
    }

    /** 404s (as a {@link StudioException}) unless the workflow belongs to {@code session}. */
    @Transactional(readOnly = true)
    public void requireOwned(DemoSession session, UUID workflowId) {
        requireWorkflow(session, workflowId);
    }

    @Transactional(readOnly = true)
    public List<RunView> listRuns(DemoSession session, UUID workflowId) {
        requireWorkflow(session, workflowId);
        return runRepository.findTop10ByWorkflowIdOrderByStartedAtDesc(workflowId).stream()
                .map(streams::toView)
                .toList();
    }

    /** Starts a run of one of {@code session}'s own workflows. */
    public Flux<String> run(DemoSession session, UUID workflowId, String input) {
        DemoStudioWorkflow workflow = requireWorkflow(session, workflowId);
        return runner.run(session, workflow, input);
    }

    /** 404s (as a {@link StudioException}) unless the run belongs to {@code session}. */
    @Transactional(readOnly = true)
    public void requireRunOwned(DemoSession session, UUID runId) {
        DemoStudioRun run =
                runRepository
                        .findById(runId)
                        .orElseThrow(
                                () ->
                                        new StudioException(
                                                "not_found",
                                                404,
                                                "That run isn't in your studio."));
        if (!session.getId().equals(run.getSessionId())) {
            throw new StudioException("not_found", 404, "That run isn't in your studio.");
        }
    }

    private DemoStudioWorkflowRunner.Definition validated(
            DemoSession session, DemoStudioWorkflowRunner.Definition definition) {
        return runner.validate(
                session,
                definition == null
                        ? new DemoStudioWorkflowRunner.Definition(List.of())
                        : definition);
    }

    private DemoStudioWorkflow requireWorkflow(DemoSession session, UUID workflowId) {
        DemoStudioWorkflow workflow =
                workflowRepository
                        .findById(workflowId)
                        .orElseThrow(
                                () ->
                                        new StudioException(
                                                "not_found",
                                                404,
                                                "That workflow isn't in your studio."));
        if (!session.getId().equals(workflow.getSessionId())) {
            throw new StudioException("not_found", 404, "That workflow isn't in your studio.");
        }
        return workflow;
    }

    private WorkflowView toView(DemoStudioWorkflow w, DemoSession session) {
        return new WorkflowView(
                w.getId(),
                w.getName(),
                runner.parse(w.getDefinitionJson()),
                w.getSessionId().equals(session.getId()),
                w.getCreatedAt(),
                w.getUpdatedAt());
    }
}
