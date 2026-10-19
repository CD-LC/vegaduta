package com.agenticai.core.agents.tools.browseruse;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import java.util.UUID;

/**
 * {@link BrowserPageOps} over this conversation's live headless session in {@link
 * BrowserUseSessionManager}. Navigate/click/type/read go through the manager's own entry points
 * (the ones the {@code browser_*} tools use), so their guards apply unchanged; only the few
 * operations those do not offer (current URL, element wait, select, load-state settle) hop onto the
 * Playwright thread through {@link BrowserUseSessionManager#withSessionPage}.
 *
 * <p>Needs a real browser, so it is exercised only by the lead's smoke test; everything that
 * decides replay behaviour lives in the pure {@link BrowserRecipeReplayer}.
 */
final class ManagerBrowserPageOps implements BrowserPageOps {

    /** A wait inside one executor hop must stay under the manager's per-hop Future timeout. */
    private static final int MAX_HOP_WAIT_MS = 8_000;

    private final BrowserUseSessionManager manager;
    private final String tenantId;
    private final UUID agentId;
    private final String sessionId;

    ManagerBrowserPageOps(
            BrowserUseSessionManager manager, String tenantId, UUID agentId, String sessionId) {
        this.manager = manager;
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.sessionId = sessionId;
    }

    @Override
    public String navigate(String url) {
        return manager.navigate(tenantId, agentId, sessionId, url).finalUrl();
    }

    @Override
    public void click(String selectorOrDescription) {
        manager.click(tenantId, agentId, sessionId, selectorOrDescription);
    }

    @Override
    public void type(String selectorOrDescription, String text) {
        manager.type(tenantId, agentId, sessionId, selectorOrDescription, text);
    }

    @Override
    public void select(String selectorOrDescription, String value) {
        manager.withSessionPage(
                tenantId,
                agentId,
                sessionId,
                page -> {
                    BrowserUseLocators.performWithFallback(
                            page, selectorOrDescription, locator -> locator.selectOption(value));
                    return null;
                });
    }

    @Override
    public String currentUrl() {
        return manager.withSessionPage(tenantId, agentId, sessionId, Page::url);
    }

    @Override
    public String pageText() {
        return manager.readPageText(tenantId, agentId, sessionId);
    }

    @Override
    public boolean waitForElement(String selectorOrDescription, int timeoutMs) {
        double timeout = Math.max(1, Math.min(timeoutMs, MAX_HOP_WAIT_MS));
        Boolean found =
                manager.withSessionPage(
                        tenantId,
                        agentId,
                        sessionId,
                        page -> {
                            try {
                                BrowserUseLocators.performWithFallback(
                                        page,
                                        selectorOrDescription,
                                        locator ->
                                                locator.first()
                                                        .waitFor(
                                                                new Locator.WaitForOptions()
                                                                        .setTimeout(timeout)));
                                return Boolean.TRUE;
                            } catch (PlaywrightException e) {
                                return Boolean.FALSE;
                            }
                        });
        return Boolean.TRUE.equals(found);
    }

    @Override
    public void settle(int timeoutMs) {
        double timeout = Math.max(1, Math.min(timeoutMs, MAX_HOP_WAIT_MS));
        manager.withSessionPage(
                tenantId,
                agentId,
                sessionId,
                page -> {
                    try {
                        page.waitForLoadState(
                                LoadState.DOMCONTENTLOADED,
                                new Page.WaitForLoadStateOptions().setTimeout(timeout));
                    } catch (PlaywrightException e) {
                        // A page that never settles is not a failure by itself; the step's own
                        // verification decides.
                    }
                    return null;
                });
    }
}
