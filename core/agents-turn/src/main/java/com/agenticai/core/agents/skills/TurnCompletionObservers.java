package com.agenticai.core.agents.skills;

import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import reactor.core.publisher.Flux;

/**
 * The three turn-completion observers a dispatched turn's reply stream is wrapped in, in one
 * place so the dispatcher stays readable:
 *
 * <ul>
 *   <li>skill learning ({@code app.skills.learning.enabled}) reports how the turn ended;
 *   <li>lessons ({@code app.learning.lessons.enabled}) reads mistakes and corrections;
 *   <li>desktop-recipe suggestion ({@code app.learning.desktop-recipes.enabled}) offers a
 *       multi-step {@code local_desktop_*} run for one-click save.
 * </ul>
 *
 * All three are off by default: a gateway with no observer installed - or one that reports itself
 * disabled - returns the very same {@link Flux} it was given, so a disabled loop adds no operator
 * to the turn.
 */
public final class TurnCompletionObservers {

    private TurnCompletionObservers() {}

    public static Flux<String> observe(
            Flux<String> turn,
            UUID agentId,
            String sessionId,
            String userMessage,
            Instant startedAt,
            Optional<TenantContext> tenant) {
        turn = SkillLearningGateway.observe(turn, agentId, sessionId, userMessage, startedAt, tenant);
        turn = LessonLearningGateway.observe(turn, agentId, sessionId, userMessage, startedAt, tenant);
        return DesktopRunSuggestionGateway.observe(turn, agentId, sessionId, userMessage, startedAt, tenant);
    }
}
