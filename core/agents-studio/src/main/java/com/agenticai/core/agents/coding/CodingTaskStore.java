package com.agenticai.core.agents.coding;

import com.agenticai.core.agents.coding.CodingTaskDtos.TaskDocument;
import java.util.List;
import java.util.Optional;

/**
 * Where a coding task's state lives: one document per task, its diff, a cancel flag, the human
 * decision, a per-user "one live task" lock, a short recent-tasks index and a liveness mark. An
 * interface so {@link CodingTaskService} is tested against an in-memory fake; the only real
 * implementation is {@link RedisCodingTaskStore}. Every key is tenant-first, so a task id can never
 * address another tenant's task.
 *
 * <p>No table: the schema is maintained outside this repository, and a coding task is a short-lived
 * job whose record matters for hours, not forever. Every method throws when the backing store is
 * unreachable; nothing here turns a failure into "not found".
 */
public interface CodingTaskStore {

    /** Writes the whole document and restarts its TTL. */
    void save(String tenantId, TaskDocument doc);

    Optional<TaskDocument> find(String tenantId, String taskId);

    void delete(String tenantId, String taskId);

    void saveDiff(String tenantId, String taskId, String diff);

    Optional<String> findDiff(String tenantId, String taskId);

    void deleteDiff(String tenantId, String taskId);

    boolean acquireLock(String tenantId, String subject, String taskId);

    /** Frees the lock only if it still belongs to {@code taskId}. */
    void releaseLock(String tenantId, String subject, String taskId);

    Optional<String> lockHolder(String tenantId, String subject);

    void requestCancel(String tenantId, String taskId);

    boolean isCancelRequested(String tenantId, String taskId);

    /** Records the human decision: {@code APPROVE:<diffHash>} or {@code REJECT}. Overwrites an earlier one. */
    void setDecision(String tenantId, String taskId, String decision);

    Optional<String> decision(String tenantId, String taskId);

    void clearDecision(String tenantId, String taskId);

    /** Says "the JVM that holds this task is up". Short TTL: it has to be repeated. */
    void markAlive(String tenantId, String taskId);

    boolean isAlive(String tenantId, String taskId);

    /** Adds the task to the user's recent list (newest first, capped). */
    void index(String tenantId, String subject, String taskId);

    List<String> recent(String tenantId, String subject, int limit);

    /**
     * A window of the user's recent list, newest first. The default derives it from {@link #recent(String,
     * String, int)}; the Redis store overrides it with a range read.
     */
    default List<String> recent(String tenantId, String subject, int offset, int limit) {
        List<String> all = recent(tenantId, subject, (int) Math.min((long) offset + limit, Integer.MAX_VALUE));
        return all.subList(Math.min(Math.max(offset, 0), all.size()), all.size());
    }

    /** How many tasks the user's recent list holds (bounded by the index cap). */
    default long recentCount(String tenantId, String subject) {
        return recent(tenantId, subject, Integer.MAX_VALUE).size();
    }
}
