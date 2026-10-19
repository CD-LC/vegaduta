package com.agenticai.core.agents.tools.browseruse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration of browser human takeover, all under {@code app.browser.takeover.*}.
 *
 * <p>{@code app.browser.takeover.enabled} defaults to <b>false</b> and the feature additionally
 * needs browser v2 ({@code app.browser.v2.enabled}): {@link #isActive()} is the single gate every
 * part consults. With it off no tool is registered, no endpoint answers (404) and the session
 * manager takes none of its takeover branches, so behaviour is identical to a build without the
 * feature.
 *
 * <p>Single constructor on purpose (see {@code InjectableConstructorTest}); every {@code @Value}
 * repeats its default so a slim test context constructs. Every numeric knob is clamped so a typo
 * cannot turn the live view into an unbounded stream or an unlimited input pipe.
 */
@Component
public class BrowserTakeoverSettings {

    /**
     * {@code return}: an idle takeover hands the page back to the agent. {@code expire}: it ends
     * the session.
     */
    enum IdleAction {
        RETURN,
        EXPIRE
    }

    private final boolean enabled;
    private final ObjectProvider<BrowserUseV2Settings> v2;
    private final double fps;
    private final int maxDimension;
    private final int jpegQuality;
    private final long idleTimeoutSeconds;
    private final IdleAction idleAction;
    private final long maxDurationSeconds;
    private final long pausedWaitSeconds;
    private final long tokenTtlSeconds;
    private final int inputPerSecond;
    private final int inputBurst;
    private final int maxTypeChars;
    private final int maxScrollPx;
    private final int maxStreams;
    private final boolean requireExplicitAllowlist;
    private final String tokenSecret;
    private final int resumeTextMaxChars;
    private final int reasonMaxChars;

    public BrowserTakeoverSettings(
            @Value("${app.browser.takeover.enabled:false}") boolean enabled,
            ObjectProvider<BrowserUseV2Settings> v2,
            @Value("${app.browser.takeover.stream.fps:2}") double fps,
            @Value("${app.browser.takeover.stream.max-dimension:1024}") int maxDimension,
            @Value("${app.browser.takeover.stream.jpeg-quality:55}") int jpegQuality,
            @Value("${app.browser.takeover.idle-timeout-seconds:300}") long idleTimeoutSeconds,
            @Value("${app.browser.takeover.idle-action:return}") String idleAction,
            @Value("${app.browser.takeover.max-duration-seconds:900}") long maxDurationSeconds,
            @Value("${app.browser.takeover.paused-wait-seconds:600}") long pausedWaitSeconds,
            @Value("${app.browser.takeover.token-ttl-seconds:120}") long tokenTtlSeconds,
            @Value("${app.browser.takeover.input.per-second:20}") int inputPerSecond,
            @Value("${app.browser.takeover.input.burst:40}") int inputBurst,
            @Value("${app.browser.takeover.input.max-type-chars:256}") int maxTypeChars,
            @Value("${app.browser.takeover.input.max-scroll-px:2000}") int maxScrollPx,
            @Value("${app.browser.takeover.max-streams:4}") int maxStreams,
            @Value("${app.browser.takeover.require-explicit-allowlist:true}")
                    boolean requireExplicitAllowlist,
            @Value("${app.browser.takeover.token-secret:}") String tokenSecret,
            @Value("${app.browser.takeover.resume-text-max-chars:1500}") int resumeTextMaxChars,
            @Value("${app.browser.takeover.reason-max-chars:200}") int reasonMaxChars) {
        this.enabled = enabled;
        this.v2 = v2;
        this.fps = Math.max(0.2, Math.min(5.0, fps));
        this.maxDimension = clamp(maxDimension, 320, 1600);
        this.jpegQuality = clamp(jpegQuality, 20, 80);
        this.idleTimeoutSeconds = Math.max(15, Math.min(3_600, idleTimeoutSeconds));
        this.idleAction =
                "expire".equalsIgnoreCase(idleAction == null ? "" : idleAction.strip())
                        ? IdleAction.EXPIRE
                        : IdleAction.RETURN;
        this.maxDurationSeconds = Math.max(60, Math.min(7_200, maxDurationSeconds));
        this.pausedWaitSeconds = Math.max(30, Math.min(7_200, pausedWaitSeconds));
        this.tokenTtlSeconds = Math.max(20, Math.min(900, tokenTtlSeconds));
        this.inputPerSecond = clamp(inputPerSecond, 1, 100);
        this.inputBurst = clamp(inputBurst, 1, 200);
        this.maxTypeChars = clamp(maxTypeChars, 1, 1_000);
        this.maxScrollPx = clamp(maxScrollPx, 50, 10_000);
        this.maxStreams = clamp(maxStreams, 1, 50);
        this.requireExplicitAllowlist = requireExplicitAllowlist;
        this.tokenSecret = tokenSecret == null ? "" : tokenSecret;
        this.resumeTextMaxChars = clamp(resumeTextMaxChars, 0, 8_000);
        this.reasonMaxChars = clamp(reasonMaxChars, 20, 500);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * The one gate: takeover is on AND browser v2 is on. Everything else is a no-op / 404 when this
     * is false.
     */
    public boolean isActive() {
        if (!enabled) {
            return false;
        }
        BrowserUseV2Settings settings = v2 == null ? null : v2.getIfAvailable();
        return settings != null && settings.isEnabled();
    }

    double fps() {
        return fps;
    }

    long frameIntervalMillis() {
        return Math.max(50L, Math.round(1000.0 / fps));
    }

    int maxDimension() {
        return maxDimension;
    }

    int jpegQuality() {
        return jpegQuality;
    }

    long idleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    IdleAction idleAction() {
        return idleAction;
    }

    long maxDurationSeconds() {
        return maxDurationSeconds;
    }

    long pausedWaitSeconds() {
        return pausedWaitSeconds;
    }

    long tokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    int inputPerSecond() {
        return inputPerSecond;
    }

    int inputBurst() {
        return inputBurst;
    }

    int maxTypeChars() {
        return maxTypeChars;
    }

    int maxScrollPx() {
        return maxScrollPx;
    }

    int maxStreams() {
        return maxStreams;
    }

    boolean requireExplicitAllowlist() {
        return requireExplicitAllowlist;
    }

    /**
     * The operator-supplied token secret, or empty (a random per-process secret is then generated).
     */
    String tokenSecret() {
        return tokenSecret;
    }

    int resumeTextMaxChars() {
        return resumeTextMaxChars;
    }

    int reasonMaxChars() {
        return reasonMaxChars;
    }
}
