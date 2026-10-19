package com.agenticai.core.agents.assist;

import com.agenticai.core.agents.assist.dto.AssistCodeEditRequest;
import com.agenticai.core.agents.assist.dto.AssistRequest;
import com.agenticai.core.agents.assist.dto.AssistResponse;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.chat.ChatClientRegistry;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogEmptyException;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Backs the "Ask AI" helper that sits next to every description/prompt field
 * in the console (system prompts, workflow/knowledge descriptions, node
 * config text, etc - see {@code AskAiHelper.js}). One tiny, non-streaming
 * completion per click: no session, no memory, no tools, no persisted
 * agent - deliberately the simplest possible use of the existing LLM
 * plumbing, reusing {@link ProviderCredentialResolver} and {@link
 * ChatClientRegistry} exactly as {@code AgentChatService} does, just against
 * "whichever provider is active" instead of one specific agent's provider.
 *
 * <p>FREE-plan tenants never have a BYOK provider to resolve (the Providers
 * page isn't reachable on Free), so {@link #buildChatClient} mirrors {@code
 * AgentChatService#buildChatClientFor}'s plan branch: FREE routes through
 * {@link FreeTierChatClientService} (usage-quota checked, platform-hosted
 * pool) instead of {@link #credentialResolver}. Before this branch existed,
 * every FREE-tenant click of "Ask AI" failed outright with {@code
 * NoActiveProviderException} - this was a gap left over from when free-tier
 * hosted-pool routing was added only to the main chat path, not a deliberate
 * restriction.
 *
 * <p>{@code defaultModel} is an Anthropic model id ({@code
 * app.agents.default-model}) - fine as a fallback, but {@link
 * #credentialResolver}'s active provider is not always Anthropic (any of
 * ANTHROPIC/GEMINI/GROK/OPENROUTER/SARVAM/META/... can be the one active BYOK
 * credential). Passing the Anthropic id straight through to a non-Anthropic
 * provider produced a 404 "model not found" at the gateway (reproduced via
 * Gemini). {@link #buildChatClient} now looks up a real model for whichever
 * provider actually resolved via {@link ProviderModelCatalogService}, only
 * falling back to {@code defaultModel} if that provider's catalog is
 * unexpectedly empty.
 */
@Service
public class AssistService {

    private final ProviderCredentialResolver credentialResolver;
    private final ChatClientRegistry chatClientRegistry;
    private final LlmGatewayService llmGatewayService;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final TenantLimits tenantLimits;
    private final FreeTierChatClientService freeTierChatClientService;
    private final String defaultModel;
    private final int defaultMaxTokens;

    public AssistService(
        ProviderCredentialResolver credentialResolver,
        ChatClientRegistry chatClientRegistry,
        LlmGatewayService llmGatewayService,
        ProviderModelCatalogService providerModelCatalogService,
        TenantLimits tenantLimits,
        FreeTierChatClientService freeTierChatClientService,
        @Value("${app.agents.default-model}") String defaultModel,
        @Value("${app.agents.default-max-tokens:1024}") int defaultMaxTokens
    ) {
        this.credentialResolver = credentialResolver;
        this.chatClientRegistry = chatClientRegistry;
        this.llmGatewayService = llmGatewayService;
        this.providerModelCatalogService = providerModelCatalogService;
        this.tenantLimits = tenantLimits;
        this.freeTierChatClientService = freeTierChatClientService;
        this.defaultModel = defaultModel;
        this.defaultMaxTokens = defaultMaxTokens;
    }

    public AssistResponse draft(AssistRequest request) {
        // Low temperature (unlike a chat agent's tenant-configurable value) -
        // this helper should give the same admin roughly the same drafted
        // text for the same instruction, not creative variation each click.
        // topP=1.0 (no nucleus narrowing) - only an agent's own configured
        // value should ever tune that, not this one-off internal helper.
        ChatClient chatClient = buildChatClient();

        String system = """
            You help an admin of an AI agent SaaS platform write clear,
            effective text for one specific form field. You will be told the
            field's purpose, the admin's rough instruction, and (maybe) what
            they've already typed. Reply with ONLY the finished text for that
            field - no preamble, no markdown code fences, no explanation, no
            quotation marks around it. If a current draft is given, improve or
            extend it rather than ignoring it. Keep length appropriate to the
            field (a system prompt can be a paragraph or two; a short
            description field should be one or two sentences).
            """;

        String user = "Field: " + request.fieldLabel()
            + "\nInstruction: " + request.instruction()
            + (request.currentValue() != null && !request.currentValue().isBlank()
                ? "\nCurrent draft:\n" + request.currentValue()
                : "");

        String suggestion = chatClient.prompt().system(system).user(user).call().content();
        return new AssistResponse(suggestion == null ? "" : suggestion.trim());
    }

    /**
     * One-off completion with caller-chosen system+user prompts (Tier-3:
     * {@code AgentGenerationService}'s structured spec call uses this) -
     * the same client/plumbing {@link #draft} resolves (FREE-plan pool
     * routing, per-provider model pick, low temperature), just without
     * {@code draft}'s form-field-shaped prompt. Returns the trimmed reply;
     * parsing/validation is the caller's job.
     */
    /**
     * The studio's code-edit turn: the caller's own system prompt (the edit
     * block contract) and the app + request as the user turn, with no
     * form-field framing around either. Same client resolution as
     * {@link #draft}.
     */
    public AssistResponse codeEdit(AssistCodeEditRequest request) {
        return new AssistResponse(complete(request.system(), request.prompt()));
    }

    public String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, false);
    }

    /**
     * {@link #complete(String, String)} for a caller no person is waiting on (the hourly improvement sweep).
     * A provider key the workspace's provider refused recently is skipped, and when nothing else is connected
     * {@link com.agenticai.core.providers.ProviderCoolingDownException} is thrown instead of calling the refused
     * provider again - see {@link ProviderCredentialResolver#resolveBest}.
     */
    public String completeInBackground(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, true);
    }

    private String complete(String systemPrompt, String userPrompt, boolean background) {
        ChatClient chatClient = buildChatClient(background);
        String content = chatClient.prompt().system(systemPrompt).user(userPrompt).call().content();
        return content == null ? "" : content.trim();
    }

    private ChatClient buildChatClient() {
        return buildChatClient(false);
    }

    private ChatClient buildChatClient(boolean background) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        // usesPlatformPool (not plan == FREE): a PREMIUM/UNLIMITED tenant with no
        // BYOK key of its own also runs on the hosted pool; the FREE-only check
        // sent those tenants to resolveAnyActiveKey() -> NoActiveProviderException -> 400.
        if (tenantId != null) {
            TenantPlan plan = tenantLimits.plan(tenantId);
            if (tenantLimits.usesPlatformPool(tenantId, plan)) {
                return freeTierChatClientService.buildClientForFreeTenant(tenantId, plan, defaultMaxTokens, 0.3, 1.0);
            }
        }
        ProviderCredentialResolver.ResolvedProvider active = credentialResolver.resolveBest(background);
        String model = resolveModelFor(active.providerName());
        return llmGatewayService.resolveClient(tenantId, new GatewayCallSpec(
            active.providerName(), active.apiKey(), model, defaultMaxTokens, 0.3, 1.0
        ));
    }

    /**
     * Picks a real model id for {@code provider} via {@link
     * ProviderModelCatalogService#defaultModelFor} - the same explicit
     * per-provider preference new agents get - instead of always sending the
     * Anthropic-only {@link #defaultModel}. NOT the catalog's first
     * alphabetical entry: that accident of sort order could be a retired or
     * non-chat model (see AgentService#resolveDefaultModelFor). Falls back
     * to {@link #defaultModel} for Anthropic only if the catalog is
     * unexpectedly empty (e.g. a transient refresh failure).
     */
    private String resolveModelFor(ProviderName provider) {
        java.util.Optional<String> picked = providerModelCatalogService.defaultModelFor(provider);
        if (picked.isPresent()) {
            return picked.get();
        }
        if (provider == ProviderName.ANTHROPIC) {
            return defaultModel;
        }
        throw new ProviderModelCatalogEmptyException(provider);
    }
}
