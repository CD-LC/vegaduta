package com.agenticai.core.agents.turn;

import com.agenticai.core.chat.StreamEndExecutor;
import com.agenticai.core.rl.api.RlTurnRecorder;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Opens/closes the RL episode recorded for a Playground turn, extracted verbatim from {@link
 * AgentChatService} (extract-only split).
 */
final class RlEpisodeRecorder {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final ObjectProvider<RlTurnRecorder> rlTurnRecorder;

    RlEpisodeRecorder(ObjectProvider<RlTurnRecorder> rlTurnRecorder) {
        this.rlTurnRecorder = rlTurnRecorder;
    }

    /**
     * Opens an RL episode for this turn, or returns null when there is nothing to record against
     * (no client turnRef, i.e. not a Playground turn) or the RL module isn't on the classpath.
     * Best-effort: an RL failure must never break a chat turn.
     */
    UUID beginRlEpisode(
            String turnRef,
            UUID agentId,
            String sessionId,
            String userMessage,
            Map<PolicyDimension, UUID> selectedArms) {
        if (turnRef == null) {
            return null;
        }
        RlTurnRecorder recorder = rlTurnRecorder.getIfAvailable();
        if (recorder == null) {
            return null;
        }
        try {
            UUID episodeId = recorder.beginTurn(agentId, sessionId, turnRef, null, selectedArms);
            // Record the user prompt as the episode's first step - the "prompt"
            // half of every (prompt, completion) preference sample the export
            // pipeline reconstructs.
            recorder.recordStep(episodeId, "user", "message", userMessage, null, null, null);
            return episodeId;
        } catch (RuntimeException e) {
            log.debug(
                    "RL beginTurn failed for agent {} turnRef {} - continuing without an RL"
                            + " episode",
                    agentId,
                    turnRef,
                    e);
            return null;
        }
    }

    /**
     * Records the final assistant answer as a trajectory step, then marks the episode ended.
     * Re-applies {@code capturedTenant} because this runs from {@code doFinally} on the
     * stream-completion thread, which for the FAST streaming path is not the request thread that
     * opened the episode - the same capture-then-reapply the schema-per-tenant write needs
     * everywhere (see LlmMetricsAdvisor). Best-effort: never affects the turn.
     */
    void completeRlEpisode(UUID episodeId, String answer, Optional<TenantContext> capturedTenant) {
        RlTurnRecorder recorder = rlTurnRecorder.getIfAvailable();
        if (recorder == null) {
            return;
        }
        // Inline unless app.chat.stream-end.async is on; the tenant travels as data, so either
        // thread works.
        streamEnd.run(
                () -> {
                    try {
                        TenantScope.of(capturedTenant)
                                .run(
                                        () -> {
                                            if (answer != null && !answer.isEmpty()) {
                                                recorder.recordStep(
                                                        episodeId,
                                                        "assistant",
                                                        "answer",
                                                        answer,
                                                        null,
                                                        null,
                                                        null);
                                            }
                                            recorder.endTurn(episodeId, true);
                                        });
                    } catch (RuntimeException e) {
                        log.debug(
                                "RL completeRlEpisode failed for episode {} - ignoring",
                                episodeId,
                                e);
                    }
                });
    }

    /** Set by {@link StreamEndWiring}; inline until then (hand-built fixtures, flag off). */
    private volatile StreamEndExecutor streamEnd = StreamEndExecutor.INLINE;

    void setStreamEnd(StreamEndExecutor streamEnd) {
        this.streamEnd = streamEnd == null ? StreamEndExecutor.INLINE : streamEnd;
    }
}
