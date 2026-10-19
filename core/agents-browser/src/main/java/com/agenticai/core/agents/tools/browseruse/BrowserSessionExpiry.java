package com.agenticai.core.agents.tools.browseruse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Which browser sessions the idle/lifetime reaper of {@link BrowserUseSessionManager} closes, and
 * the hard per-tenant/global session caps. Pure functions (no Playwright, no executor), split out of
 * the manager so each has a direct unit-test seam.
 */
final class BrowserSessionExpiry {

    private BrowserSessionExpiry() {}

    /**
     * Keys of the sessions past {@code idleTimeoutSeconds} of inactivity or {@code
     * maxLifetimeSeconds} of total age. A session the person holds (or was asked to take) is
     * governed by the takeover clocks, not by agent idleness ({@code heldByPerson}).
     */
    static List<String> expiredKeys(
            Collection<BrowserUseSession> sessions,
            Instant now,
            long idleTimeoutSeconds,
            long maxLifetimeSeconds,
            Predicate<String> heldByPerson) {
        List<String> expired = new ArrayList<>();
        for (BrowserUseSession session : sessions) {
            boolean idle =
                    Duration.between(session.lastUsedAt(), now).toSeconds() >= idleTimeoutSeconds
                            && !heldByPerson.test(session.sessionKey());
            boolean tooOld =
                    Duration.between(session.createdAt(), now).toSeconds() >= maxLifetimeSeconds;
            if (idle || tooOld) {
                expired.add(session.sessionKey());
            }
        }
        return expired;
    }

    /**
     * The hard per-tenant/global session-cap check - a pure, exception-or-nothing function so it
     * needs no Playwright/executor machinery to test. The counts are computed by the caller (which
     * does need the session map, hence living on the executor thread); this only decides whether
     * they pass.
     */
    static void checkCapacity(
            long tenantCount, long totalCount, int maxSessionsPerTenant, int maxTotalSessions) {
        if (tenantCount >= maxSessionsPerTenant) {
            throw new BrowserUseException(
                    "this tenant already has "
                            + maxSessionsPerTenant
                            + " active browser session(s), the maximum "
                            + "allowed at once - wait for one to finish or time out before starting another");
        }
        if (totalCount >= maxTotalSessions) {
            throw new BrowserUseException(
                    "the platform's headless-browser capacity is fully in use right now - try again shortly");
        }
    }
}
