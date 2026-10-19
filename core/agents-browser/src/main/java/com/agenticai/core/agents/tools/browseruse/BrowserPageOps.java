package com.agenticai.core.agents.tools.browseruse;

/**
 * The handful of page operations a recipe replay needs, as an interface so {@link
 * BrowserRecipeReplayer} is pure and unit-testable with a fake page. The production implementation
 * is {@link ManagerBrowserPageOps}, which drives the SAME headless session the live {@code
 * browser_*} tools use (so a failed replay leaves the browser exactly where the agent can carry on
 * by hand) and goes through the same {@link BrowserUseSessionManager} entry points, so every guard
 * they enforce (public-URL validation, per-request SSRF route guard, session caps and any domain
 * allow-list added there) applies to replayed steps too.
 *
 * <p>Every method may throw {@link BrowserUseException} (or any RuntimeException); the replayer
 * turns that into a failed step rather than letting it escape.
 */
interface BrowserPageOps {

    /** Navigates (creating the session if needed) and returns the final URL after redirects. */
    String navigate(String url);

    void click(String selectorOrDescription);

    void type(String selectorOrDescription, String text);

    void select(String selectorOrDescription, String value);

    /** The page's current URL. */
    String currentUrl();

    /** The page's visible text. */
    String pageText();

    /**
     * True when an element matching the selector (or text description) is visible within {@code
     * timeoutMs}.
     */
    boolean waitForElement(String selectorOrDescription, int timeoutMs);

    /**
     * Best-effort wait for the page to finish loading after an action; never throws for a mere
     * timeout.
     */
    void settle(int timeoutMs);
}
