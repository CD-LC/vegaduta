package com.agenticai.core.common.quicktask;

import com.agenticai.core.tenancy.OffsetLimitRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists {@link QuickTaskRun} rows around the real work {@link
 * QuickTaskService#execute} does - "fail open" on every write, same posture
 * as {@code LlmCallLogService}: a logging/persistence hiccup must never break
 * the actual task the person is waiting on. Worst case if a write fails, that
 * one task simply isn't retrievable afterward by id - the task itself still
 * ran and still delivered, exactly as it did before this feature existed.
 */
@Service
public class QuickTaskRunService {

    private static final Logger log = LoggerFactory.getLogger(QuickTaskRunService.class);

    private final QuickTaskRunRepository repository;

    public QuickTaskRunService(QuickTaskRunRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void start(String taskId, UUID agentId, String keycloakUserId, QuickTaskChannel channel, String requestMessage) {
        start(taskId, agentId, keycloakUserId, channel, requestMessage, null);
    }

    /**
     * C1 overload carrying the resolved {@code agent_version.id} pin - null
     * when the agent has no PRODUCTION/STAGING label or resolution failed
     * open (which must never break the task itself).
     */
    @Transactional
    public void start(
        String taskId, UUID agentId, String keycloakUserId, QuickTaskChannel channel, String requestMessage,
        UUID agentVersionId
    ) {
        try {
            QuickTaskRun run = new QuickTaskRun(taskId, agentId, keycloakUserId, channel, requestMessage, Instant.now());
            run.setAgentVersionId(agentVersionId);
            repository.save(run);
        } catch (Exception e) {
            log.warn("Failed to persist QuickTaskRun start for task {} (task itself continues) - {}", taskId, e.getMessage());
        }
    }

    @Transactional
    public void complete(String taskId, String resultText, String deliveryNote) {
        try {
            repository.findById(taskId).ifPresent(run -> {
                run.complete(resultText, deliveryNote, Instant.now());
                repository.save(run);
            });
        } catch (Exception e) {
            log.warn("Failed to persist QuickTaskRun completion for task {} - {}", taskId, e.getMessage());
        }
    }

    @Transactional
    public void fail(String taskId, String errorMessage) {
        try {
            repository.findById(taskId).ifPresent(run -> {
                run.fail(errorMessage, Instant.now());
                repository.save(run);
            });
        } catch (Exception e) {
            log.warn("Failed to persist QuickTaskRun failure for task {} - {}", taskId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Optional<QuickTaskRun> findForOwner(String taskId, String keycloakUserId) {
        return repository.findByTaskIdAndRequestedByKeycloakUserId(taskId, keycloakUserId);
    }

    @Transactional(readOnly = true)
    public List<QuickTaskRun> recentForOwner(String keycloakUserId) {
        return repository.findTop50ByRequestedByKeycloakUserIdOrderByStartedAtDesc(keycloakUserId);
    }

    /** One page of the owner's runs, newest first (taskId as tiebreaker so offset pages are stable). */
    @Transactional(readOnly = true)
    public Page<QuickTaskRun> recentForOwner(String keycloakUserId, long offset, int limit) {
        return repository.findByRequestedByKeycloakUserIdOrderByStartedAtDescTaskIdDesc(
                keycloakUserId, new OffsetLimitRequest(offset, limit));
    }
}
