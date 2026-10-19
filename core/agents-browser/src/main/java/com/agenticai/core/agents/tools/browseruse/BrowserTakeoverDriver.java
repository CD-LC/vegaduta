package com.agenticai.core.agents.tools.browseruse;

import java.util.Collection;
import java.util.UUID;

/**
 * What takeover needs from a live browser session, as an interface so {@link
 * BrowserTakeoverService} - the state machine, authorisation, rate limiting and secret handling -
 * is pure and unit-testable with a fake page. The production implementation is {@link
 * BrowserUseSessionManager}, which runs each call on its single Playwright thread against the SAME
 * page the agent's {@code browser_*} tools use (so the person sees and drives exactly what the
 * agent was driving, behind the same SSRF / domain-policy route guards).
 *
 * <p>None of these methods is reachable by the agent, and none is gated by takeover state: the gate
 * is on the agent's entry points. Every method may throw {@link BrowserUseException} with a short,
 * generic reason (never quoting selectors, URLs' queries or typed text).
 */
interface BrowserTakeoverDriver {

    /**
     * One screenshot: JPEG bytes plus the dimensions of the image and of the page viewport it came
     * from.
     */
    record Frame(
            byte[] jpeg, int frameWidth, int frameHeight, int viewportWidth, int viewportHeight) {}

    /**
     * What the agent is allowed to learn when it gets the page back. {@code text} is already
     * scrubbed and capped.
     */
    record ResumeSnapshot(String hostAndPath, String text, boolean offSite) {}

    /** The page's current URL. */
    String currentUrl(String sessionKey);

    /**
     * Why takeover must NOT be offered on the page's current site (domain policy, profile scope, or
     * - when required - no explicit allow-list), or {@code null} when it may.
     */
    String siteRefusal(String sessionKey, UUID agentId, String url);

    /**
     * A JPEG of the viewport no larger than {@code maxDimension} on its long side, at most {@code
     * quality}.
     */
    Frame frame(String sessionKey, int maxDimension, int quality);

    /** The page viewport size {@code [width, height]} in CSS pixels. */
    int[] viewport(String sessionKey);

    void click(String sessionKey, int x, int y);

    /** A named key ("Enter", "Backspace", ...) or a single character. */
    void press(String sessionKey, String key);

    /** Types text into the focused element. Implementations must never log {@code text}. */
    void typeText(String sessionKey, String text);

    void scroll(String sessionKey, int x, int y, int dx, int dy);

    /**
     * Hand-back read: registers {@code typedCandidates} (what the person typed) with the session's
     * redaction set, then returns the page's text scrubbed of them and of every vault value typed
     * in this session, capped at {@code maxChars}. If the page ended up on a site the policy
     * refuses it is blanked and {@code offSite} is true.
     */
    ResumeSnapshot resume(
            String sessionKey, UUID agentId, Collection<String> typedCandidates, int maxChars);

    /** Closes the session (best effort, asynchronous). */
    void closeSession(String sessionKey);
}
