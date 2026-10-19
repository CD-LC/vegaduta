package com.agenticai.core.brief;

import java.time.Instant;
import java.util.List;

/**
 * What a rehearsal needs from the platform's simulation feature, as one narrow port ({@link ServiceRehearsalPort}
 * implements it over {@code SimulationPersonaService} / {@code SimulationRunService}). The simulation runs every
 * turn of the target agent with tools OFF - nothing a persona provokes is sent, written or charged - so a rehearsal
 * is offer-only by construction; the port has no way to switch tools on.
 */
public interface RehearsalPort {

    /** Creates the custom persona if a persona with this key does not exist yet (idempotent). */
    void ensurePersona(String key, String name, String description, String objective);

    /** Starts one simulation session (one run per persona key) against the agent; returns the session id. */
    String createSession(String agentId, List<String> personaKeys, int maxTurns, String createdBy);

    /** The agent's simulation sessions, newest first. */
    List<SessionInfo> sessions(String agentId);

    /** The runs of one session. */
    List<RunInfo> runs(String agentId, String sessionId);

    record SessionInfo(String id, String createdBy, String status, Instant createdAt) {
    }

    record RunInfo(String id, String personaName, String status, String verdict, String verdictReason, int turns) {
    }
}
