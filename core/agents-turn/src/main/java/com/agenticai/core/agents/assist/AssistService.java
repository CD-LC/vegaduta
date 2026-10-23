package com.agenticai.core.agents.assist;

import com.agenticai.core.agents.assist.dto.AssistCodeEditRequest;
import com.agenticai.core.agents.assist.dto.AssistRequest;
import com.agenticai.core.agents.assist.dto.AssistResponse;
import com.agenticai.core.agents.auxiliary.AuxiliaryModelResolver;
import com.agenticai.core.agents.auxiliary.AuxiliaryPurpose;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.chat.ChatClientRegistry;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Backs the "Ask AI" helper that sits next to every description/prompt field
 * in the console (system prompts, workflow/knowledge descriptions, node
 * config text, etc - see {@code AskAiHelper.js}). One tiny, non-streaming
 * completion per click: no session, no memory, no tools, no persisted
 * agent - deliberately the simplest possible use of the existing LLM
 * plumbing, reusing the provider credentials and {@link
 * ChatClientRegistry} exactly as {@code AgentChatService} does, just against
 * "whichever provider is active" instead of one specific agent's provider.
 *
 * <p>FREE-plan tenants never have a BYOK provider to resolve (the Providers
 * page isn't reachable on Free), so {@link #buildChatClient} mirrors {@code
 * AgentChatService#buildChatClientFor}'s plan branch: FREE routes through
 * {@link FreeTierChatClientService} (usage-quota checked, platform-hosted
 * pool) instead of a BYOK credential. Before this branch existed,
 * every FREE-tenant click of "Ask AI" failed outright with {@code
 * NoActiveProviderException} - this was a gap left over from when free-tier
 * hosted-pool routing was added only to the main chat path, not a deliberate
 * restriction.
 *
 * <p>The provider and model of a BYOK tenant's call come from {@link AuxiliaryModelResolver} (the tenant's
 * "assist" preference, {@code app.auxiliary-model.assist.*}, else the best credential and that provider's
 * catalog default model), never an Anthropic id passed to another provider and never the first alphabetical
 * catalog entry.
 */
@Service
public class AssistService {

    private final AuxiliaryModelResolver auxiliaryModels;
    private final ChatClientRegistry chatClientRegistry;
    private final LlmGatewayService llmGatewayService;
    private final TenantLimits tenantLimits;
    private final FreeTierChatClientService freeTierChatClientService;
    private final int defaultMaxTokens;

    /**
     * Registry key of the form-field writer's system prompt ({@code prompts/en/edge.ask_ai.system.md}); an {@code
     * edge} key because the console's on-device draft ({@code AskAiHelper}) reads the same wording from the edge bundle.
     */
    public static final String FIELD_DRAFT_KEY = "edge.ask_ai.system";

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so an owner can reword the helper's instruction; absent = the shipped text. */
    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    public AssistService(
        AuxiliaryModelResolver auxiliaryModels,
        ChatClientRegistry chatClientRegistry,
        LlmGatewayService llmGatewayService,
        TenantLimits tenantLimits,
        FreeTierChatClientService freeTierChatClientService,
        @Value("${app.agents.default-max-tokens:4096}") int defaultMaxTokens
    ) {
        this.auxiliaryModels = auxiliaryModels;
        this.chatClientRegistry = chatClientRegistry;
        this.llmGatewayService = llmGatewayService;
        this.tenantLimits = tenantLimits;
        this.freeTierChatClientService = freeTierChatClientService;
        this.defaultMaxTokens = defaultMaxTokens;
    }

    public AssistResponse draft(AssistRequest request) {
        // Low temperature (unlike a chat agent's tenant-configurable value) -
        // this helper should give the same admin roughly the same drafted
        // text for the same instruction, not creative variation each click.
        // topP=1.0 (no nucleus narrowing) - only an agent's own configured
        // value should ever tune that, not this one-off internal helper.
        ChatClient chatClient = buildChatClient();

        String system = prompts.text(FIELD_DRAFT_KEY);

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
        AuxiliaryModelResolver.Selection model = auxiliaryModels.select(AuxiliaryPurpose.ASSIST, null, background);
        return llmGatewayService.resolveClient(tenantId, new GatewayCallSpec(
            model.provider(), model.apiKey(), model.model(), defaultMaxTokens, 0.3, 1.0,
            AuxiliaryPurpose.ASSIST.featureKey()
        ));
    }
}
