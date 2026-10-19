package com.agenticai.core.agents.gateway;

import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.chat.gateway.GatewayGovernanceAdvisor;
import com.agenticai.core.guardrails.ContentGuardrailCheckResult;
import com.agenticai.core.guardrails.ContentGuardrailEngine;
import com.agenticai.core.guardrails.ContentGuardrailEngine.GuardrailEvaluationContext;
import com.agenticai.core.guardrails.ContentGuardrailType;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Response-side PII observation for every governed {@code ChatClient} -
 * attached via the {@link GatewayGovernanceAdvisor} marker by {@code
 * LlmGatewayService#resolveClient} (BYOK/gateway clients) and {@code
 * FreeTierChatClientService} (platform-hosted-pool clients, the free-tier
 * agent-chat path). Pass-through when there is no tenant context, and
 * applied at most once per request (see {@link #APPLIED_CONTEXT_KEY}).
 *
 * <p><b>Request-side governance deliberately does NOT live here.</b> This
 * class originally also screened prompts (always-on JAILBREAK block +
 * flag-gated PII redact) - then the gap-radar merge landed {@code
 * LlmGatewayContentPolicyAdvisor} in {@code core/chat}, which owns that
 * surface properly: per-tenant FLAG/BLOCK injection policy, per-category
 * PII redaction, an audit trail, and a real admin surface ({@code
 * GET/PUT /api/tenant/llm-gateway/content-policy} + the /admin/guardrails
 * card). Running both would scan every prompt twice - and worse, this
 * advisor's always-on block would silently override a tenant's FLAG
 * choice, making the admin's configured posture lie. So the request path
 * is now theirs alone; what remains here is the one thing the policy
 * advisor doesn't do.
 *
 * <p>What it does: on the blocking {@code .call()} path only, evaluate the
 * completed response text through the shared {@link ContentGuardrailEngine}'s
 * PII check. A detection is counted ({@code llm_gateway_response_pii_detected})
 * and logged WITHOUT content - flagged, never rewritten: rebuilding a {@code
 * ChatClientResponse} is a mutation the response-moderation unification
 * should design for properly, not a regex in an advisor. Streamed responses
 * pass through untouched - buffering a token stream to scan it would defeat
 * the stream, and the agent-chat path's FAST-depth post-stream audit already
 * covers that surface ({@code AgentChatService#auditStreamedOutboundViolation}).
 * Scanner errors fail open: flagging must never break a response that
 * already succeeded.
 *
 * <p>Lives in {@code core/agents} for the same reason the original did -
 * it is the module that sees both the {@code chat} marker interface and
 * the {@code guardrails} engine.
 */
@Service
public class ResponsePiiFlagAdvisor implements GatewayGovernanceAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ResponsePiiFlagAdvisor.class);

    /** Advisor-context marker: this request's response is already being flagged further up the chain. */
    static final String APPLIED_CONTEXT_KEY = "llmGateway.responsePiiFlag.applied";

    private final ContentGuardrailEngine guardrailEngine;
    private final MeterRegistry meterRegistry;

    public ResponsePiiFlagAdvisor(
        ContentGuardrailEngine guardrailEngine,
        ObjectProvider<MeterRegistry> meterRegistry
    ) {
        this.guardrailEngine = guardrailEngine;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    @Override
    public String getName() {
        return "ResponsePiiFlagAdvisor";
    }

    @Override
    public int getOrder() {
        // This advisor only observes completed responses - it mutates and
        // blocks nothing, so its position in the chain is immaterial. Runs
        // after the request-shaping advisors on purpose.
        return 500;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // Internal/system call (no tenant) or a second copy of this advisor further
        // down the same chain: pure pass-through, so a response is flagged at most once.
        if (TenantContext.current().isEmpty()
            || (request.context() != null && request.context().containsKey(APPLIED_CONTEXT_KEY))) {
            return chain.nextCall(request);
        }
        ChatClientRequest marked = request.mutate().context(APPLIED_CONTEXT_KEY, Boolean.TRUE).build();
        ChatClientResponse response = chain.nextCall(marked);
        flagResponsePii(marked, response);
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request);
    }

    private void flagResponsePii(ChatClientRequest request, ChatClientResponse response) {
        try {
            var chatResponse = response == null ? null : response.chatResponse();
            var result = chatResponse == null ? null : chatResponse.getResult();
            var output = result == null ? null : result.getOutput();
            String text = output == null ? null : output.getText();
            if (text == null || text.isBlank()) {
                return;
            }
            UUID agentId = contextAgentId(request);
            String sessionId = contextSessionId(request);
            GuardrailEvaluationContext outCtx = GuardrailEvaluationContext.output(agentId, sessionId, null);
            ContentGuardrailCheckResult pii =
                guardrailEngine.evaluate(text, Set.of(ContentGuardrailType.PII), outCtx);
            if (pii.getSanitizedContent() != null && !pii.getSanitizedContent().equals(text) || !pii.isPassed()) {
                if (meterRegistry != null) {
                    meterRegistry.counter("llm_gateway_response_pii_detected").increment();
                }
                log.warn(
                    "Gateway flagged PII in an outbound LLM response (agent={}, session={}) - flagged, not rewritten",
                    agentId, sessionId);
            }
        } catch (RuntimeException e) {
            log.debug("Gateway response PII screen skipped: {}", e.toString());
        }
    }

    private static UUID contextAgentId(ChatClientRequest request) {
        Object value = request.context().get(LlmMetricsAdvisor.AGENT_ID_PARAM);
        return value instanceof UUID id ? id : null;
    }

    private static String contextSessionId(ChatClientRequest request) {
        Object value = request.context().get(LlmMetricsAdvisor.SESSION_ID_PARAM);
        return value instanceof String s ? s : null;
    }
}
