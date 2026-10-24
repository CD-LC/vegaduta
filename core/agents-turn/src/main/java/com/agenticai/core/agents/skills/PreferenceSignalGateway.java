package com.agenticai.core.agents.skills;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one hook the chat turn has into the "preferences from behavior" learning loop. It hands over the person's
 * message at the START of the turn, never touches the reply stream, and costs one volatile read when the loop is
 * off ({@code app.learning.preferences.enabled=false}, the default). The observer must be quick (it runs on the
 * request thread) and do anything real on its own worker with the tenant carried in {@code scope} (AGENTS.md rule 12).
 */
public final class PreferenceSignalGateway {

    private static final Logger log = LoggerFactory.getLogger(PreferenceSignalGateway.class);

    /** Messages longer than this are not style requests; only this much is ever handed over. */
    public static final int MAX_MESSAGE_CHARS = 400;

    /** Receives the person's message of one turn; must be quick and never throw. */
    public interface Observer {
        boolean enabled();

        void onUserMessage(UUID agentId, String sessionId, String userMessage, TenantScope scope);
    }

    private static volatile Observer observer;

    private PreferenceSignalGateway() {}

    public static void install(Observer installed) {
        observer = installed;
    }

    /** True once an observer is installed, whether or not it reports itself enabled (the startup wiring check). */
    public static boolean installed() {
        return observer != null;
    }

    public static void observe(
            UUID agentId, String sessionId, String userMessage, Optional<TenantContext> tenant) {
        Observer o = observer;
        if (o == null || agentId == null || userMessage == null || userMessage.isBlank() || !o.enabled()) {
            return;
        }
        try {
            String text =
                    userMessage.length() > MAX_MESSAGE_CHARS
                            ? userMessage.substring(0, MAX_MESSAGE_CHARS)
                            : userMessage;
            o.onUserMessage(agentId, sessionId, text, TenantScope.of(tenant));
        } catch (RuntimeException e) {
            log.debug("Preference-signal observer failed: {}", e.getMessage());
        }
    }
}
