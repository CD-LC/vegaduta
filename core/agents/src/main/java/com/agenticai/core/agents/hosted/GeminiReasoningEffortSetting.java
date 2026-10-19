package com.agenticai.core.agents.hosted;

import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The admin-selected Gemini thinking effort for hosted-pool turns ({@link
 * FreeTierChatClientService#KEY_GEMINI_REASONING_EFFORT}), read through a time-boxed cache - moved
 * out of {@link FreeTierChatClientService} (extract-only split).
 */
final class GeminiReasoningEffortSetting {

    private static final Logger log = LoggerFactory.getLogger(FreeTierChatClientService.class);

    private final PlatformSettingsService platformSettingsService;
    private final long reasoningConfigCacheMs;
    private final AtomicReference<CachedReasoningEffort> cachedReasoningEffort =
            new AtomicReference<>();

    GeminiReasoningEffortSetting(
            PlatformSettingsService platformSettingsService, long reasoningConfigCacheMs) {
        this.platformSettingsService = platformSettingsService;
        this.reasoningConfigCacheMs = reasoningConfigCacheMs;
    }

    private record CachedReasoningEffort(String effort, long loadedAtMs) {}

    /**
     * Current effective Gemini thinking effort, validated against {@link
     * FreeTierChatClientService#ALLOWED_GEMINI_REASONING_EFFORTS} (a corrupt/hand-edited row
     * degrades to the default rather than reaching the request path). Time-boxed cache around the {@link PlatformSettingsService}
     * read - identical shape and reasoning to {@code
     * FreeTierUsageLimiterService#effectiveQuotaConfig}; an admin change is live within {@code
     * reasoningConfigCacheMs} (10s default) with no redeploy.
     */
    String effective() {
        CachedReasoningEffort cached = cachedReasoningEffort.get();
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.loadedAtMs()) < reasoningConfigCacheMs) {
            return cached.effort();
        }
        String stored =
                platformSettingsService.getString(
                        FreeTierChatClientService.KEY_GEMINI_REASONING_EFFORT,
                        FreeTierChatClientService.DEFAULT_GEMINI_REASONING_EFFORT);
        String effort =
                stored == null
                        ? FreeTierChatClientService.DEFAULT_GEMINI_REASONING_EFFORT
                        : stored.trim().toLowerCase(Locale.ROOT);
        if (!FreeTierChatClientService.ALLOWED_GEMINI_REASONING_EFFORTS.contains(effort)) {
            log.warn(
                    "Platform setting '{}' has unsupported value '{}' - using default '{}'",
                    FreeTierChatClientService.KEY_GEMINI_REASONING_EFFORT,
                    stored,
                    FreeTierChatClientService.DEFAULT_GEMINI_REASONING_EFFORT);
            effort = FreeTierChatClientService.DEFAULT_GEMINI_REASONING_EFFORT;
        }
        cachedReasoningEffort.set(new CachedReasoningEffort(effort, now));
        return effort;
    }
}
