package com.agenticai.core.agents.tools.browseruse;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import java.time.Instant;

/**
 * One tenant's live headless-browser session: a single Playwright {@link Page} (and the {@link
 * BrowserContext} that owns it) that {@code browser_navigate}/{@code browser_click}/{@code
 * browser_type}/{@code browser_read_page_text}/{@code browser_screenshot} all act on across a
 * conversation, keyed by {@link BrowserUseSessionManager}'s {@code sessionKey}.
 *
 * <p>Touched ONLY from {@link BrowserUseSessionManager}'s single Playwright executor thread - see
 * that class's javadoc for why a Playwright {@code Page} must never be called from more than one
 * thread. This class does no synchronization of its own on that basis; it is not a general-purpose
 * thread-safe holder, it is a plain record of "which page, since when, idle since when" for code
 * that is already single-threaded by construction.
 */
final class BrowserUseSession {

    private final String sessionKey;
    private final String tenantId;
    private final BrowserContext context;
    private Page page;
    private final Instant createdAt;
    private Instant lastUsedAt;

    /**
     * Last domain-policy block raised by the route guard, so a failed navigation can say WHY (null
     * = none since cleared).
     */
    private String lastBlocked;

    /**
     * Browser v2 per-session state; null until a v2 tool first runs (flag-off sessions never
     * allocate it).
     */
    private BrowserV2State v2;

    BrowserUseSession(
            String sessionKey,
            String tenantId,
            BrowserContext context,
            Page page,
            Instant createdAt) {
        this.sessionKey = sessionKey;
        this.tenantId = tenantId;
        this.context = context;
        this.page = page;
        this.createdAt = createdAt;
        this.lastUsedAt = createdAt;
    }

    String sessionKey() {
        return sessionKey;
    }

    String tenantId() {
        return tenantId;
    }

    Page page() {
        return page;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant lastUsedAt() {
        return lastUsedAt;
    }

    void touch() {
        this.lastUsedAt = Instant.now();
    }

    /**
     * The page tool calls act on; a popup opened by a click replaces it under browser v2 (see
     * {@link #adoptPage}).
     */
    void adoptPage(Page newPage) {
        this.page = newPage;
    }

    /**
     * When the active page was closed (a popup the model finished with), falls back to the most
     * recently opened page still open.
     */
    void onPageClosed(Page closed) {
        if (closed != page) {
            return;
        }
        java.util.List<Page> open = context.pages();
        if (!open.isEmpty()) {
            this.page = open.get(open.size() - 1);
        }
    }

    int openPageCount() {
        return context.pages().size();
    }

    void noteBlocked(String message) {
        this.lastBlocked = message;
    }

    /** The last domain-policy block message, or null. */
    String lastBlocked() {
        return lastBlocked;
    }

    void clearBlocked() {
        this.lastBlocked = null;
    }

    /** The v2 state, or null when no v2 tool has run in this session. */
    BrowserV2State v2OrNull() {
        return v2;
    }

    /** The v2 state, created from {@code settings} on first use. */
    BrowserV2State v2(BrowserUseV2Settings settings) {
        if (v2 == null) {
            v2 = settings.newState();
        }
        return v2;
    }

    /**
     * Refuses a browser action while a v2 stuck-detector abort is latched; a no-op for sessions
     * that never ran a v2 tool.
     */
    void requireNotAborted() {
        if (v2 != null && v2.isAborted()) {
            throw new BrowserUseException(v2.detector().abortReason());
        }
    }

    /**
     * Closes the underlying context (which closes every page it owns, just this one here).
     * Idempotent enough for the reaper's best-effort sweep - a second close on an already-closed
     * context is swallowed by the caller.
     */
    void close() {
        context.close();
    }
}
