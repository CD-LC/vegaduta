package com.agenticai.core.workflow.version;

import com.agenticai.core.workflow.WorkflowRun;
import com.agenticai.core.workflow.WorkflowRunNotFoundException;
import com.agenticai.core.workflow.WorkflowRunRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Run-to-run diff facade: loads both runs (each must belong to {@code workflowId}) and their
 * stamped version snapshots, then delegates to {@link WorkflowVersionService#diffRuns}. Keeps
 * {@link WorkflowVersionController} off {@link WorkflowRunRepository} and the run entity.
 */
@Service
public class WorkflowRunDiffService {

    private final WorkflowRunRepository runRepository;
    private final WorkflowVersionService versionService;

    public WorkflowRunDiffService(
            WorkflowRunRepository runRepository, WorkflowVersionService versionService) {
        this.runRepository = runRepository;
        this.versionService = versionService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> diffRuns(UUID workflowId, UUID runId, UUID otherRunId) {
        WorkflowRun a = load(workflowId, runId);
        WorkflowRun b = load(workflowId, otherRunId);
        WorkflowVersion versionA = resolveVersion(workflowId, a.getWorkflowVersion());
        WorkflowVersion versionB = resolveVersion(workflowId, b.getWorkflowVersion());
        return versionService.diffRuns(a, b, versionA, versionB);
    }

    private WorkflowRun load(UUID workflowId, UUID runId) {
        return runRepository
                .findById(runId)
                .filter(run -> workflowId.equals(run.getWorkflowId()))
                .orElseThrow(() -> new WorkflowRunNotFoundException(runId));
    }

    /** Stamped version -> its snapshot row, or null (unstamped run, or a snapshot deleted with an older workflow id). */
    private WorkflowVersion resolveVersion(UUID workflowId, Integer versionNumber) {
        if (versionNumber == null) {
            return null;
        }
        return versionService.getOptional(workflowId, versionNumber).orElse(null);
    }
}
