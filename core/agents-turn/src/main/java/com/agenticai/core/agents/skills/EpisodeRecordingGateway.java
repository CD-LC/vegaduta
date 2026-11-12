package com.agenticai.core.agents.skills;

import com.agenticai.core.agents.memory.TurnTaintSnapshot;
import com.agenticai.core.agents.skills.SkillLearningGateway.TurnOutcome;
import com.agenticai.core.guardrails.taint.TurnTaint;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * The chat turn's hook into the episodic recorder (ADR-0028 WP4b), the same shape as {@link
 * LessonLearningGateway}. With no observer, or one that reports itself disabled ({@code
 * app.memory.episodic.enabled=false}, the default), {@link #observe} returns the very same {@link
 * Flux}: not one operator is added to the turn. Unlike the learning loops it keeps no reply text at
 * all, since an episode never contains what was said.
 */
public final class EpisodeRecordingGateway {

    private static final Logger log = LoggerFactory.getLogger(EpisodeRecordingGateway.class);

    /**
     * Receives every observed turn on the thread that finished it; must be quick and never throw.
     */
    public interface Observer {
        boolean enabled();

        void onTurn(TurnOutcome outcome);
    }

    private static volatile Observer observer;

    private EpisodeRecordingGateway() {}

    public static void install(Observer installed) {
        observer = installed;
    }

    /**
     * True once an observer is installed, whether or not it reports itself enabled (the startup
     * wiring check).
     */
    public static boolean installed() {
        return observer != null;
    }

    public static Flux<String> observe(
            Flux<String> turn,
            UUID agentId,
            String sessionId,
            Instant startedAt,
            Optional<TenantContext> tenant) {
        return observe(turn, agentId, sessionId, startedAt, tenant, null);
    }

    /**
     * As above, and when the turn ends the outcome carries a {@link TurnTaintSnapshot} of {@code
     * taint}, read at that moment so marks made during the turn count. A null record yields the
     * unreadable (fail-closed) snapshot.
     */
    public static Flux<String> observe(
            Flux<String> turn,
            UUID agentId,
            String sessionId,
            Instant startedAt,
            Optional<TenantContext> tenant,
            TurnTaint taint) {
        Observer o = observer;
        if (o == null || !o.enabled()) {
            return turn;
        }
        TenantScope scope = TenantScope.of(tenant);
        return turn.doFinally(
                signal -> {
                    try {
                        o.onTurn(
                                new TurnOutcome(
                                        agentId,
                                        sessionId,
                                        null,
                                        null,
                                        startedAt,
                                        signal == SignalType.ON_COMPLETE,
                                        signal == SignalType.CANCEL,
                                        scope,
                                        TurnTaintSnapshot.of(taint)));
                    } catch (RuntimeException e) {
                        log.debug("Episode observer failed: {}", e.getMessage());
                    }
                });
    }
}
