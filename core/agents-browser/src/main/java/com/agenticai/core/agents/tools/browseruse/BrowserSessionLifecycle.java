package com.agenticai.core.agents.tools.browseruse;

import com.microsoft.playwright.Page;
import java.util.UUID;

/**
 * Optional extension point on {@link BrowserUseSessionManager}: observers that need to act when a
 * headless-browser session is created or closed (browser profiles seed a new context with saved
 * cookies/localStorage and persist the refreshed state again at the end).
 *
 * <p>Every method runs on the manager's single Playwright executor thread, with NO {@code
 * TenantContext} set, and must therefore be quick and must never throw into the manager
 * (implementations swallow their own failures - a broken observer must not break browsing). The
 * default methods are no-ops, so a context with no lifecycle bean behaves exactly as before.
 *
 * <p>The session key is the manager's {@code tenantId|agentId|sessionId} key; {@link
 * #agentIdFromKey} extracts the agent so observers need not parse it themselves.
 */
public interface BrowserSessionLifecycle {

    /**
     * Playwright storage-state JSON (cookies + localStorage) to seed a brand-new context with, or
     * {@code null} for a clean context (the default).
     */
    default String initialStorageState(String tenantId, String sessionKey) {
        return null;
    }

    /**
     * Called once the new context's page exists and the manager's own request guard is installed.
     */
    default void onSessionCreated(String tenantId, String sessionKey, Page page) {}

    /** Called just before the session's context is closed (idle/lifetime reaper, shutdown). */
    default void onSessionClosing(String tenantId, String sessionKey, Page page) {}

    /**
     * Called after a navigate / click / type (or a replayer page operation) succeeded - a cheap
     * checkpoint so state that just changed (a login) is persisted without waiting for the session
     * to close. Implementations must make the unchanged case nearly free (compare a fingerprint)
     * because this runs after every such action.
     */
    default void onActionCompleted(String tenantId, String sessionKey, Page page) {}

    /**
     * If this observer refused a navigation in this session since the last successful action, a
     * short model-facing reason (and forgets it), else {@code null}. Lets a failed {@code
     * browser_navigate} say WHY instead of a bare "net::ERR_FAILED".
     */
    default String consumeBlockedReason(String sessionKey) {
        return null;
    }

    /**
     * Human takeover only: whether this session's saved login profile (if any) covers {@code host}.
     * {@code null} = no profile on this session (the default), {@code TRUE} = the host is inside
     * the profile's allowed domains, {@code FALSE} = it is outside them and takeover must be
     * refused.
     */
    default Boolean profileAllowsHost(String sessionKey, String host) {
        return null;
    }

    /**
     * The agent id embedded in a manager session key ({@code tenant|agent|session}), or {@code
     * null} when the key does not have that shape. The tenant id is a Keycloak realm name and the
     * agent id a UUID, so splitting into at most three parts is unambiguous even when the
     * (free-form) session id itself contains a {@code |}.
     */
    static UUID agentIdFromKey(String sessionKey) {
        if (sessionKey == null) {
            return null;
        }
        String[] parts = sessionKey.split("\\|", 3);
        if (parts.length < 2) {
            return null;
        }
        try {
            return UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
