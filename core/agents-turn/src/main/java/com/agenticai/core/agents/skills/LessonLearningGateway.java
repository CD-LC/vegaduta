package com.agenticai.core.agents.skills;

import com.agenticai.core.agents.skills.SkillLearningGateway.TurnOutcome;
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
 * The chat turn's hook into the lessons loop (mistakes and corrections), the same shape as
 * {@link SkillLearningGateway} so the two loops switch independently. Installed once at startup by
 * the lessons service; with no observer, or one that reports itself disabled
 * ({@code app.learning.lessons.enabled=false}, the default), {@link #observe} returns the very same
 * {@link Flux} it was given: not one operator is added to the turn.
 */
public final class LessonLearningGateway {

    private static final Logger log = LoggerFactory.getLogger(LessonLearningGateway.class);

    /** The reply text kept for the observer; a longer answer is cut, never buffered unbounded. */
    static final int MAX_ANSWER_CHARS = 12_000;

    /** Receives every observed turn on the thread that finished it; must be quick and never throw. */
    public interface Observer {
        boolean enabled();

        void onTurn(TurnOutcome outcome);
    }

    private static volatile Observer observer;

    private LessonLearningGateway() {}

    public static void install(Observer installed) {
        observer = installed;
    }

    public static boolean active() {
        Observer o = observer;
        return o != null && o.enabled();
    }

    /** Passes the reply through unchanged; the tenant travels as data in the outcome (AGENTS.md rule 12). */
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
                                log.debug("Lessons observer failed: {}", e.getMessage());
                            }
                        });
    }
}
