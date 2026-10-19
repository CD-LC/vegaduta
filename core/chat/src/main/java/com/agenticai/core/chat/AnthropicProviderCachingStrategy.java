package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import java.util.Locale;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Real cache-object lifecycle strategy for Anthropic prompt caching
 * (platform.claude.com/docs/en/build-with-claude/prompt-caching), wired
 * through Spring AI 1.1's own {@link AnthropicCacheOptions} rather than
 * hand-built {@code cache_control} JSON. Extracted verbatim (behavior
 * unchanged) from {@link AnthropicAgentChatClientFactory}, which owned this
 * inline before {@link ProviderCachingStrategy} existed - see that class's
 * git history for the original javadoc on why {@code SYSTEM_AND_TOOLS} is
 * the default and why {@code multiBlockSystemCaching} is unconditional.
 */
@Component
public class AnthropicProviderCachingStrategy implements ProviderCachingStrategy<AnthropicChatOptions.Builder> {

    private final boolean enabled;
    private final AnthropicCacheStrategy strategy;

    public AnthropicProviderCachingStrategy(
        @Value("${app.llm.anthropic.prompt-caching.enabled:true}") boolean enabled,
        @Value("${app.llm.anthropic.prompt-caching.strategy:SYSTEM_AND_TOOLS}") String strategy
    ) {
        this.enabled = enabled;
        this.strategy = parseStrategy(strategy);
    }

    private static AnthropicCacheStrategy parseStrategy(String configured) {
        try {
            return AnthropicCacheStrategy.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Misconfigured app.llm.anthropic.prompt-caching.strategy value -
            // fail open to "cache nothing" rather than fail the whole bean
            // over a typo in a cost-optimization setting.
            return AnthropicCacheStrategy.NONE;
        }
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.ANTHROPIC;
    }

    @Override
    public boolean isEnabled(String model) {
        return enabled && strategy != AnthropicCacheStrategy.NONE;
    }

    @Override
    public AnthropicChatOptions.Builder apply(AnthropicChatOptions.Builder optionsBuilder, ProviderCachingContext context) {
        if (!isEnabled(context.model())) {
            return optionsBuilder;
        }
        return optionsBuilder.cacheOptions(AnthropicCacheOptions.builder()
            .strategy(strategy)
            .multiBlockSystemCaching(true)
            .build());
    }
}
