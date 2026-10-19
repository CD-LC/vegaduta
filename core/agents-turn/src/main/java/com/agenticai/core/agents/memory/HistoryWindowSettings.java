package com.agenticai.core.agents.memory;

import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.concurrent.atomic.AtomicReference;

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
    HistoryConfig current() {
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
}
