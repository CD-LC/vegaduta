package com.agenticai.core.agents.tools.browseruse;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.ScreenshotType;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

/**
 * {@link BrowserTakeoverDriver} over the live headless sessions in {@link
 * BrowserUseSessionManager}: every call hops onto the manager's single Playwright thread ({@link
 * BrowserUseSessionManager#runForUser}) and acts on the SAME page the agent's tools use, behind the
 * same SSRF / domain-policy / profile-scope route guards (a link the person clicks to a refused
 * domain is refused exactly as an agent navigation would be).
 *
 * <p>Needs a real browser, so it is exercised only by a live smoke test; the rules that decide
 * behaviour (state machine, authorisation, rate limit, redaction bookkeeping) live in the pure
 * {@link BrowserTakeoverService} and are tested with a fake driver. Never logs or echoes typed
 * text: failures surface as a generic {@link BrowserUseException}.
 */
final class ManagerBrowserTakeoverDriver implements BrowserTakeoverDriver {

    private static final int DEFAULT_VIEWPORT_WIDTH = 1280;
    private static final int DEFAULT_VIEWPORT_HEIGHT = 800;

    private final BrowserUseSessionManager manager;

    ManagerBrowserTakeoverDriver(BrowserUseSessionManager manager) {
        this.manager = manager;
    }

    @Override
    public String currentUrl(String sessionKey) {
        return manager.runForUser(sessionKey, false, session -> session.page().url());
    }

    @Override
    public String siteRefusal(String sessionKey, UUID agentId, String url) {
        String host = BrowserDomainPolicy.hostOrNull(url);
        String scheme = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (host == null || !(scheme.startsWith("http://") || scheme.startsWith("https://"))) {
            return "takeover is only offered on a normal web page (the browser is not on one right now) - navigate to the site first";
        }
        BrowserDomainPolicy policy = manager.takeoverDomainPolicy();
        if (policy.isActive()) {
            BrowserDomainPolicy.Decision decision = policy.check(agentId, url, true);
            if (!decision.allowed()) {
                return decision.message();
            }
        }
        Boolean profile = manager.profileAllowsHost(sessionKey, host);
        if (Boolean.FALSE.equals(profile)) {
            return "takeover is not offered on '"
                    + host
                    + "': it is outside the domains of this agent's saved browser profile";
        }
        BrowserTakeoverService service = manager.activeTakeover();
        boolean explicitRequired = service == null || service.settings().requireExplicitAllowlist();
        boolean explicit = policy.hasAllowList(agentId) || Boolean.TRUE.equals(profile);
        if (explicitRequired && !explicit) {
            return "takeover is only offered on sites an administrator has allow-listed for this agent (browser allowed-domains, an "
                    + "agent allow-list, or a saved browser profile's domains); '"
                    + host
                    + "' is not one of them";
        }
        return null;
    }

    @Override
    public Frame frame(String sessionKey, int maxDimension, int quality) {
        return manager.runForUser(
                sessionKey,
                false,
                session -> {
                    Page page = session.page();
                    byte[] jpeg =
                            page.screenshot(
                                    new Page.ScreenshotOptions()
                                            .setType(ScreenshotType.JPEG)
                                            .setQuality(quality));
                    int[] viewport = viewportOf(page);
                    return BrowserTakeoverFrames.fit(
                            jpeg, viewport[0], viewport[1], maxDimension, quality);
                });
    }

    @Override
    public int[] viewport(String sessionKey) {
        return manager.runForUser(sessionKey, false, session -> viewportOf(session.page()));
    }

    private static int[] viewportOf(Page page) {
        var size = page.viewportSize();
        return size == null
                ? new int[] {DEFAULT_VIEWPORT_WIDTH, DEFAULT_VIEWPORT_HEIGHT}
                : new int[] {size.width, size.height};
    }

    @Override
    public void click(String sessionKey, int x, int y) {
        manager.runForUser(
                sessionKey,
                true,
                session -> {
                    session.page().mouse().click(x, y);
                    return null;
                });
    }

    @Override
    public void press(String sessionKey, String key) {
        manager.runForUser(
                sessionKey,
                true,
                session -> {
                    session.page().keyboard().press(key);
                    return null;
                });
    }

    @Override
    public void typeText(String sessionKey, String text) {
        manager.runForUser(
                sessionKey,
                true,
                session -> {
                    session.page().keyboard().type(text);
                    return null;
                });
    }

    @Override
    public void scroll(String sessionKey, int x, int y, int dx, int dy) {
        manager.runForUser(
                sessionKey,
                true,
                session -> {
                    session.page().mouse().move(x, y);
                    session.page().mouse().wheel(dx, dy);
                    return null;
                });
    }

    @Override
    public ResumeSnapshot resume(
            String sessionKey, UUID agentId, Collection<String> typedCandidates, int maxChars) {
        return manager.runForUser(
                sessionKey,
                true,
                session -> {
                    Page page = session.page();
                    BrowserUseV2Settings settings = manager.v2Settings();
                    BrowserV2State v2 =
                            settings == null ? session.v2OrNull() : session.v2(settings);
                    // What the person typed joins the session's redaction set BEFORE the page is
                    // read, so the text the agent
                    // gets back cannot echo it (a page may render a typed value, e.g. "code 123456
                    // accepted").
                    if (v2 != null) {
                        v2.rememberSecretValues(typedCandidates);
                    }
                    BrowserDomainPolicy policy = manager.takeoverDomainPolicy();
                    String url = page.url();
                    if (policy.isActive()) {
                        String scheme = url == null ? "" : url.toLowerCase(Locale.ROOT);
                        if ((scheme.startsWith("http://") || scheme.startsWith("https://"))
                                && !policy.check(agentId, url, true).allowed()) {
                            try {
                                page.navigate("about:blank");
                            } catch (RuntimeException ignored) {
                                // best effort - the offSite flag is what matters
                            }
                            return new ResumeSnapshot("", "", true);
                        }
                    }
                    String text = "";
                    try {
                        text = page.innerText("body");
                    } catch (PlaywrightException e) {
                        // an unreadable page (mid-navigation) is reported as empty, not as a
                        // failure of the hand-back
                    }
                    if (v2 != null) {
                        text = v2.redact(text);
                    }
                    if (text.length() > maxChars) {
                        text = text.substring(0, maxChars) + "...";
                    }
                    return new ResumeSnapshot(hostAndPath(url), text, false);
                });
    }

    /**
     * {@code host/path} of a URL, dropping scheme, query and fragment (a query often carries
     * tokens).
     */
    static String hostAndPath(String url) {
        String host = BrowserDomainPolicy.hostOrNull(url);
        if (host == null) {
            return "";
        }
        try {
            String path = java.net.URI.create(url).getPath();
            return host + (path == null ? "" : path);
        } catch (RuntimeException e) {
            return host;
        }
    }

    @Override
    public void closeSession(String sessionKey) {
        manager.closeSessionAsync(sessionKey);
    }
}
