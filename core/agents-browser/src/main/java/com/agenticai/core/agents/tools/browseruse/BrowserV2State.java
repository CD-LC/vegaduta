package com.agenticai.core.agents.tools.browseruse;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-session state of the browser v2 tools: the server-side copy of the last snapshot (the source
 * of truth for what an index means - the model's own label is never trusted), the stuck/loop
 * detector, the vault values typed in this session (kept only to scrub them from later results) and
 * the abort latch.
 *
 * <p>Held by {@link BrowserUseSession}, touched only on the session manager's single Playwright
 * thread, so no synchronisation. Created lazily the first time a v2 tool runs, so a flag-off
 * deployment never allocates one.
 */
final class BrowserV2State {

    private final BrowserStuckDetector detector;
    private BrowserSnapshot.Data snapshot;
    private final Set<String> secretValues = new LinkedHashSet<>();
    private String abortUrl;

    BrowserV2State(int stuckWarnAfter, int stuckAbortAfter) {
        this.detector = new BrowserStuckDetector(stuckWarnAfter, stuckAbortAfter);
    }

    BrowserStuckDetector detector() {
        return detector;
    }

    /** The last snapshot taken in this session, or null. */
    BrowserSnapshot.Data snapshot() {
        return snapshot;
    }

    void setSnapshot(BrowserSnapshot.Data snapshot) {
        this.snapshot = snapshot;
    }

    /** Remembers a vault value that was typed so later page text can be scrubbed of it. */
    void rememberSecretValues(Collection<String> values) {
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isEmpty()) {
                    secretValues.add(v);
                }
            }
        }
    }

    boolean hasSecretValues() {
        return !secretValues.isEmpty();
    }

    /**
     * Scrubs every vault value typed in this session out of {@code text} (values under 6 characters
     * get an exact-match scrub; see {@link BrowserSecretPlaceholders#redact}).
     */
    String redact(String text) {
        return secretValues.isEmpty() ? text : BrowserSecretPlaceholders.redact(text, secretValues);
    }

    boolean isAborted() {
        return detector.isAborted();
    }

    /**
     * Remembers the page the stuck detector gave up on (first call after a latch wins), so a
     * re-navigate to the SAME page cannot clear it.
     */
    void noteAbortUrl(String url) {
        if (abortUrl == null) {
            abortUrl = url == null ? "" : url;
        }
    }

    /**
     * True when {@code requestedUrl} is the page the browsing task was aborted on (ignoring
     * fragment and trailing slash).
     */
    boolean isAbortedPage(String requestedUrl) {
        return detector.isAborted()
                && abortUrl != null
                && !abortUrl.isEmpty()
                && comparableUrl(abortUrl).equalsIgnoreCase(comparableUrl(requestedUrl));
    }

    /**
     * A deliberate re-plan (navigation to a DIFFERENT page): clears the stuck detector and its
     * abort latch.
     */
    void resetForReplan() {
        detector.reset();
        abortUrl = null;
    }

    private static String comparableUrl(String url) {
        if (url == null) {
            return "";
        }
        int hash = url.indexOf('#');
        String s = hash >= 0 ? url.substring(0, hash) : url;
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }
}
