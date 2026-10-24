package com.agenticai.core.agents.skills;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one hook the chat turn and the job submit path have into the "routines from habits" learning loop
 * ({@code app.learning.routines.enabled}). It is a plain static holder, installed once at startup by the loop
 * (same shape as {@link SkillLearningGateway}), because both call sites are not beans.
 *
 * <p>It adds no operator to any stream: {@link #observe} only reports that the person made a request. With no
 * observer installed, or one that reports itself disabled (the default), it returns before doing any work. The
 * tenant is captured here, on the calling thread, and travels as data (AGENTS.md rule 12); the observer must hand
 * the work to its own bounded worker and never block.
 */
public final class RoutineObservationGateway {

    private static final Logger log = LoggerFactory.getLogger(RoutineObservationGateway.class);

    /** Where a request came from. */
    public static final String CHAT = "chat";

    public static final String JOB = "job";

    /** One request the person made. {@code text} is raw: the observer scrubs it before storing anything. */
    public record Observation(
            UUID agentId, String sessionId, String text, String source, Instant at, TenantScope scope) {}

    /** Receives each request on the calling thread; must return quickly and never throw. */
    public interface Observer {
        boolean enabled();

        void onObservation(Observation observation);
    }

    private static volatile Observer observer;

    private RoutineObservationGateway() {}

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

    /** Reports a request made inside a chat turn; {@code tenant} is the turn's own context. */
    public static void observe(
            UUID agentId,
            String sessionId,
            String text,
            String source,
            Optional<TenantContext> tenant) {
        Observer o = observer;
        if (o == null || agentId == null || text == null || text.isBlank() || !o.enabled()) {
            return;
        }
        try {
            o.onObservation(
                    new Observation(agentId, sessionId, text, source, Instant.now(), TenantScope.of(tenant)));
        } catch (RuntimeException e) {
            log.debug("Routine observer failed: {}", e.getMessage());
        }
    }

    /** Reports a request made on the current (request) thread, using the thread's tenant. */
    public static void observeCurrent(UUID agentId, String text, String source) {
        if (!active()) {
            return;
        }
        observe(agentId, null, text, source, TenantContext.current());
    }
}
