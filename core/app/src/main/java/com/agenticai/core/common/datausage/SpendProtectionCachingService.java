package com.agenticai.core.common.datausage;

import com.agenticai.core.chat.LlmCallLogRepository;
import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.chat.ProviderCachingRegistry;
import com.agenticai.core.providers.ProviderName;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Builds the per-provider prompt-caching effectiveness rows for the spend-protection panel, so
 * {@link TenantUsageQuotaController} never touches the call-log repository projection types.
 */
@Service
@Profile("security")
public class SpendProtectionCachingService {

    private final ProviderCachingRegistry providerCachingRegistry;
    private final LlmCallLogService llmCallLogService;

    public SpendProtectionCachingService(
            ProviderCachingRegistry providerCachingRegistry, LlmCallLogService llmCallLogService) {
        this.providerCachingRegistry = providerCachingRegistry;
        this.llmCallLogService = llmCallLogService;
    }

    public List<SpendProtectionResponse.CachingProviderStatus> cachingStatus(Instant windowStart) {
        // Source of truth for "which providers have caching active" - the
        // shared registry every ProviderCachingStrategy bean registers into
        // automatically, never a hardcoded provider list (Anthropic/OpenAI
        // today, Gemini and others as their strategies land).
        Map<String, LlmCallLogRepository.CacheEffectivenessRow> effectivenessByProvider =
                llmCallLogService.cacheEffectivenessSince(windowStart).stream()
                        .collect(
                                Collectors.toMap(
                                    LlmCallLogRepository.CacheEffectivenessRow::getProviderName,
                                    row -> row));

        return providerCachingRegistry
                .activeProviders().stream()
                    .sorted(Comparator.comparing(ProviderName::name))
                    .map(
                            provider -> {
                                LlmCallLogRepository.CacheEffectivenessRow row =
                                        effectivenessByProvider.get(provider.name());
                                if (row == null || row.getTotalCalls() == 0) {
                                    // No calls to this provider in the window yet - real
                                    // absence of data, not a fabricated 0% metric.
                                    return new SpendProtectionResponse.CachingProviderStatus(
                                            provider.name(), true, 0, null, null);
                                }
                                long totalInputTokens =
                                        row.getPromptTokens()
                                                + row.getCacheCreationTokens()
                                                + row.getCacheReadTokens();
                                Double cacheReadTokenShare =
                                        totalInputTokens > 0
                                                ? row.getCacheReadTokens()
                                                        / (double) totalInputTokens
                                                : null;
                                Double callsWithCacheHitShare =
                                        row.getCallsWithCacheHit()
                                                / (double) row.getTotalCalls();
                                return new SpendProtectionResponse.CachingProviderStatus(
                                        provider.name(),
                                        true,
                                        row.getTotalCalls(),
                                        cacheReadTokenShare,
                                        callsWithCacheHitShare);
                            })
                    .toList();
    }
}
