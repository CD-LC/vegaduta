package com.agenticai.core.quality.simulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request/response records for the simulation REST surface ({@link
 * SimulationController}) - same one-file grouping {@code EvalGateDtos}
 * documents for the eval-gate package.
 */
public final class SimulationDtos {

    private SimulationDtos() {
        // Namespace holder - never instantiated.
    }

    /**
     * {@code attack} marks a built-in indirect-injection scenario (key prefix {@code attack_}):
     * turn 1 is a scripted poisoned payload and the verdict has a deterministic canary check.
     */
    public record PersonaResponse(
        String key, String name, String description, String objective, boolean builtIn, boolean attack
    ) {
        /** Pre-attack shape, kept for existing callers: never an attack scenario. */
        public PersonaResponse(String key, String name, String description, String objective, boolean builtIn) {
            this(key, name, description, objective, builtIn, false);
        }

        public static PersonaResponse of(SimulationPersonaService.ResolvedPersona p) {
            boolean attack = p.builtIn()
                && com.agenticai.core.quality.simulation.attack.AttackScenarioCatalog.find(p.key()).isPresent();
            return new PersonaResponse(p.key(), p.name(), p.description(), p.objective(), p.builtIn(), attack);
        }
    }

    /** One scenario row of {@link AttackReportResponse}. {@code verdict} is null while the run is not finished. */
    public record AttackScenarioResult(
        String key,
        String name,
        SimulationVerdict verdict,
        boolean deterministic,
        String reason,
        UUID runId
    ) {
    }

    /**
     * GET .../sessions/{id}/attack-report. {@code attackSuccessRate} = succeededAttacks /
     * (succeededAttacks + resisted), null when that denominator is 0 (INCONCLUSIVE and unfinished
     * runs are excluded). Computed from the session's runs on every read; nothing is stored.
     */
    public record AttackReportResponse(
        UUID sessionId,
        int total,
        int succeededAttacks,
        int resisted,
        int inconclusive,
        int pending,
        Double attackSuccessRate,
        List<AttackScenarioResult> perScenario,
        List<String> limits
    ) {
    }

    public record CreatePersonaRequest(String key, String name, String description, String objective) {
    }

    /**
     * Session trigger. {@code maxTurns} defaults to 6 (bounded 1-50
     * server-side); {@code budgetUsd} (V344) is the PER-RUN cost cap,
     * default $10.
     */
    public record CreateSessionRequest(List<String> personaKeys, Integer maxTurns, BigDecimal budgetUsd) {
    }

    public record SessionResponse(
        UUID id,
        UUID agentId,
        List<String> personaKeys,
        int maxTurns,
        SimulationSessionStatus status,
        BigDecimal budgetUsd,
        boolean cancelRequested,
        String createdBy,
        Instant createdAt,
        Instant completedAt
    ) {
        public static SessionResponse of(SimulationSession s) {
            return new SessionResponse(
                s.getId(), s.getAgentId(), s.getPersonaKeys(), s.getMaxTurns(), s.getStatus(),
                s.getBudgetUsd(), s.isCancelRequested(),
                s.getCreatedBy(), s.getCreatedAt(), s.getCompletedAt());
        }
    }

    /** Summary shape (no transcript) for a session's run list. */
    public record RunSummaryResponse(
        UUID id,
        UUID sessionId,
        String personaKey,
        String personaName,
        SimulationRunStatus status,
        SimulationTermination termination,
        SimulationVerdict verdict,
        String verdictReason,
        int turnCount,
        BigDecimal costUsd,
        UUID agentVersionId,
        String errorMessage,
        Instant startedAt,
        Instant completedAt
    ) {
        public static RunSummaryResponse of(SimulationRun r) {
            return new RunSummaryResponse(
                r.getId(), r.getSessionId(), r.getPersonaKey(), r.getPersonaName(), r.getStatus(),
                r.getTermination(), r.getVerdict(), r.getVerdictReason(), r.getTurnCount(),
                r.getCostUsd(), r.getAgentVersionId(), r.getErrorMessage(),
                r.getStartedAt(), r.getCompletedAt());
        }
    }

    /** Full detail (includes the transcript) for one run. */
    public record RunDetailResponse(
        UUID id,
        UUID sessionId,
        String personaKey,
        String personaName,
        SimulationRunStatus status,
        SimulationTermination termination,
        String transcriptJson,
        SimulationVerdict verdict,
        String verdictReason,
        int turnCount,
        BigDecimal costUsd,
        UUID agentVersionId,
        String errorMessage,
        Instant startedAt,
        Instant completedAt
    ) {
        public static RunDetailResponse of(SimulationRun r) {
            return new RunDetailResponse(
                r.getId(), r.getSessionId(), r.getPersonaKey(), r.getPersonaName(), r.getStatus(),
                r.getTermination(), r.getTranscriptJson(), r.getVerdict(), r.getVerdictReason(),
                r.getTurnCount(), r.getCostUsd(), r.getAgentVersionId(), r.getErrorMessage(),
                r.getStartedAt(), r.getCompletedAt());
        }
    }
}
