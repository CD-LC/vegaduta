package com.agenticai.core.agents.skills;

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
 * The one hook the chat turn has into the skill-learning loop. The turn assembles its reply stream
 * in plain objects that are not beans, so it reaches the loop through this holder, installed once
 * at startup by the learning service (same shape as {@code TurnJournalGateway}).
 *
 * <p>With no observer installed, or one that reports itself disabled (the default,
 * {@code app.skills.learning.enabled=false}), {@link #observe} returns the very same {@link Flux}
 * instance it was given: not one operator is added to the turn.
 */
public final class SkillLearningGateway {

    private static final Logger log = LoggerFactory.getLogger(SkillLearningGateway.class);

    /** The reply text kept for the observer; a longer answer is cut, never buffered unbounded. */
    static final int MAX_ANSWER_CHARS = 24_000;

    /** How one observed turn ended. */
    public record TurnOutcome(
            UUID agentId,
            String sessionId,
            String userMessage,
            String answer,
            Instant startedAt,
            boolean completed,
            boolean cancelled,
            TenantScope scope) {}

    /** Receives every observed turn on the thread that finished it; must be quick and never throw. */
    public interface Observer {
        boolean enabled();

        void onTurn(TurnOutcome outcome);
    }

    private static volatile Observer observer;

    private SkillLearningGateway() {}

    public static void install(Observer installed) {
        observer = installed;
    }

    /** True once an observer is installed, whether or not it reports itself enabled (the startup wiring check). */
    public static boolean installed() {
        return observer != null;
    }

    public static boolean active() {
        Observer o = observer;
        return o != null && o.enabled();
    }

    /**
     * Passes the reply through unchanged and reports how the turn ended. The tenant is captured
     * here, on the thread that assembled the turn, and travels as data in the outcome (AGENTS.md
     * rule 12): whoever handles it on another thread re-applies it with {@code outcome.scope()}.
     */
    public static Flux<String> observe(
            Flux<String> turn,
            UUID agentId,
            String sessionId,
            String userMessage,
            Instant startedAt,
            Optional<TenantContext> tenant) {
        Observer o = observer;
        if (o == null || !o.enabled()) {
            return turn;
        }
        TenantScope scope = TenantScope.of(tenant);
        StringBuilder answer = new StringBuilder();
        return turn.doOnNext(
                        chunk -> {
                            if (chunk != null && answer.length() < MAX_ANSWER_CHARS) {
                                answer.append(chunk);
                            }
                        })
                .doFinally(
                        signal -> {
                            try {
                                o.onTurn(
                                        new TurnOutcome(
                                                agentId,
                                                sessionId,
                                                userMessage,
                                                answer.toString(),
                                                startedAt,
                                                signal == SignalType.ON_COMPLETE,
                                                signal == SignalType.CANCEL,
                                                scope));
                            } catch (RuntimeException e) {
                                log.debug("Skill-learning observer failed: {}", e.getMessage());
                            }
                        });
    }
}
