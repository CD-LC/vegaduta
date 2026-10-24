package com.agenticai.core.agents.context;

import com.agenticai.core.agents.auxiliary.AuxiliaryModelResolver;
import com.agenticai.core.agents.auxiliary.AuxiliaryPurpose;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The LLM half of auto-compaction: folds the conversation turns being dropped from the prompt (and any
 * earlier compaction summary) into one new summary, with the cheap-model client pattern the other
 * background helpers use ({@code SessionSummaryService}, {@code AssistService}): platform-pool tenants go
 * through {@link FreeTierChatClientService}, BYOK tenants through their own credential via the gateway,
 * {@code temperature 0}, a small {@code maxTokens}. The call is attributed to the agent/session through
 * {@link LlmMetricsAdvisor}'s request params, so its cost lands in {@code llm_call_log} like every other
 * model call (and, for a FREE tenant, consumes one message unit exactly as the session summariser does).
 *
 * <h2>Bounded and fail-open</h2>
 * The call runs on a virtual thread with the caller's {@link TenantContext} re-applied (the tool loop has
 * none) and is abandoned after {@code summary-timeout-ms}; any failure - no provider, empty catalog,
 * budget refusal, timeout, a blank completion - yields {@link Optional#empty()}, which the engine treats
 * as "skip compaction". Nothing is ever deleted on this path: compaction only changes the prompt view.
 *
 * <p>{@code core/agents} cannot import {@code core/quality}'s {@code JudgeChatClientFactory} (the module
 * graph runs quality -> agents), so the client-building branch is the same deliberate copy
 * {@code SessionSummaryService} documents, not a shared bean.
 */
@Service
public class LlmCompactionSummarizer {

    private static final Logger log = LoggerFactory.getLogger(LlmCompactionSummarizer.class);

    /** Virtual-thread-per-task: a summary call is pure I/O wait and must not starve a pooled executor. */
    private static final ExecutorService EXECUTOR = TenantAwareExecutors.newVirtualThreadPerTaskExecutor("llm-compaction-summarizer-");

