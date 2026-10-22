package com.agenticai.core.chat;

import com.anthropic.models.messages.Usage;
import com.openai.models.completions.CompletionUsage;
import java.util.Optional;
import org.springframework.ai.chat.metadata.DefaultUsage;

/**
 * Provider usage exactly as Spring AI 2.0's {@code AnthropicChatModel#getDefaultUsage} and {@code
 * OpenAiChatModel#getDefaultUsage} build it from the vendor SDK's usage object: token counts, the
 * native SDK object, and the cache read/write counts on the {@code Usage} interface itself.
 */
final class SdkUsageFixtures {

    private SdkUsageFixtures() {}

    /** Anthropic: prompt tokens are the uncached input only (exclusive convention). */
    static DefaultUsage anthropic(
            int inputTokens, int outputTokens, Integer cacheCreation, Integer cacheRead) {
        Usage nativeUsage =
                Usage.builder()
                        .cacheCreation(Optional.empty())
                        .cacheCreationInputTokens(
                                Optional.ofNullable(
                                        cacheCreation == null ? null : cacheCreation.longValue()))
                        .cacheReadInputTokens(
                                Optional.ofNullable(
                                        cacheRead == null ? null : cacheRead.longValue()))
                        .inferenceGeo(Optional.empty())
                        .inputTokens((long) inputTokens)
                        .outputTokens((long) outputTokens)
                        .outputTokensDetails(Optional.empty())
                        .serverToolUse(Optional.empty())
                        .serviceTier(Optional.empty())
                        .build();
        return new DefaultUsage(
                inputTokens,
                outputTokens,
                inputTokens + outputTokens,
                nativeUsage,
                cacheRead == null ? null : cacheRead.longValue(),
                cacheCreation == null ? null : cacheCreation.longValue());
    }

    /**
     * OpenAI(-compatible) usage that also reports {@code reasoning_tokens} in its completion
     * details.
     */
    static DefaultUsage openAiWithReasoning(
            int promptTokens, int completionTokens, int totalTokens, int reasoningTokens) {
        CompletionUsage nativeUsage =
                CompletionUsage.builder()
                        .completionTokens((long) completionTokens)
                        .promptTokens((long) promptTokens)
                        .totalTokens((long) totalTokens)
                        .completionTokensDetails(
                                CompletionUsage.CompletionTokensDetails.builder()
                                        .reasoningTokens((long) reasoningTokens)
                                        .build())
                        .build();
        return new DefaultUsage(promptTokens, completionTokens, totalTokens, nativeUsage);
    }

    /**
     * OpenAI(-compatible): prompt tokens include the cached ones (inclusive convention); no
     * cache-write count.
     */
    static DefaultUsage openAi(int promptTokens, int completionTokens, Integer cachedTokens) {
        CompletionUsage.Builder builder =
                CompletionUsage.builder()
                        .completionTokens((long) completionTokens)
                        .promptTokens((long) promptTokens)
                        .totalTokens((long) (promptTokens + completionTokens));
        if (cachedTokens != null) {
            builder.promptTokensDetails(
                    CompletionUsage.PromptTokensDetails.builder()
                            .cachedTokens(cachedTokens.longValue())
                            .build());
        }
        return new DefaultUsage(
                promptTokens,
                completionTokens,
                promptTokens + completionTokens,
                builder.build(),
                cachedTokens == null ? null : cachedTokens.longValue(),
                null);
    }
}
