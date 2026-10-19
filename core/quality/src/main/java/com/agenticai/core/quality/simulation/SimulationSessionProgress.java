package com.agenticai.core.quality.simulation;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The session side of a simulation run's worker: reads the session's cancel flag between turns, marks the session
 * RUNNING when its first run starts, and flips it to COMPLETED (or CANCELLED, when a cancel drained it) once every
 * run is terminal. All best-effort - the same posture as turn_feedback/turn-log writes: a failure here is logged,
 * never thrown into the run. Split out of {@link SimulationRunService}.
 */
final class SimulationSessionProgress {

    private static final Logger log = LoggerFactory.getLogger(SimulationSessionProgress.class);

    private final SimulationSessionRepository sessionRepository;
    private final SimulationRunRepository runRepository;

    SimulationSessionProgress(SimulationSessionRepository sessionRepository, SimulationRunRepository runRepository) {
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
    }

    boolean isCancelRequested(UUID sessionId) {
        try {
            return sessionRepository.findById(sessionId)
                .map(SimulationSession::isCancelRequested).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    void markRunningBestEffort(UUID sessionId) {
        try {
            sessionRepository.findById(sessionId).ifPresent(s -> {
                s.markRunning();
                sessionRepository.save(s);
            });
        } catch (Exception e) {
            log.debug("Could not mark simulation session {} RUNNING (non-fatal) - {}", sessionId, e.getMessage());
        }
    }

    /** Flips the session to COMPLETED once every one of its runs has reached a terminal state. */
    void completeIfDoneBestEffort(UUID sessionId) {
        try {
            boolean anyLive = runRepository.existsBySessionIdAndStatusIn(
                sessionId, List.of(SimulationRunStatus.QUEUED, SimulationRunStatus.RUNNING));
            if (anyLive) {
                return;
            }
            sessionRepository.findById(sessionId).ifPresent(s -> {
                if (s.getStatus() == SimulationSessionStatus.COMPLETED
                    || s.getStatus() == SimulationSessionStatus.CANCELLED) {
                    return;
                }
                // A session that drained BECAUSE a cancel was requested is
                // CANCELLED, not COMPLETED - the terminal state records that
                // it was stopped, not that it finished naturally (V344).
                if (s.isCancelRequested()) {
                    s.markCancelled();
                } else {
                    s.markCompleted();
                }
                sessionRepository.save(s);
            });
        } catch (Exception e) {
            log.debug("Could not check/complete simulation session {} (non-fatal) - {}", sessionId, e.getMessage());
        }
    }
}
