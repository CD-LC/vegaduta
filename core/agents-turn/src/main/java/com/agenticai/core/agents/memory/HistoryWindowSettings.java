package com.agenticai.core.agents.memory;

import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The admin-tunable replay-window knobs of {@link SessionMemoryService} (platform_setting-backed),
 * moved out of it verbatim to keep that class under the file-size ceiling.
 *
 * <p>The constructor defaults are the {@code @Value} fallbacks, used only when no platform_setting
 * row exists yet (a database that predates the seed migration); the LIVE values come from {@link
 * #current()}. 40 turns / 32k chars keeps roughly the last 20 exchanges. 32k chars was meant to cap
 * the replay at ~8k tokens, which only holds for Latin-script text: Devanagari or CJK is roughly
 * one token per character, so the same 32k chars could be ~32k tokens. The separate 8k
 * estimated-token cap makes that intent hold for every script (see {@link
 * SessionMemoryService#estimateTokens}).
 */
final class HistoryWindowSettings {

    private static final Logger log = LoggerFactory.getLogger(HistoryWindowSettings.class);

    /**
     * Settings-catalog keys: a workspace's own replay window (tenant scope; the history has no
     * agent).
     */
    static final String MAX_TURNS = "chat.history.maxTurns";

    static final String MAX_CHARS = "chat.history.maxChars";
    static final String MAX_TOKENS = "chat.history.maxTokens";

    /**
     * The three window knobs, snapshotted together so one turn never mixes a fresh cap with a stale
     * one.
     */
    record HistoryConfig(int maxTurns, int maxChars, int maxTokens) {}

    private record CachedHistoryConfig(HistoryConfig config, long loadedAtMs) {}

    private final PlatformSettingsService settings;
    private final int defaultMaxTurns;
    private final int defaultMaxChars;
    private final int defaultMaxTokens;
    private final long cacheMs;
    private final AtomicReference<CachedHistoryConfig> cached = new AtomicReference<>();

    HistoryWindowSettings(
            PlatformSettingsService settings,
            int defaultMaxTurns,
            int defaultMaxChars,
            int defaultMaxTokens,
            long cacheMs) {
        this.settings = settings;
        this.defaultMaxTurns = defaultMaxTurns;
        this.defaultMaxChars = defaultMaxChars;
        this.defaultMaxTokens = defaultMaxTokens;
        this.cacheMs = cacheMs;
    }

    /**
     * Time-boxed cache (default 10s, {@code app.chat.history-config-cache-ms}) around {@link
     * PlatformSettingsService} reads — identical shape and reasoning to {@code
     * FreeTierUsageLimiterService#effectiveQuotaConfig}: {@code getHistory} runs on every chat
     * turn, so an uncached read would add a Postgres round trip per message; 10s staleness on an
     * admin knob is imperceptible. Values are clamped to at least 1 turn / 0 chars / 0 tokens so a
     * mistyped admin value can never blank the window entirely (the always-include-newest rule
     * still guarantees one turn of context).
     */
    HistoryConfig platform() {
        CachedHistoryConfig hit = cached.get();
        long now = System.currentTimeMillis();
        if (hit != null && (now - hit.loadedAtMs()) < cacheMs) {
            return hit.config();
        }
        HistoryConfig fresh =
                new HistoryConfig(
                        Math.max(
                                1,
                                settings.getInt(
                                        SessionMemoryService.KEY_HISTORY_MAX_TURNS,
                                        defaultMaxTurns)),
                        Math.max(
                                0,
                                settings.getInt(
                                        SessionMemoryService.KEY_HISTORY_MAX_CHARS,
                                        defaultMaxChars)),
                        Math.max(
                                0,
                                settings.getInt(
                                        SessionMemoryService.KEY_HISTORY_MAX_TOKENS,
                                        defaultMaxTokens)));
        cached.set(new CachedHistoryConfig(fresh, now));
        return fresh;
    }

    private static volatile SettingsResolver catalog;

    /**
     * Hands over the settings catalog at startup; without one every workspace gets the platform
     * window.
     */
    static void use(SettingsResolver resolver) {
        catalog = resolver;
    }

    /** Takes the catalog back when its context closes (only if it is still the one in use). */
    static void release(SettingsResolver resolver) {
        if (catalog == resolver) {
            catalog = null;
        }
    }

    /**
     * The window of the calling thread's workspace: the platform window ({@link #platform()}) with
     * the workspace's {@code chat.history.*} settings applied. With no catalog, no tenant on the
     * thread or a failing read it is the platform window, so a settings outage never blanks or
     * widens a prompt. The same floors as {@link #platform()} apply (at least one turn, never a
     * negative budget).
     */
    HistoryConfig current() {
        HistoryConfig platform = platform();
        SettingsResolver resolver = catalog;
        if (resolver == null) {
            return platform;
        }
        try {
            SettingContext ctx = SettingContext.tenant();
            int turns = Math.max(1, resolver.getInt(MAX_TURNS, ctx));
            int chars = Math.max(0, resolver.getInt(MAX_CHARS, ctx));
            int tokens = Math.max(0, resolver.getInt(MAX_TOKENS, ctx));
            if (turns == platform.maxTurns()
                    && chars == platform.maxChars()
                    && tokens == platform.maxTokens()) {
                return platform;
            }
            return new HistoryConfig(turns, chars, tokens);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not read the chat.history.* settings; using the platform window: {}",
                    e.toString());
            return platform;
        }
    }
}
