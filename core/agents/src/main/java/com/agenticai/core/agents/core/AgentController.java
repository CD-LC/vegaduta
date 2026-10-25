package com.agenticai.core.agents.core;

import com.agenticai.core.agents.dto.AgentAutonomyTierUpdateRequest;
import com.agenticai.core.agents.dto.AgentBiometricConsentToggleRequest;
import com.agenticai.core.agents.dto.AgentCapabilityResponse;
import com.agenticai.core.agents.dto.AgentCapabilityUpdateRequest;
import com.agenticai.core.agents.dto.AgentCodeIntelRepoAccessUpdateRequest;
import com.agenticai.core.agents.dto.AgentCollaboratorsUpdateRequest;
import com.agenticai.core.agents.dto.AgentCostRoutingUpdateRequest;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentEdgeRoutingPolicyUpdateRequest;
import com.agenticai.core.agents.dto.AgentExperienceModeUpdateRequest;
import com.agenticai.core.agents.dto.AgentFallbackUpdateRequest;
import com.agenticai.core.agents.dto.AgentGenerationParamsUpdateRequest;
import com.agenticai.core.agents.dto.AgentKnowledgeAccessUpdateRequest;
import com.agenticai.core.agents.dto.AgentKnowledgeBaseToggleRequest;
import com.agenticai.core.agents.dto.AgentKnowledgeWriteAccessResponse;
import com.agenticai.core.agents.dto.AgentKnowledgeWriteAccessUpdateRequest;
import com.agenticai.core.agents.dto.AgentModelUpdateRequest;
import com.agenticai.core.agents.dto.AgentPromptUpdateRequest;
import com.agenticai.core.agents.dto.AgentReasoningDepthUpdateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.dto.AgentResponseCachingToggleRequest;
import com.agenticai.core.agents.dto.AgentShareMemoryToggleRequest;
import com.agenticai.core.agents.dto.AgentTierUpdateRequest;
import com.agenticai.core.agents.dto.AgentToolAccessUpdateRequest;
import com.agenticai.core.agents.dto.AgentTuningModeUpdateRequest;
import com.agenticai.core.agents.dto.PromptVersionResponse;
import com.agenticai.core.agents.tools.AgentToolPort;
import com.agenticai.core.agents.tools.ToolDescriptor;
import com.agenticai.core.agents.tools.classification.TrifectaStatusResponse;
import com.agenticai.core.knowledge.KnowledgeCollectionService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.tenancy.ListPaging;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    public static final String SESSION_ID_HEADER = "X-Session-Id";

    /**
     * Marker prefixed to a synthetic final SSE chunk when the chat stream ({@code
     * AgentChatStreamController#chat}, {@code core/agents-turn}) dies mid-stream (a provider
     * failure after tokens already flowed - HTTP status is already committed at that point, see
     * {@code AgentChatService#streamFast}'s javadoc). Without this, the connection just closed with
     * no signal (Chrome surfaces the truncated response as {@code net::ERR_QUIC_PROTOCOL_ERROR});
     * the frontend (web/app/lib/api.ts's {@code streamChat}) recognizes this exact literal and
     * turns it into a visible error instead of literal text. Namespaced with a random-looking
     * suffix so it can't realistically collide with real streamed model output. Deliberately
     * applied ONLY at the web SSE endpoints, not inside {@code AgentChatService#chat} itself - that
     * method is also the entry point for every omnichannel webhook and {@code
     * VoiceCallController#turn}'s {@code collectList()}, which must never see this literal folded
     * into a customer-facing message.
     */
    // public: AgentChatStreamResponder, AgentShareController and the AG-UI/A2A adapters
    // reuse this exact marker (with AgentChatStreamResponder#midStreamFailureMessage) for
    // their own streams, so a mid-stream failure renders identically everywhere - two
    // divergent marker strings would be a real bug. It stays on this kernel class so the
    // modules that only read the marker do not need core/agents-turn.
    public static final String STREAM_ERROR_MARKER = "@@AGENT_CHAT_STREAM_ERROR_9f2b1c@@";

    private final AgentService agentService;
    private final AgentToolPort agentToolProvider;

    /**
     * Validates knowledge-access collection ids up front (unknown id -> 404, not an FK-violation
     * 500).
     */
    private final KnowledgeCollectionService knowledgeCollectionService;

    /**
     * The chat endpoints that used to live here are in {@code core/agents-turn} (ADR-0023): the
     * chat SSE stream in {@code AgentChatStreamController}, and turn cancel, rewind, attachment
     * upload, tool-call history and reliability in {@code AgentChatSessionController}.
     */
    public AgentController(
            AgentService agentService,
            AgentToolPort agentToolProvider,
            KnowledgeCollectionService knowledgeCollectionService) {
        this.agentService = agentService;
        this.agentToolProvider = agentToolProvider;
        this.knowledgeCollectionService = knowledgeCollectionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<AgentResponse> create(@Valid @RequestBody AgentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(agentService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<AgentResponse>> list(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        // listAll applies the per-user visibility filter, so the window is cut after it and the
        // total is exactly what this caller can page through. No params = the whole list.
        return ListPaging.window(agentService.listAll(), limit, offset, 50, 200);
    }

    @GetMapping("/{agentId}")
    public AgentResponse get(@PathVariable UUID agentId) {
        return agentService.get(agentId);
    }

    /**
     * Subscription Plan &amp; GTM Strategy doc, Section 4, Decision 2 - a FREE tenant's only way to
     * change a locked (24h+) agent: duplicate it into a fresh, editable copy. Available regardless
     * of plan (see {@link AgentService#duplicate}'s javadoc). Consumes a slot under the same
     * agent-count cap {@link #create} enforces.
     */
    @PostMapping("/{agentId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<AgentResponse> duplicate(@PathVariable UUID agentId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(agentService.duplicate(agentId));
    }

    /** Stage 6: attach/detach this agent's knowledge base (search_knowledge_base tool). */
    @PatchMapping("/{agentId}/knowledge-base")
    public AgentResponse setKnowledgeBaseEnabled(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentKnowledgeBaseToggleRequest request) {
        return agentService.setKnowledgeBaseEnabled(agentId, request.enabled());
    }

    /**
     * The plug-and-play capability catalog - every {@link
     * com.agenticai.core.agents.capability.AgentCapability} bean that applies to this agent, with
     * its current on/off state and server-resolved availability. This pair of endpoints replaced
     * the per-provider {@code PATCH .../sarvam|e2b|xai-server-tools|code-workspace} endpoints (and
     * the workbench's matching provider-specific sections): a new provider or platform capability
     * now plugs in with only a capability bean - no new endpoint, column, or frontend section. The
     * catalog itself is agent-aware: {@code appliesTo} filtering (e.g. xAI server tools only
     * appearing for GROK-provider agents) happens server-side.
     */
    @GetMapping("/{agentId}/capabilities")
    public List<AgentCapabilityResponse> listCapabilities(@PathVariable UUID agentId) {
        return agentService.listCapabilities(agentId);
    }

    /**
     * The single capability write path - {@code {enabled}} body, same shape the removed
     * per-provider toggle endpoints all shared. Unknown or non-applicable keys are rejected by
     * {@link AgentService#setCapabilityEnabled} rather than silently ignored.
     */
    @PatchMapping("/{agentId}/capabilities/{capabilityKey}")
    public AgentResponse setCapabilityEnabled(
            @PathVariable UUID agentId,
            @PathVariable String capabilityKey,
            @Valid @RequestBody AgentCapabilityUpdateRequest request) {
        return agentService.setCapabilityEnabled(agentId, capabilityKey, request.enabled());
    }

    /**
     * Migration V103 - opt-in per-agent repo allow-list for the code-intel write/execute tools,
     * same shape as {@link #setKnowledgeAccess}. Empty/ null clears the allow-list back to deny-all
     * (the opposite of {@link #setKnowledgeAccess}'s "empty means unrestricted") - see {@code
     * Agent.allowedCodeIntelRepoIds}' javadoc. Only matters once the {@code code-workspace}
     * capability is on (see {@link #setCapabilityEnabled}).
     */
    @PatchMapping("/{agentId}/code-intel-repo-access")
    public AgentResponse setCodeIntelRepoAccess(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentCodeIntelRepoAccessUpdateRequest request) {
        return agentService.setAllowedCodeIntelRepoIds(agentId, request.allowedCodeIntelRepoIds());
    }

    /**
     * Master Roadmap 2026 item #18 (cost optimization) - opt this agent in or out of exact-match
     * LLM response caching. Same shape as {@link #setCapabilityEnabled}/{@link
     * #setKnowledgeBaseEnabled}.
     */
    @PatchMapping("/{agentId}/response-caching")
    public AgentResponse setResponseCachingEnabled(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentResponseCachingToggleRequest request) {
        return agentService.setResponseCachingEnabled(agentId, request.enabled());
    }

    /**
     * Gated semantic (embedding-similarity) response caching - a separate, stronger opt-in than
     * {@link #setResponseCachingEnabled} above. Reuses the same {@link
     * AgentResponseCachingToggleRequest} body ({@code {enabled}}). See {@link
     * com.agenticai.core.agents.turn.cache.SemanticResponseCacheService}.
     */
    @PatchMapping("/{agentId}/semantic-response-caching")
    public AgentResponse setSemanticResponseCachingEnabled(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentResponseCachingToggleRequest request) {
        return agentService.setSemanticResponseCachingEnabled(agentId, request.enabled());
    }

    /**
     * Shared cross-agent memory tier (#847) - attach/detach this agent from the tenant-wide shared
     * memory pool (both contributing via remember_shared_fact and automatic recall). Same shape as
     * {@link #setCapabilityEnabled}/{@link #setKnowledgeBaseEnabled}.
     */
    @PatchMapping("/{agentId}/share-memory")
    public AgentResponse setShareMemoryWithTenant(
            @PathVariable UUID agentId, @Valid @RequestBody AgentShareMemoryToggleRequest request) {
        return agentService.setShareMemoryWithTenant(agentId, request.enabled());
    }

    /**
     * Face recognition (2026-08-06) - the explicit consent gate. Same shape as {@link
     * #setShareMemoryWithTenant} above; see {@code Agent.biometricConsentEnabled}'s javadoc for why
     * this flag exists separately from every other per-agent opt-in. Gates both {@code
     * FaceEnrollmentController}'s enroll/list endpoints and whether the web Playground's camera
     * panel ever runs on-device face matching for this agent.
     */
    @PatchMapping("/{agentId}/biometric-consent")
    public AgentResponse setBiometricConsentEnabled(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentBiometricConsentToggleRequest request) {
        return agentService.setBiometricConsentEnabled(agentId, request.enabled());
    }

    /**
     * Autonomy/Guardrails Strategy Stage A - opt-in per-agent tool allow-list (see {@code
     * Agent.allowedToolNames}'s javadoc for why this is opt-in, not a default-deny). {@link
     * #listAvailableTools} below is the dropdown picker this javadoc used to call out as future
     * work - exact tool name strings from there always round-trip correctly here.
     */
    @PatchMapping("/{agentId}/tool-access")
    public ResponseEntity<AgentResponse> setToolAccess(
            @PathVariable UUID agentId, @Valid @RequestBody AgentToolAccessUpdateRequest request) {
        // Soft-check against the agent's currently-resolvable tool set (logged, never rejected).
        ToolAllowListCheck.warnOnUnknownNames(agentToolProvider, agentId, request.allowedToolNames());
        AgentResponse saved =
                agentService.setAllowedToolNamesEnforced(agentId, request.allowedToolNames());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        // Surface the lethal-trifecta outcome to the UI on the save itself (header values are
        // ASCII-safe: newlines stripped). A block-mode rejection never gets here: it is the 400
        // above.
        TrifectaStatusResponse trifecta = agentService.trifectaStatus(agentId);
        String warning = trifecta.warning();
        if (warning != null) {
            response.header(TOOL_ACCESS_WARNING_HEADER, warning.replaceAll("[\r\n]+", " "));
        }
        if (trifecta.enforced() && trifecta.gated()) {
            response.header(TOOL_ACCESS_ENFORCEMENT_HEADER, "approval-required");
        }
        return response.body(saved);
    }

    static final String TOOL_ACCESS_WARNING_HEADER = "X-Agent-Warning";
    static final String TOOL_ACCESS_ENFORCEMENT_HEADER = "X-Agent-Trifecta-Enforcement";

    /**
     * Lethal-trifecta check for the agent's current tool allow-list (private-data read + untrusted
     * content + egress), plus what the configured enforcement mode does about it: {@code
     * enforcement} (off|warn|require-approval|block), {@code enforced} (the platform acts on a
     * trifecta this agent holds) and {@code gated} (actions that send data out always need
     * approval). Read-only; needs VIEWER access.
     */
    @GetMapping("/{agentId}/tool-access/trifecta")
    public TrifectaStatusResponse toolAccessTrifecta(@PathVariable UUID agentId) {
        return agentService.trifectaStatus(agentId);
    }

    /**
     * Stage B - the tool allow-list dropdown picker. Every tool this specific agent would currently
     * be offered (its knowledge-base flag, whichever MCP servers are enabled right now), regardless
     * of whatever it's already restricted to - see {@code AgentToolProvider#listAvailableToolsFor}
     * for why this can't be a static list.
     */
    @GetMapping("/{agentId}/available-tools")
    public List<ToolDescriptor> listAvailableTools(@PathVariable UUID agentId) {
        // Cross-user resource sharing retrofit (Round 2) - this endpoint reaches
        // AgentToolProvider directly (not through AgentService), so the VIEWER
        // check has to happen here rather than inside a shared AgentService
        // method - see AgentService#requireAccess's javadoc for why it's
        // package-private (this controller is in the same package).
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return agentToolProvider.listAvailableToolsFor(agentId);
    }

    /**
     * Knowledge Stage F1 - opt-in per-agent knowledge-collection allow-list, same shape/reasoning
     * as {@link #setToolAccess}. Only matters once {@code knowledgeBaseEnabled} is true (see {@link
     * #setKnowledgeBaseEnabled}).
     */
    @PatchMapping("/{agentId}/knowledge-access")
    public AgentResponse setKnowledgeAccess(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentKnowledgeAccessUpdateRequest request) {
        // Unknown ids used to reach the DB and trip the V16 FK to knowledge_collection,
        // surfacing as a 500. requireExists throws KnowledgeCollectionNotFoundException (404).
        if (request.allowedKnowledgeCollectionIds() != null) {
            for (UUID collectionId : request.allowedKnowledgeCollectionIds()) {
                knowledgeCollectionService.requireExists(collectionId);
            }
        }
        return agentService.setAllowedKnowledgeCollectionIds(
                agentId, request.allowedKnowledgeCollectionIds());
    }

    /**
     * Migration V198 - the knowledge-WRITE posture (the {@code add_to_knowledge_base} tool): the
     * opt-in flag plus the deny-by-default collection grant list. {@link AgentResponse} carries
     * neither field, so these endpoints read and write a dedicated view; the pair is also what was
     * missing for the feature to be usable at all (the columns and {@code AgentService} setters
     * existed, the set path did not).
     */
    @GetMapping("/{agentId}/knowledge-write-access")
    public AgentKnowledgeWriteAccessResponse getKnowledgeWriteAccess(@PathVariable UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return knowledgeWriteAccess(agentId);
    }

    /** The agent's write opt-in ({@code {enabled}} body, same shape as {@link #setKnowledgeBaseEnabled}). */
    @PatchMapping("/{agentId}/knowledge-write")
    public AgentKnowledgeWriteAccessResponse setKnowledgeWrite(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentKnowledgeBaseToggleRequest request) {
        agentService.setKnowledgeWriteEnabled(agentId, request.enabled());
        return knowledgeWriteAccess(agentId);
    }

    /**
     * The collection grant list for {@link #setKnowledgeWrite}. Empty/null clears every grant back
     * to deny-all - the OPPOSITE of {@link #setKnowledgeAccess}'s "empty means unrestricted"; an
     * unknown id is a 404, same {@code requireExists} reasoning as there.
     */
    @PatchMapping("/{agentId}/knowledge-write-access")
    public AgentKnowledgeWriteAccessResponse setKnowledgeWriteAccess(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentKnowledgeWriteAccessUpdateRequest request) {
        if (request.allowedKnowledgeWriteCollectionIds() != null) {
            for (UUID collectionId : request.allowedKnowledgeWriteCollectionIds()) {
                knowledgeCollectionService.requireExists(collectionId);
            }
        }
        agentService.setAllowedKnowledgeWriteCollectionIds(
                agentId, request.allowedKnowledgeWriteCollectionIds());
        return knowledgeWriteAccess(agentId);
    }

    private AgentKnowledgeWriteAccessResponse knowledgeWriteAccess(UUID agentId) {
        return new AgentKnowledgeWriteAccessResponse(
                agentService.isKnowledgeWriteEnabled(agentId),
                agentService.getAllowedKnowledgeWriteCollectionIds(agentId));
    }

    /**
     * Opt-in per-agent
     * collaborator list (see {@code Agent.collaboratorAgentIds}' javadoc for why this is opt-in,
     * same reasoning as {@link #setToolAccess}/{@link #setKnowledgeAccess}). Each id must be
     * another existing agent in this same tenant - {@link AgentService#setCollaboratorAgentIds}
     * validates and rejects self-reference or an unknown id with a clear error rather than silently
     * dropping it.
     */
    @PatchMapping("/{agentId}/collaborators")
    public AgentResponse setCollaborators(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentCollaboratorsUpdateRequest request) {
        return agentService.setCollaboratorAgentIds(agentId, request.collaboratorAgentIds());
    }

    /**
     * Direct feedback: "customer should be able to select the temperature, p and all other details
     * they can set on agents" - lets a customer tune an existing agent's maxTokens/temperature/topP
     * after creation (creation-time defaults are still set via {@link #create}, see {@code
     * AgentCreateRequest}). All three always update together - see {@code
     * AgentGenerationParamsUpdateRequest}'s javadoc.
     */
    @PatchMapping("/{agentId}/generation-params")
    public AgentResponse updateGenerationParams(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentGenerationParamsUpdateRequest request) {
        return agentService.updateGenerationParams(agentId, request);
    }

    /**
     * Direct feedback: "in agents there should be simple and manual tuning selection" - switches an
     * existing agent between Simple (auto-tuned) and Manual generation control. See {@link
     * AgentService#setTuningMode} for what switching TO SIMPLE triggers.
     */
    @PatchMapping("/{agentId}/tuning-mode")
    public AgentResponse setTuningMode(
            @PathVariable UUID agentId, @Valid @RequestBody AgentTuningModeUpdateRequest request) {
        return agentService.setTuningMode(agentId, request.tuningMode());
    }

    /**
     * Edge Wave 2 (EDGE-B): sets/clears this agent's server-declared edge routing policy (null
     * clears it, back to device-preference routing) - see {@link AgentService#setEdgeRoutingPolicy}
     * and {@link EdgeRoutingPolicy}'s javadoc for the honest client-side-enforcement limitation.
     */
    @PatchMapping("/{agentId}/edge-routing-policy")
    public AgentResponse setEdgeRoutingPolicy(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentEdgeRoutingPolicyUpdateRequest request) {
        return agentService.setEdgeRoutingPolicy(agentId, request.edgeRoutingPolicy());
    }

    /**
     * Per-agent Simple/Advanced override (null clears it, back to inheriting the tenant default) -
     * see {@link AgentService#setExperienceMode}.
     */
    @PatchMapping("/{agentId}/experience-mode")
    public AgentResponse setExperienceMode(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentExperienceModeUpdateRequest request) {
        return agentService.setExperienceModeEnforced(agentId, request.experienceMode());
    }

    /**
     * Agent Intelligence research doc (July 2026) - opt an existing agent into rigorous,
     * independently-verified tool calls (or back to FAST). See {@link ReasoningDepth}'s javadoc and
     * {@link AgentService#setReasoningDepth}.
     */
    @PatchMapping("/{agentId}/reasoning-depth")
    public AgentResponse setReasoningDepth(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentReasoningDepthUpdateRequest request) {
        return agentService.setReasoningDepth(agentId, request.reasoningDepth());
    }

    /**
     * #815 Governance follow-up - opt an existing agent into a stricter or looser autonomy posture.
     * See {@link AgentAutonomyTier}'s javadoc and {@link AgentService#setAutonomyTier}.
     */
    @PatchMapping("/{agentId}/autonomy-tier")
    public AgentResponse setAutonomyTier(
            @PathVariable UUID agentId,
            @Valid @RequestBody AgentAutonomyTierUpdateRequest request) {
        return agentService.setAutonomyTier(agentId, request.autonomyTier());
    }

    /**
     * Unlimited-plan capability: "users should be able to...change the models" - swaps an existing
     * agent's provider/model pair. Same edit-lock gating as every other after-creation PATCH here
     * (see {@link AgentService#updateModel}).
     */
    @PatchMapping("/{agentId}/model")
    public AgentResponse updateModel(
            @PathVariable UUID agentId, @Valid @RequestBody AgentModelUpdateRequest request) {
        return agentService.updateModel(agentId, request.providerName(), request.model());
    }

    /**
     * Direct feedback: "set a fallback provider/model so future provider outages degrade gracefully
     * instead of failing the run." See {@link AgentService#setFallbackProvider} and {@link
     * AgentFallbackUpdateRequest}'s javadoc - {@code providerName: null} clears a
     * previously-configured fallback.
     */
    @PatchMapping("/{agentId}/fallback")
    public AgentResponse setFallbackProvider(
            @PathVariable UUID agentId, @Valid @RequestBody AgentFallbackUpdateRequest request) {
        return agentService.setFallbackProvider(agentId, request.providerName(), request.model());
    }

    /**
     * #1077 fast-follow (Master Roadmap #18, cost optimization: routing) - the "routing" half
     * response-caching's own javadoc deliberately left open. See {@link
     * AgentService#setCostRouting} and {@link AgentCostRoutingUpdateRequest}'s javadoc - {@code
     * economyProviderName: null} clears a previously-configured economy model.
     */
    @PatchMapping("/{agentId}/cost-routing")
    public AgentResponse setCostRouting(
            @PathVariable UUID agentId, @Valid @RequestBody AgentCostRoutingUpdateRequest request) {
        return agentService.setCostRouting(
                agentId,
                request.enabled(),
                request.economyProviderName(),
                request.economyModel(),
                request.jevThreshold());
    }

    /**
     * Multi-provider voice/video/text architecture foundation - selects the speech-to-text provider
     * for this agent's voice turns. Premium feature - FREE tenants always use the default fallback
     * chain (Sidecar Whisper → Sarvam). See {@link AgentService#updateVoiceModel} and {@link
     * com.agenticai.core.agents.dto.AgentVoiceModelUpdateRequest}'s javadoc - {@code
     * voiceModelProvider: null} clears a previously-configured selection.
     */
    @PatchMapping("/{agentId}/voice-model")
    public AgentResponse updateVoiceModel(
            @PathVariable UUID agentId,
            @Valid @RequestBody
                    com.agenticai.core.agents.dto.AgentVoiceModelUpdateRequest request) {
        return agentService.updateVoiceModel(agentId, request);
    }

    /**
     * TTS provider selector (Stage 2, 2026-08-09) - output-side twin of {@link #updateVoiceModel}:
     * selects the text-to-speech provider this agent's voice REPLIES are synthesized through,
     * instead of the input-side transcription provider {@code updateVoiceModel} controls. Premium
     * feature - FREE tenants always use the default fallback chain (ElevenLabs → Sarvam). See
     * {@link AgentService#updateTtsModel} and {@link
     * com.agenticai.core.agents.dto.AgentTtsModelUpdateRequest}'s javadoc - {@code ttsProvider:
     * null} clears a previously-configured selection.
     */
    @PatchMapping("/{agentId}/tts-model")
    public AgentResponse updateTtsModel(
            @PathVariable UUID agentId,
            @Valid @RequestBody com.agenticai.core.agents.dto.AgentTtsModelUpdateRequest request) {
        return agentService.updateTtsModel(agentId, request);
    }

    /**
     * Multi-provider video/transcription foundation - selects the video-to-text provider for this
     * agent's video turns. Premium feature - FREE tenants always use the default fallback chain.
     * See {@link AgentService#updateVideoModel} and {@link
     * com.agenticai.core.agents.dto.AgentVideoModelUpdateRequest}'s javadoc - {@code
     * videoModelProvider: null} clears a previously-configured selection.
     */
    @PatchMapping("/{agentId}/video-model")
    public AgentResponse updateVideoModel(
            @PathVariable UUID agentId,
            @Valid @RequestBody
                    com.agenticai.core.agents.dto.AgentVideoModelUpdateRequest request) {
        return agentService.updateVideoModel(agentId, request);
    }

    /**
     * Multi-provider text/LLM routing - selects the text completion provider for this agent's chat
     * turns. Premium/Unlimited feature - FREE tenants always use the platform's hosted LLM pool.
     * See {@link AgentService#updateTextModel} and {@link
     * com.agenticai.core.agents.dto.AgentTextModelUpdateRequest}'s javadoc - {@code
     * textModelProvider: null} clears a previously-configured selection and reverts to the agent's
     * primary provider/model.
     */
    @PatchMapping("/{agentId}/text-model")
    public AgentResponse updateTextModel(
            @PathVariable UUID agentId,
            @Valid @RequestBody com.agenticai.core.agents.dto.AgentTextModelUpdateRequest request) {
        return agentService.updateTextModel(agentId, request);
    }

    /**
     * Direct feedback: "give this as an extra feature instead of [changing] the simple workflow...
     * make it available in premium and unlimited." Optional one-click Consumer/Professional/Expert
     * preset - see {@link AgentTier}'s javadoc and {@link AgentService#applyTier}. Premium/
     * Unlimited only; a FREE-plan tenant gets the same 403 "upgrade your plan" shape every other
     * plan-gated endpoint returns.
     */
    @PatchMapping("/{agentId}/tier")
    public AgentResponse applyTier(
            @PathVariable UUID agentId, @Valid @RequestBody AgentTierUpdateRequest request) {
        return agentService.applyTier(agentId, request.tier());
    }

    /**
     * Consumer-facing capability: "consumers should be able to edit the agent prompt". Same
     * edit-lock gating as {@link #updateModel} (see {@link AgentService#updateSystemPrompt}).
     */
    @PatchMapping("/{agentId}/prompt")
    public AgentResponse updateSystemPrompt(
            @PathVariable UUID agentId, @Valid @RequestBody AgentPromptUpdateRequest request) {
        return agentService.updateSystemPrompt(agentId, request.systemPrompt());
    }

    /**
     * Master-Roadmap-2026.md item #5 (prompt-template versioning) - every past state of this
     * agent's system prompt, most recent first (see {@link AgentService#listPromptVersions}).
     * Read-only, so unlike {@link #updateSystemPrompt} it is not gated by {@code assertEditable} -
     * a locked FREE-tier agent's prompt history is still viewable, same "chat, run, and delete are
     * still available" posture the edit-lock message itself already promises for read-only
     * surfaces.
     */
    @GetMapping("/{agentId}/prompt/versions")
    public List<PromptVersionResponse> listPromptVersions(@PathVariable UUID agentId) {
        return agentService.listPromptVersions(agentId);
    }

    /**
     * Master-Roadmap-2026.md item #5 (prompt-template versioning) - restores a past prompt as the
     * agent's current one (see {@link AgentService#revertToPromptVersion}'s javadoc for why this is
     * always itself non-destructive). Same edit-lock gating as {@link #updateSystemPrompt}.
     */
    @PostMapping("/{agentId}/prompt/versions/{versionId}/revert")
    public AgentResponse revertToPromptVersion(
            @PathVariable UUID agentId, @PathVariable UUID versionId) {
        return agentService.revertToPromptVersion(agentId, versionId);
    }

    /**
     * Unlimited-plan capability: "should be able to...delete also" - hard delete, reachable
     * regardless of edit-lock state (see {@link AgentService#delete}'s javadoc for why). 204 on
     * success, a clear 409 ({@link AgentInUseException}, mapped in {@code GlobalExceptionHandler})
     * if the agent is still bound to a channel.
     */
    @DeleteMapping("/{agentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID agentId) {
        agentService.delete(agentId);
        return ResponseEntity.noContent().build();
    }
}
