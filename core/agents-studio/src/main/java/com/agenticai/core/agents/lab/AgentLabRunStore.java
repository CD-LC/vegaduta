package com.agenticai.core.agents.lab;

import com.agenticai.core.agents.lab.AgentLabDtos.LabRunDocument;
import java.util.Optional;

/**
 * Where a lab run's state lives while it runs and for an hour afterwards:
 * one document per run, a cancel flag, and a per-user "one live run" lock.
 *
 * <p>An interface so {@link AgentLabRunService} can be tested against an
 * in-memory fake; the only real implementation is {@link
 * RedisAgentLabRunStore}. Every key is tenant-first, so a run id can never
 * address another tenant's run.
 *
 * <p>Every method throws when the backing store is unreachable. The service
 * decides what that means for each caller (503 on the request thread, stop
 * on the worker); nothing here swallows a failure into "not found", because
 * a poll that read "not found" would make the client forget a run that is
 * still spending.
 */
public interface AgentLabRunStore {

    /** Writes the whole document and restarts its TTL. */
    void save(String tenantId, LabRunDocument doc);

    /** Empty when the run never existed, expired, or its stored form is unreadable. */
    Optional<LabRunDocument> find(String tenantId, String runId);

    void delete(String tenantId, String runId);

    /**
     * Takes the caller's one-live-run lock for {@code runId}.
     *
     * @return false when this user already holds the lock for some run
     */
    boolean acquireLock(String tenantId, String subject, String runId);

    /**
     * Frees the lock only if it still belongs to {@code runId}. A worker
     * that outlived its run (failed as stale, then replaced by a new run)
     * therefore cannot free the newer run's lock.
     */
    void releaseLock(String tenantId, String subject, String runId);

    /** The run id currently holding this user's lock, if any. */
    Optional<String> lockHolder(String tenantId, String subject);

    void requestCancel(String tenantId, String runId);

    /** Reads the flag without consuming it: the worker and the poll both look at it. */
    boolean isCancelRequested(String tenantId, String runId);

    /**
     * Writes {@code replacement} only if the stored document is still the one
     * a reader saw: same status, same {@code updatedAtMs}, same {@code
     * completedCalls} as {@code expected}. One atomic step in the real store.
     *
     * <p>This is how anything other than the worker may end a run (failing a
     * run whose worker is gone, cancelling a run that is still queued): a
     * blind {@link #save} from an earlier read could overwrite the worker's
     * newer progress - or its final SUCCEEDED document - with a stale copy.
     *
     * @return false when the document is gone or has changed since it was read; nothing is written
     */
    boolean replaceIfUnchanged(String tenantId, LabRunDocument expected, LabRunDocument replacement);

    /**
     * Says "the JVM that queued or is running this run is still up". Short
     * TTL, so it has to be repeated ({@code AgentLabRunService} does, on a
     * timer, for every run it holds); when that JVM dies the mark lapses on
     * its own.
     */
    void markAlive(String tenantId, String runId);

    /**
     * Whether {@link #markAlive} was called recently enough. This - not the
     * time since the last document write - is what tells a dead run from one
     * that is waiting in the queue or inside a long model call.
     */
    boolean isAlive(String tenantId, String runId);
}