    /** Registry key of the compaction summarizer's system prompt (default text: {@code prompts/en/summarizer.compaction.md}). */
    static final String PROMPT_KEY = "summarizer.compaction";

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so a tenant or agent override of {@link #PROMPT_KEY} applies; absent = shipped text. */
    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    private final AuxiliaryModelResolver auxiliaryModels;
    private final LlmGatewayService llmGatewayService;
    private final TenantLimits tenantLimits;
    private final FreeTierChatClientService freeTierChatClientService;
    private final int maxTokens;
    private final long timeoutMillis;
    private final int maxPromptChars;
    private final int maxTurnExcerptChars;

    public LlmCompactionSummarizer(
        AuxiliaryModelResolver auxiliaryModels,
        LlmGatewayService llmGatewayService,
        TenantLimits tenantLimits,
        FreeTierChatClientService freeTierChatClientService,
        @Value("${app.context.compaction.summary-max-tokens:800}") int maxTokens,
        @Value("${app.context.compaction.summary-timeout-ms:20000}") long timeoutMillis,
        @Value("${app.context.compaction.summary-input-chars:24000}") int maxPromptChars,
        @Value("${app.context.compaction.summary-turn-excerpt-chars:2000}") int maxTurnExcerptChars
    ) {
        this.auxiliaryModels = auxiliaryModels;
        this.llmGatewayService = llmGatewayService;
        this.tenantLimits = tenantLimits;
        this.freeTierChatClientService = freeTierChatClientService;
        this.maxTokens = Math.max(maxTokens, 64);
        this.timeoutMillis = Math.max(timeoutMillis, 1_000L);
        this.maxPromptChars = Math.max(maxPromptChars, 2_000);
        this.maxTurnExcerptChars = Math.max(maxTurnExcerptChars, 200);
    }

    /**
     * Summarises {@code turns} (oldest first) into one text, or empty on any failure or timeout. Never
     * throws.
     *
     * @param tenant the caller's tenant snapshot, taken on a thread that had one; empty means skip (a
     *     missing context would resolve credentials and plan against the wrong schema)
     * @param agentId / sessionId only for cost attribution; may be null
     */
    public Optional<String> summarize(
        Optional<TenantContext> tenant, UUID agentId, String sessionId, String existingSummary, List<Message> turns
    ) {
        if (tenant == null || tenant.isEmpty() || turns == null || turns.isEmpty()) {
            return Optional.empty();
        }
        List<Message> snapshot = List.copyOf(turns);
        CompletableFuture<Optional<String>> future = CompletableFuture.supplyAsync(
            TenantScope.of(tenant).wrapSupplier(() -> doSummarize(agentId, sessionId, existingSummary, snapshot)),
            EXECUTOR);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Context compaction summary timed out after {} ms - keeping the original turns", timeoutMillis);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Context compaction summary failed - keeping the original turns: {}", e.toString());
            return Optional.empty();
        }
    }

    private Optional<String> doSummarize(UUID agentId, String sessionId, String existingSummary, List<Message> turns) {
        ChatClient client = buildChatClient(agentId);
        String text = client.prompt()
            .system(prompts.text(PROMPT_KEY, agentId))
            .user(buildPrompt(existingSummary, turns))
            .advisors(a -> {
                if (agentId != null) {
                    a.param(LlmMetricsAdvisor.AGENT_ID_PARAM, agentId);
                }
                if (sessionId != null) {
                    a.param(LlmMetricsAdvisor.SESSION_ID_PARAM, sessionId);
                }
            })
            .call()
            .content();
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(text.strip());
    }

    /**
     * Renders the request newest-first into the character budget and reverses it back, so when the batch is
     * too large it is the OLDEST turns that are cut (an earlier summary, if any, already covers them best).
     */
    String buildPrompt(String existingSummary, List<Message> turns) {
        StringBuilder head = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            head.append("Earlier summary of everything before these turns:\n").append(existingSummary).append("\n\n");
        } else {
            head.append("There is no earlier summary - these are the oldest turns.\n\n");
        }
        head.append("Turns being compacted (oldest first):\n");
        int budget = Math.max(0, maxPromptChars - head.length());
        List<String> rendered = new ArrayList<>();
        int used = 0;
        int omitted = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            String line = render(turns.get(i));
            if (!rendered.isEmpty() && used + line.length() > budget) {
                omitted = i + 1;
                break;
            }
            rendered.add(line);
            used += line.length();
        }
        Collections.reverse(rendered);
        StringBuilder body = new StringBuilder(head);
        if (omitted > 0) {
            body.append("[").append(omitted).append(" older turn(s) omitted for length]\n");
        }
        rendered.forEach(body::append);
        return body.toString();
    }

    private String render(Message message) {
        String content = message.getText() == null ? "" : message.getText();
        if (content.length() > maxTurnExcerptChars) {
            content = content.substring(0, maxTurnExcerptChars) + "...";
        }
        String role = message.getMessageType() == MessageType.USER ? "USER" : "ASSISTANT";
        return role + ": " + content + "\n";
    }

    /**
     * Platform pool for pool tenants, else the tenant's own credential on the model {@link AuxiliaryModelResolver}
     * chooses for compaction (the tenant's preference, {@code app.auxiliary-model.compaction.*}, the agent's own
     * model, the provider's catalog default; never the first alphabetical catalog entry).
     */
    private ChatClient buildChatClient(UUID agentId) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : null;
        if (tenantLimits.usesPlatformPool(tenantId, plan)) {
            return freeTierChatClientService.buildClientForFreeTenant(tenantId, plan, maxTokens, 0.0, 1.0);
        }
        AuxiliaryModelResolver.Selection model = auxiliaryModels.select(AuxiliaryPurpose.COMPACTION, agentId, false);
        return llmGatewayService.resolveClient(tenantId, new GatewayCallSpec(
            model.provider(), model.apiKey(), model.model(), maxTokens, 0.0, 1.0, AuxiliaryPurpose.COMPACTION.featureKey()
        ));
    }
}
