package com.agenticai.core.chat.gateway;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Gap-radar P0 ("a real LLM gateway - PII redaction/injection screening") -
 * the actual content-inspection half of the LLM gateway seam, closing the gap
 * {@code docs/plans/LLM-GATEWAY-ARCHITECTURE-2026-09-23.md} (Section 4, item
 * 1) documents: {@link LlmGatewayService#resolveClient} sees only {@link
 * GatewayCallSpec} (provider/model/params), never the rendered prompt, so a
 * pre-call content pass could not be wired in there. This advisor hooks in at
 * the seam that DOES see the rendered prompt instead of duplicating call
 * sites: {@link GatewayGovernanceAdvisor} - the marker every governed client
 * carries (BYOK/gateway clients via {@link LlmGatewayService#resolveClient},
 * platform-hosted-pool clients - the free-tier agent-chat path - via {@code
 * FreeTierChatClientService}), so the policy applies whichever provider or
 * call site asked for the client, including normal agent chat. (It
 * deliberately does NOT use {@code DefaultAgentAdvisor}: that list is
 * consumed by only some provider factories, so a rule registered there would
 * silently miss the rest.) Being a plain {@code @Component} implementing the
 * marker is the whole integration.
 *
 * <p>Two independent, per-tenant-configurable checks (see {@link
 * LlmGatewayContentPolicy} for defaults - injection screening is ON in FLAG
 * mode for a tenant with no saved policy, PII redaction is opt-in):
 * <ol>
 *   <li><b>PII redaction</b> - every {@link UserMessage} in the prompt is
 *       passed through {@link LlmGatewayPiiRedactor}, which replaces matched
 *       spans with a category placeholder rather than rejecting the turn.</li>
 *   <li><b>Prompt-injection screening</b> - the current turn's {@link
 *       UserMessage}s (see {@link #currentTurnStart}) are screened by {@link
 *       LlmGatewayPromptInjectionScreener} BEFORE the (possibly redacted)
 *       request is forwarded down the chain to the model - i.e. before this
 *       inbound content can influence a call that also carries the agent's
 *       system-privileged instructions in the same prompt. A flagged request
 *       is audit-logged once (via {@link AuditLogService}, the same durable
 *       trail {@code InboundSafetyScanService} already uses for channel-layer
 *       injection flags); {@link LlmGatewayContentPolicyAction#BLOCK}
 *       additionally rejects the call by throwing {@link
 *       LlmGatewayContentPolicyViolationException} before {@code
 *       chain.nextCall}/{@code nextStream} is ever invoked, so no provider
 *       network call is made for a blocked turn.</li>
 * </ol>
 *
 * <p><b>Applied at most once per request.</b> Once the policy has run, the
 * request carries {@link #APPLIED_CONTEXT_KEY} in its advisor context (never
 * sent to the provider); a second instance of this advisor further down the
 * same chain - e.g. a client that somehow picked up the governance advisors
 * twice - sees the marker and passes through, so nothing is screened,
 * audited or redacted twice.
 *
 * <p><b>Why a heuristic screen, not an LLM-judge call.</b> An LLM-judge check
 * would itself be an outbound LLM call that would need to funnel through this
 * same gateway - adding a full model round-trip of latency (and cost) to
 * every gateway call just to screen it is a worse default than a cheap
 * pattern pre-filter, the same "cheap check before the expensive one" shape
 * {@link LlmGatewayRateLimiterService}/{@link LlmGatewayBudgetService} already
 * use ahead of the real provider call. An LLM-judge escalation for messages
 * the heuristic does not confidently clear is a real, flagged follow-up, not
 * built here.
 *
 * <p><b>Only {@link UserMessage} content is inspected/redacted</b> -
 * {@code SystemMessage}/{@code AssistantMessage}/tool messages are left
 * untouched. That is deliberate for both checks: injection screening exists
 * specifically for INBOUND user content (the system prompt is
 * platform/agent-authored, not attacker-controlled), and PII redaction here
 * targets what a user pastes into a turn, not the agent's own instructions.
 *
 * <p>Runs at order {@value #ORDER} - earlier than every other advisor in this
 * module ({@code SystemMessageMergingAdvisor} at -1000, {@code
 * LlmMetricsAdvisor} at 0) and the hosted pool's usage-tracking advisors
 * (order 0), so a flagged/blocked call never reaches metrics logging or
 * provider dispatch, and any later advisor that inspects the request sees the
 * redacted form.
 */
@Component
public class LlmGatewayContentPolicyAdvisor implements GatewayGovernanceAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayContentPolicyAdvisor.class);

    static final int ORDER = -1500;

    /** Advisor-context marker set once the policy has been applied to a request - see class javadoc. */
    static final String APPLIED_CONTEXT_KEY = "llmGateway.contentPolicy.applied";

    private static final String AUDIT_ACTOR = "system:llm-gateway-content-policy";
    private static final String AUDIT_ACTION_FLAGGED = "llm.gateway.injection_flag";
    private static final String AUDIT_ACTION_BLOCKED = "llm.gateway.injection_block";
    private static final String AUDIT_TARGET_TYPE = "tenant";

    private final LlmGatewayContentPolicyService policyService;
    private final AuditLogService auditLogService;

    public LlmGatewayContentPolicyAdvisor(LlmGatewayContentPolicyService policyService, AuditLogService auditLogService) {
        this.policyService = policyService;
        this.auditLogService = auditLogService;
    }

    @Override
    public String getName() {
        return "LlmGatewayContentPolicyAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(applyPolicy(request));
    }

    /**
     * Unlike {@link #adviseCall}, a thrown {@link LlmGatewayContentPolicyViolationException}
     * from {@link #applyPolicy} must not escape this method synchronously -
     * {@code streamAdvisorChain.nextStream(...)} is expected to signal
     * failure reactively, same reasoning {@link
     * com.agenticai.core.chat.LlmMetricsAdvisor}'s own stream path documents
     * for cross-thread concerns - so a BLOCK verdict is surfaced as {@link
     * Flux#error(Throwable)} instead of a synchronous throw.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest processed;
        try {
            processed = applyPolicy(request);
        } catch (RuntimeException blocked) {
            return Flux.error(blocked);
        }
        return chain.nextStream(processed);
    }

    /**
     * @return {@code request} unchanged when there is no tenant context (an
     *     internal/system call), the policy was already applied further up
     *     this chain, or both master switches are off; otherwise a copy
     *     carrying {@link #APPLIED_CONTEXT_KEY} whose {@link UserMessage}s
     *     carry redacted text (when PII redaction is on and something matched).
     * @throws LlmGatewayContentPolicyViolationException if injection
     *     screening is on, set to {@link LlmGatewayContentPolicyAction#BLOCK},
     *     and a current-turn {@link UserMessage} is flagged.
     */
    private ChatClientRequest applyPolicy(ChatClientRequest request) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            return request;
        }
        if (request.context() != null && request.context().containsKey(APPLIED_CONTEXT_KEY)) {
            return request;
        }
        LlmGatewayContentPolicy policy = policyService.resolve(tenantId);
        if (!policy.isPiiRedactionEnabled() && !policy.isInjectionScreeningEnabled()) {
            return request;
        }

        Prompt prompt = request.prompt();
        List<Message> messages = prompt.getInstructions();

        if (policy.isInjectionScreeningEnabled()) {
            for (int i = currentTurnStart(messages); i < messages.size(); i++) {
                if (!(messages.get(i) instanceof UserMessage userMessage)) {
                    continue;
                }
                LlmGatewayPromptInjectionScreener.Verdict verdict =
                    LlmGatewayPromptInjectionScreener.screen(userMessage.getText());
                if (verdict.suspect()) {
                    // Throws when this tenant's policy is BLOCK - see handleFlaggedInjection.
                    // One audit row per request, not per matching message.
                    handleFlaggedInjection(tenantId, policy, verdict.reason());
                    break;
                }
            }
        }

        List<Message> rewritten = null;
        if (policy.isPiiRedactionEnabled()) {
            Set<LlmGatewayPiiCategory> categories = policy.enabledPiiCategories();
            for (int i = 0; i < messages.size(); i++) {
                if (!(messages.get(i) instanceof UserMessage userMessage)) {
                    continue;
                }
                String text = userMessage.getText();
                String redacted = LlmGatewayPiiRedactor.redact(text, categories);
                if (!Objects.equals(redacted, text)) {
                    if (rewritten == null) {
                        rewritten = new ArrayList<>(messages);
                    }
                    rewritten.set(i, userMessage.mutate().text(redacted).build());
                }
            }
        }

        ChatClientRequest.Builder out = request.mutate().context(APPLIED_CONTEXT_KEY, Boolean.TRUE);
        if (rewritten != null) {
            out.prompt(new Prompt(rewritten, prompt.getOptions()));
        }
        return out.build();
    }

    /**
     * Index of the first message of the current turn: everything after the
     * last {@link AssistantMessage}. Earlier {@link UserMessage}s are
     * replayed conversation history that was already screened when it was
     * the current turn - re-screening it on every later turn would re-audit
     * the same flagged message once per turn for the rest of the session.
     * A single-shot prompt (no assistant message) is screened in full.
     * PII redaction is NOT limited this way: history is re-sent to the
     * provider each turn, so it must be redacted each time.
     */
    static int currentTurnStart(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * Always audit-logs the flag first (best-effort - an audit-write failure
     * must not itself block or fail-open a request differently than the
     * policy already dictates), then throws for {@code BLOCK} tenants only.
     */
    private void handleFlaggedInjection(String tenantId, LlmGatewayContentPolicy policy, String reason) {
        boolean block = policy.getInjectionScreeningAction() == LlmGatewayContentPolicyAction.BLOCK;
        try {
            auditLogService.record(
                AUDIT_ACTOR,
                block ? AUDIT_ACTION_BLOCKED : AUDIT_ACTION_FLAGGED,
                AUDIT_TARGET_TYPE,
                tenantId,
                AuditOutcome.FAILURE,
                "LLM gateway inbound content flagged (" + (block ? "BLOCK" : "FLAG") + "): " + reason
            );
        } catch (RuntimeException e) {
            log.warn("LLM gateway content-policy audit write failed for tenant {} - continuing", tenantId, e);
        }
        if (block) {
            throw new LlmGatewayContentPolicyViolationException(
                "This request was blocked by the tenant's prompt-injection screening policy: " + reason);
        }
        log.info("LLM gateway flagged suspected prompt injection for tenant {} (mode FLAG, call proceeds): {}", tenantId, reason);
    }
}
