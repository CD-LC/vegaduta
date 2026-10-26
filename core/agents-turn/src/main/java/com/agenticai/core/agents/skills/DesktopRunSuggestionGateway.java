package com.agenticai.core.agents.skills;

import com.agenticai.core.agents.skills.SkillLearningGateway.TurnOutcome;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * The chat turn's hook into the desktop-recipe suggestion loop ("this run could be a saved
 * recipe"), the same shape as {@link LessonLearningGateway} so the loops switch independently.
 * Installed once at startup by the suggestion service; with no observer, or one that reports
 * itself disabled ({@code app.learning.desktop-recipes.enabled=false}, the default), {@link
 * #observe} returns the very same {@link Flux} it was given: not one operator is added to the
 * turn.
 */
public final class DesktopRunSuggestionGateway {

    private static final Logger log = LoggerFactory.getLogger(DesktopRunSuggestionGateway.class);

    /** Receives every finished turn on the thread that finished it; must be quick and never throw. */
    public interface Observer {
        boolean enabled();

        void onTurn(TurnOutcome outcome);
    }

    private static volatile Observer observer;

    private DesktopRunSuggestionGateway() {}

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
        return turn.doFinally(
                signal -> {
                    try {
                        o.onTurn(
                                new TurnOutcome(
                                        agentId,
                                        sessionId,
                                        userMessage,
                                        "",
                                        startedAt,
                                        signal == SignalType.ON_COMPLETE,
                                        signal == SignalType.CANCEL,
                                        scope));
                    } catch (RuntimeException e) {
                        log.debug("Desktop-run suggestion observer failed: {}", e.getMessage());
                    }
                });
    }
}
