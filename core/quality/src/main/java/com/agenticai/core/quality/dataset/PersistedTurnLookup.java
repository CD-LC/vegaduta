package com.agenticai.core.quality.dataset;

import com.agenticai.core.security.TenantContext;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

/**
 * Reads one persisted Playground turn (core/rl's {@code Episode} + {@code TrajectoryStep} rows) for
 * {@link EvalDatasetService#importTurnCase}, reflectively through the merged application context because
 * {@code core/rl} is not - and per this module's pom boundary cannot be - a compile-time dependency. Same
 * runtime-lookup trick the eval replay bridge used before it became a typed bean.
 */
final class PersistedTurnLookup {

    private static final Logger log = LoggerFactory.getLogger(PersistedTurnLookup.class);

    private final ApplicationContext applicationContext;

    PersistedTurnLookup(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * The two texts of one persisted turn; either may be null when that step was never recorded.
     */
    record PersistedTurn(String userMessage, String assistantReply) {}

    /**
     * Best-effort read of core/rl's {@code Episode} + {@code TrajectoryStep} rows for one turn, via
     * the merged application context. Every failure mode - RL module absent (module-scoped
     * context), repository bean missing, method signature drifted, no episode for the turn - yields
     * {@link Optional#empty()} and a DEBUG line, never an exception: the caller falls back to the
     * request body, which is the documented contract. Bound method names are the repository's own
     * derived-query names ({@code findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc},
     * {@code findByEpisodeIdOrderByStepIndexAsc}) and the entities' plain getters - if core/rl
     * renames them this degrades to "body only", which is visible in the case's text but never a
     * broken endpoint.
     */
    Optional<PersistedTurn> find(UUID agentId, String sessionId, String turnRef) {
        if (applicationContext == null) {
            return Optional.empty();
        }
        try {
            Class<?> episodeRepoClass =
                    Class.forName("com.agenticai.core.rl.repo.EpisodeRepository");
            Class<?> stepRepoClass =
                    Class.forName("com.agenticai.core.rl.repo.TrajectoryStepRepository");
            Object episodeRepo = applicationContext.getBean(episodeRepoClass);
            Object stepRepo = applicationContext.getBean(stepRepoClass);

            Method findEpisode =
                    episodeRepoClass.getMethod(
                            "findFirstByAgentIdAndSessionIdAndTurnRefOrderByStartedAtDesc",
                            UUID.class,
                            String.class,
                            String.class);
            Object maybeEpisode = findEpisode.invoke(episodeRepo, agentId, sessionId, turnRef);
            if (!(maybeEpisode instanceof Optional<?> optional) || optional.isEmpty()) {
                return Optional.empty();
            }
            Object episode = optional.get();
            // Only the caller's own turn: an agent editor must not copy another user's
            // conversation into a dataset. Another person's (or an unattributed) turn falls back
            // to the request body, like a turn that was never recorded.
            Object subject = episode.getClass().getMethod("getSubject").invoke(episode);
            String caller = TenantContext.current().map(TenantContext::getSubject).orElse(null);
            if (subject == null || !subject.equals(caller)) {
                return Optional.empty();
            }
            UUID episodeId = (UUID) episode.getClass().getMethod("getId").invoke(episode);

            Method findSteps =
                    stepRepoClass.getMethod("findByEpisodeIdOrderByStepIndexAsc", UUID.class);
            Object steps = findSteps.invoke(stepRepo, episodeId);
            String user = null;
            String assistant = null;
            if (steps instanceof List<?> stepList) {
                for (Object step : stepList) {
                    String role = (String) step.getClass().getMethod("getRole").invoke(step);
                    String content = (String) step.getClass().getMethod("getContent").invoke(step);
                    if (content == null || content.isBlank()) {
                        continue;
                    }
                    // First user message, LAST assistant answer: a tool-using
                    // turn can record intermediate assistant steps; the
                    // final one is the reply the human actually saw.
                    if ("user".equalsIgnoreCase(role) && user == null) {
                        user = content;
                    } else if ("assistant".equalsIgnoreCase(role)) {
                        assistant = content;
                    }
                }
            }
            return Optional.of(new PersistedTurn(user, assistant));
        } catch (Exception e) {
            log.debug(
                    "Persisted-turn lookup unavailable for agent {} session {} turnRef {} - using request body ({})",
                    agentId,
                    sessionId,
                    turnRef,
                    e.toString());
            return Optional.empty();
        }
    }
}
