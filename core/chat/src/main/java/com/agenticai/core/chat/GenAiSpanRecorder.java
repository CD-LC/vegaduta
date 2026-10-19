package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Tags the call's span with the OTel GenAI semantic-convention attributes and the
 * Langfuse-recognized attributes ({@code langfuse.user.id}, {@code langfuse.session.id}, {@code
 * langfuse.trace.tags}, {@code langfuse.trace.metadata.*}) so a trace is filterable by tenant,
 * agent, session and cost. The span handle is the one captured on the originating thread (never a
 * re-lookup of "current"); setting attributes on an already-obtained span from another thread is
 * safe. A {@code null}/invalid span is a complete no-op - never fabricated.
 *
 * <p>Attributes for the cached-vs-uncached input split and reasoning tokens are only set when the
 * dimension was actually reported: absent is not zero (see {@link CacheTokenAccounting}). {@code
 * gen_ai.system} was renamed {@code gen_ai.provider.name} (semconv v1.37.0); {@link
 * GenAiAttributeNames} picks legacy / new / both.
 */
final class GenAiSpanRecorder implements LlmCallSink {

    private final GenAiAttributeNames genAiAttributeNames;
    private final LangfuseUserId langfuseUserId;

    GenAiSpanRecorder(GenAiAttributeNames genAiAttributeNames, LangfuseUserId langfuseUserId) {
        this.genAiAttributeNames = genAiAttributeNames;
        this.langfuseUserId = langfuseUserId;
    }

    @Override
    public void onCall(LlmCallObservation obs) {
        Span span = obs.span();
        if (span == null) {
            return;
        }
        try {
            SpanContext spanContext = span.getSpanContext();
            if (spanContext == null || !spanContext.isValid()) {
                return;
            }
            tag(span, obs);
        } catch (RuntimeException e) {
            // Tracing is a side channel - never allowed to affect the real chat turn.
        }
    }

    private void tag(Span span, LlmCallObservation obs) {
        LlmCallObservation.Attribution who = obs.attribution();
        LlmCallObservation.Tokens tokens = obs.tokens();
        UUID agentId = who.agentId();
        String sessionId = who.sessionId();
        UUID agentVersionId = who.agentVersionId();
        String promptVersion = who.promptVersion();
        String model = obs.model();
        ProviderName providerName = obs.providerName();
        BigDecimal estimatedCostUsd = tokens.estimatedCostUsd();
        CacheTokenAccounting cacheAccounting = tokens.cache();
        Long reasoningTokens = tokens.reasoning().reasoningTokens();

        for (String providerKey : genAiAttributeNames.providerKeys()) {
            span.setAttribute(
                    providerKey,
                    ProviderName.GROK == providerName
                            ? "xai"
                            : providerName.name().toLowerCase(Locale.ROOT));
        }
        span.setAttribute("gen_ai.operation.name", "chat");
        span.setAttribute("gen_ai.request.model", model);
        if (agentId != null) {
            span.setAttribute("gen_ai.agent.id", agentId.toString());
        }
        if (sessionId != null && !sessionId.isBlank()) {
            span.setAttribute("gen_ai.conversation.id", sessionId);
        }
        if (tokens.prompt() != null) {
            span.setAttribute("gen_ai.usage.input_tokens", tokens.prompt().longValue());
        }
        if (tokens.completion() != null) {
            span.setAttribute("gen_ai.usage.output_tokens", tokens.completion().longValue());
        }
        if (obs.exceptionType() != null) {
            span.setAttribute("error.type", obs.exceptionType());
        }
        if (reasoningTokens != null) {
            span.setAttribute("gen_ai.usage.reasoning.output_tokens", reasoningTokens.longValue());
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        // langfuse.user.id: by default the REAL user (Keycloak subject); falls back to the tenant
        // for system-initiated calls with no subject. app.observability.langfuse.user-id-mode can
        // instead send a salted hash or the tenant - see LangfuseUserId.
        String userId = langfuseUserId.resolve(tenantId, subject);
        if (userId != null && !userId.isBlank()) {
            span.setAttribute("langfuse.user.id", userId);
        }
        if (tenantId != null && !tenantId.isBlank()) {
            span.setAttribute("vegaduta.tenant.id", tenantId);
            span.setAttribute("langfuse.trace.metadata.tenantId", tenantId);
        }
        if (agentVersionId != null) {
            span.setAttribute("gen_ai.agent.version", agentVersionId.toString());
            span.setAttribute("langfuse.trace.metadata.agentVersionId", agentVersionId.toString());
        }
        if (promptVersion != null) {
            span.setAttribute("vegaduta.prompt.version", promptVersion);
            span.setAttribute("langfuse.trace.metadata.promptVersion", promptVersion);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            span.setAttribute("langfuse.session.id", sessionId);
        }
        if (agentId != null) {
            span.setAttribute("langfuse.trace.metadata.agentId", agentId.toString());
        }
        span.setAttribute("langfuse.trace.metadata.model", model);
        if (estimatedCostUsd != null) {
            span.setAttribute(
                    "langfuse.trace.metadata.estimatedCostUsd", estimatedCostUsd.toPlainString());
        }
        if (cacheAccounting != null && cacheAccounting.isPresent()) {
            if (cacheAccounting.cacheReadInputTokens() != null) {
                span.setAttribute(
                        "langfuse.trace.metadata.cacheReadInputTokens",
                        cacheAccounting.cacheReadInputTokens().longValue());
            }
            if (cacheAccounting.cacheWriteInputTokens() != null) {
                span.setAttribute(
                        "langfuse.trace.metadata.cacheWriteInputTokens",
                        cacheAccounting.cacheWriteInputTokens().longValue());
            }
            if (cacheAccounting.uncachedInputTokens() != null) {
                span.setAttribute(
                        "langfuse.trace.metadata.uncachedInputTokens",
                        cacheAccounting.uncachedInputTokens().longValue());
            }
            Double hitRatio = cacheAccounting.hitRatio();
            if (hitRatio != null) {
                span.setAttribute("langfuse.trace.metadata.cacheHitRatio", hitRatio.doubleValue());
            }
        }
        span.setAttribute(
                AttributeKey.stringArrayKey("langfuse.trace.tags"),
                List.of("provider:" + providerName.name()));
    }
}
