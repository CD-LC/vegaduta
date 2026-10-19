package com.agenticai.core.agents.tools.browseruse;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The session lifecycle seam of {@link BrowserUseSessionManager} (browser profiles): everything the
 * optional {@link BrowserSessionLifecycle} observers need lives here. With no observer bean every
 * method is a no-op, so behaviour is unchanged. Extracted verbatim from the manager; all methods
 * run on the manager's single Playwright executor thread, exactly as before (no thread hop is
 * introduced, so no tenant scope handling changes).
 */
final class BrowserSessionLifecycles {

    private static final Logger log = LoggerFactory.getLogger(BrowserSessionLifecycles.class);

    /** Looks up the live session for a key (the manager's executor-thread-owned map). */
    private final Function<String, BrowserUseSession> sessionLookup;

    /**
     * Optional session observers (browser profiles: seed a new context with saved login state,
     * persist it again as it changes and when the session closes). Written once at startup, read on
     * the executor thread.
     */
    private volatile List<BrowserSessionLifecycle> lifecycles = List.of();

    BrowserSessionLifecycles(Function<String, BrowserUseSession> sessionLookup) {
        this.sessionLookup = sessionLookup;
    }

    void set(List<BrowserSessionLifecycle> lifecycles) {
        this.lifecycles = lifecycles == null ? List.of() : List.copyOf(lifecycles);
    }

    /**
     * First non-null answer of the observers on whether a saved profile covers {@code host}; null =
     * no profile.
     */
    Boolean profileAllowsHost(String key, String host) {
        for (BrowserSessionLifecycle lifecycle : lifecycles) {
            Boolean answer = lifecycleSafely(() -> lifecycle.profileAllowsHost(key, host));
            if (answer != null) {
                return answer;
            }
        }
        return null;
    }

    /** Creates the context, seeded with an observer's saved storage state when it offers one. */
    BrowserContext newContext(
            Browser browser, String tenantId, String key, Browser.NewContextOptions options) {
        boolean seeded = false;
        for (BrowserSessionLifecycle lifecycle : lifecycles) {
            String savedState = lifecycleSafely(() -> lifecycle.initialStorageState(tenantId, key));
            if (savedState != null) {
                options.setStorageState(savedState);
                seeded = true;
                break;
            }
        }
        try {
            return browser.newContext(options);
        } catch (RuntimeException e) {
            if (!seeded) {
                throw e;
            }
            // A saved state Playwright refuses must not make the agent's browser permanently
            // unusable.
            log.warn(
                    "browser-use could not apply a saved browser profile ({}); starting a clean session",
                    e.getClass().getSimpleName());
            return browser.newContext(
                    new Browser.NewContextOptions()
                            .setViewportSize(1280, 800)
                            .setIgnoreHTTPSErrors(false)
                            .setServiceWorkers(ServiceWorkerPolicy.BLOCK));
        }
    }

    void sessionCreated(String tenantId, String key, Page page) {
        for (BrowserSessionLifecycle lifecycle : lifecycles) {
            lifecycleSafely(
                    () -> {
                        lifecycle.onSessionCreated(tenantId, key, page);
                        return null;
                    });
        }
    }

    void sessionClosing(BrowserUseSession session, String key) {
        for (BrowserSessionLifecycle lifecycle : lifecycles) {
            lifecycleSafely(
                    () -> {
                        lifecycle.onSessionClosing(session.tenantId(), key, session.page());
                        return null;
                    });
        }
    }

    /**
     * After a successful navigate/click/type/read: lets an observer persist state that just changed
     * (a login).
     */
    void checkpoint(String key) {
        if (lifecycles.isEmpty()) {
            return;
        }
        BrowserUseSession session = sessionLookup.apply(key);
        if (session == null) {
            return;
        }
        for (BrowserSessionLifecycle lifecycle : lifecycles) {
            lifecycleSafely(
                    () -> {
                        lifecycle.onActionCompleted(session.tenantId(), key, session.page());
                        return null;
                    });
        }
    }

    /**
     * {@code page.navigate}, then a checkpoint; a failure carries an observer's reason (e.g. a
     * profile's domain scope).
     */
    void navigate(String key, Page page, String url) {
        try {
            page.navigate(url);
        } catch (RuntimeException e) {
            for (BrowserSessionLifecycle lifecycle : lifecycles) {
                String reason = lifecycleSafely(() -> lifecycle.consumeBlockedReason(key));
                if (reason != null && !reason.isBlank()) {
                    throw new BrowserUseException(reason, e);
                }
            }
            throw e;
        }
        checkpoint(key);
    }

    /**
     * A lifecycle observer must never break browsing: any failure is logged (type only) and treated
     * as "no result".
     */
    private <T> T lifecycleSafely(Callable<T> hook) {
        try {
            return hook.call();
        } catch (Exception e) {
            log.warn("browser-use session lifecycle hook failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
