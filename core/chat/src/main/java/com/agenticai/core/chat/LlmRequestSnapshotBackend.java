package com.agenticai.core.chat;

import java.util.List;
import java.util.Optional;

/**
 * Where {@link LlmRequestSnapshotStore} keeps the copies that every replica must be able to read. Every
 * method may throw (the store treats a failure as "this backend is unavailable right now" and carries on
 * with its in-memory copy); implementations keep the same time-to-live and per-session depth the store was
 * configured with.
 */
interface LlmRequestSnapshotBackend {

    /** Inserts or replaces (by {@link LlmRequestSnapshot#id()}) one copy of a session of a tenant. */
    void save(String tenantId, LlmRequestSnapshot snapshot);

    /** One copy by id, if it is still kept. */
    Optional<LlmRequestSnapshot> find(String tenantId, String sessionId, String snapshotId);

    /** Every copy still kept for the session, in no particular order. */
    List<LlmRequestSnapshot> load(String tenantId, String sessionId);
}
