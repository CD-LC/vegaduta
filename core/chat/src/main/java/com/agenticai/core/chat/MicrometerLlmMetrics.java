package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;

/**
 * Prometheus-exportable business meters for a finished call, all tagged {@code
 * tenant}/{@code provider}/{@code model}: {@code llm.calls}, {@code llm.call.errors} (provider
 * failures only - a consumer cancellation is not one), {@code llm.latency}, {@code llm.cost.usd},
 * {@code llm.output.tokens.reasoning} and {@code llm.stream.cancelled}. Provider/model are static
 * per advisor, so {@code tenant} is the only cardinality driver; it falls back to the literal
 * {@code "system"} for calls with no tenant context. The cached-input meters live in {@link
 * CacheTokenMetrics}. The tenant tag is read from {@code TenantContext} at emit time, which {@link
 * LlmCallObserver} guarantees is the originating call's tenant.
 */
final class MicrometerLlmMetrics implements LlmCallSink {

    private static final Logger log = LoggerFactory.getLogger(MicrometerLlmMetrics.class);

    private static final String NO_TENANT_TAG = "system";

    /**
     * Warn threshold for the fixed prompt prefix, in estimated tokens. The advisor is built by hand
     * in every provider factory (not a Spring bean), so it is read from the JVM property {@code
     * prompt.prefix-warn-tokens} or env {@code PROMPT_PREFIX_WARN_TOKENS}.
     */
    private static final int PREFIX_WARN_TOKENS = readWarnThreshold();

    private final MeterRegistry meterRegistry;

    MicrometerLlmMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** The tenant/provider/model tag set every meter of this family carries. */
    static Tags tags(ProviderName providerName, String model) {
        String tenantTag =
                TenantContext.current().map(TenantContext::getTenantId).orElse(NO_TENANT_TAG);
        return Tags.of("tenant", tenantTag, "provider", providerName.name(), "model", model);
    }

    @Override
    public void onCall(LlmCallObservation obs) {
        Tags tags = tags(obs.providerName(), obs.model());

        meterRegistry.counter("llm.calls", tags).increment();
        if (obs.countAsError()) {
            meterRegistry.counter("llm.call.errors", tags).increment();
        }
        meterRegistry.timer("llm.latency", tags).record(obs.latencyMs(), TimeUnit.MILLISECONDS);

        if (obs.tokens().estimatedCostUsd() != null) {
            meterRegistry
                    .counter("llm.cost.usd", tags)
                    .increment(obs.tokens().estimatedCostUsd().doubleValue());
        }

        // Reasoning tokens as their own series (absent = no series, same rule as the cache meters).
        // accounting=included: already inside completion tokens and priced there (OpenAI);
        // accounting=additional: reported outside completion and priced on top (Gemini).
        ReasoningTokenAccounting reasoning = obs.tokens().reasoning();
        if (reasoning != null && reasoning.isPresent()) {
            meterRegistry
                    .counter(
                            "llm.output.tokens.reasoning",
                            tags.and(
                                    "accounting",
                                    reasoning.includedInCompletionTokens()
                                            ? "included"
                                            : "additional"))
                    .increment(reasoning.reasoningTokens().doubleValue());
        }
        // Not a provider failure and not in llm.call.errors - routine client disconnects.
        if (obs.streamCancelled()) {
            meterRegistry.counter("llm.stream.cancelled", tags).increment();
        }
    }

    /**
     * Fixed-prefix size (system messages + tool schemas) as chars/4 tokens. Fail-open. Tagged by
     * provider and model only: a per-tenant tag would multiply series cardinality for a size
     * distribution.
     */
    void recordPrefixBudget(ChatClientRequest request, ProviderName providerName, String model) {
        try {
            int[] prefix = LlmRequestSnapshotter.prefixSize(request);
            DistributionSummary.builder("llm.prompt.prefix.tokens")
                    .description(
                            "Estimated tokens (chars/4) of the fixed per-turn prompt prefix: system"
                                    + " messages plus tool schemas")
                    .baseUnit("tokens")
                    .tags("provider", providerName.name(), "model", model)
                    .register(meterRegistry)
                    .record(prefix[1]);
            if (prefix[1] > PREFIX_WARN_TOKENS) {
                log.info(
                        "Prompt fixed prefix ~{} tokens ({} chars) for model {}, over"
                                + " prompt.prefix-warn-tokens={}",
                        prefix[1],
                        prefix[0],
                        model,
                        PREFIX_WARN_TOKENS);
            }
        } catch (RuntimeException e) {
            log.debug("prefix budget metric skipped: {}", e.toString());
        }
    }

    private static int readWarnThreshold() {
        String v =
                System.getProperty(
                        "prompt.prefix-warn-tokens", System.getenv("PROMPT_PREFIX_WARN_TOKENS"));
        try {
            return v == null || v.isBlank() ? 2500 : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 2500;
        }
    }
}
