package com.agenticai.core.agents.core;

import com.agenticai.core.agents.dto.AgentCapabilityResponse;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentGenerationParamsUpdateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.dto.AgentTextModelUpdateRequest;
import com.agenticai.core.agents.dto.AgentTtsModelUpdateRequest;
import com.agenticai.core.agents.dto.AgentVideoModelUpdateRequest;
import com.agenticai.core.agents.dto.AgentVoiceModelUpdateRequest;
import com.agenticai.core.agents.dto.PromptVersionResponse;
import com.agenticai.core.agents.tools.TrifectaEnforcementService;
import com.agenticai.core.agents.tools.classification.LethalTrifectaDetector;
import com.agenticai.core.agents.tools.classification.TrifectaStatusResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The agents facade: every public entry point the controllers, chat path and other modules call,
 * kept source-compatible, delegating to the focused services it replaced the old 2,000-line body
 * with: {@link AgentCreationService}, {@link AgentSettingsService} (a per-field {@link
 * AgentSettingsPatch} command applied by one facet applier per facet), {@link
 * AgentModelSelectionService}, {@link AgentCapabilityService}, {@link AgentModelResolver}, {@link
 * AgentAccessService}, {@link AgentVersioningService} and {@link AgentResponseAssembler}.
 *
 * <p>What stays here is the read side (list/get and the per-pod projection getters), the
 * tool/experience-mode/collaborator writes that share the lethal-trifecta enforcement hooks, the
 * prompt history and delete.
 */
@Service
public class AgentService {

    private final AgentRepository repository;
    private final PromptVersionRepository promptVersionRepository;
    private final AgentSchemaService agentSchemaService;
    private final AgentAccessService access;
    private final AgentResponseAssembler assembler;
    private final AgentVersioningService versioning;
    private final AgentModelResolver modelResolver;
    private final AgentCapabilityService capabilityService;
    private final AgentSettingsService settings;
    private final AgentModelSelectionService modelSelection;
    private final AgentCreationService creationService;
    private final AgentToolAccessWriter toolAccess;
    private final AgentPromptHistory promptHistory;
    private final AgentCatalogOps catalog;

    public AgentService(
            AgentRepository repository,
            PromptVersionRepository promptVersionRepository,
            AgentSchemaService agentSchemaService,
            AgentAccessService access,
            AgentResponseAssembler assembler,
            AgentVersioningService versioning,
            AgentModelResolver modelResolver,
            AgentCapabilityService capabilityService,
            AgentSettingsService settings,
            AgentModelSelectionService modelSelection,
            AgentCreationService creationService) {
        this.repository = repository;
        this.promptVersionRepository = promptVersionRepository;
        this.agentSchemaService = agentSchemaService;
        this.access = access;
        this.assembler = assembler;
        this.versioning = versioning;
        this.modelResolver = modelResolver;
        this.capabilityService = capabilityService;
        this.settings = settings;
        this.modelSelection = modelSelection;
        this.creationService = creationService;
        this.toolAccess = new AgentToolAccessWriter(access, versioning, agentSchemaService);
        this.promptHistory = new AgentPromptHistory(promptVersionRepository, access, versioning);
        this.catalog = new AgentCatalogOps(repository, access, assembler);
    }

    /** Public form of {@code resolveDefaultModelFor} for the chat resolver's no-pool fallback. */
    public String defaultModelFor(com.agenticai.core.providers.ProviderName provider) {
        return modelResolver.resolveDefaultModelFor(provider);
    }

    /** Delegates to {@link AgentModelResolver#resolveEffectiveModel}, where the behavior and its rationale live. */
    @Transactional
    public String resolveEffectiveModel(Agent agent) {
        return modelResolver.resolveEffectiveModel(agent);
    }

    /** Delegates to {@link AgentModelResolver#providerServesModel}, where the behavior and its rationale live. */
    @Transactional
    public boolean providerServesModel(
            com.agenticai.core.providers.ProviderName provider, String model) {
        return modelResolver.providerServesModel(provider, model);
    }

    /** Delegates to {@link AgentAccessService#assertEditable}, where the behavior and its rationale live. */
    public void assertEditable(Agent agent) {
        access.assertEditable(agent);
    }

    /** Delegates to {@link AgentResponseAssembler#setSandboxFileRegistry}, where the behavior and its rationale live. */
    public void setSandboxFileRegistry(
            org.springframework.beans.factory.ObjectProvider<
                            com.agenticai.core.agents.tools.e2b.SandboxFileRegistry>
                    provider) {
        assembler.setSandboxFileRegistry(provider);
    }

    /**
     * Agent Intelligence research doc (July 2026) - lets a customer opt an existing agent into
     * rigorous, independently-verified tool calls after creation, same after-the-fact tunability as
     * {@link #updateGenerationParams}. See {@link ReasoningDepth}'s javadoc.
     */
    @Transactional
    public AgentResponse setReasoningDepth(UUID agentId, ReasoningDepth reasoningDepth) {
        return settings.apply(agentId, new AgentSettingsPatch.Reasoning(reasoningDepth));
    }

    /**
     * Public, read-only projection - lets {@code AgentToolProvider} (a different package) decide
     * whether to invoke the tool-call verifier for this agent's turn, same "projection, not the
     * full entity" reasoning as {@code isKnowledgeBaseEnabled}.
     */
    public ReasoningDepth getReasoningDepth(UUID agentId) {
        return projection(agentId).reasoningDepth();
    }

    /**
     * #815 Governance follow-up - same "projection, not the full entity" shape as {@link
     * #getReasoningDepth}, lets {@code AgentToolProvider} resolve this once per chat turn to drive
     * the step-ceiling/approval-gate behavior described in {@link AgentAutonomyTier}'s javadoc.
     */
    public AgentAutonomyTier getAutonomyTier(UUID agentId) {
        return projection(agentId).autonomyTier();
    }

    /**
     * Lets a customer opt an existing agent into a stricter or looser autonomy posture after
     * creation - same after-the-fact tunability as {@link #setReasoningDepth}. Until this endpoint
     * existed, {@link AgentAutonomyTier} could only ever be its default ({@code SEMI_AUTONOMOUS})
     * for every agent, since nothing else in this codebase ever called {@code
     * Agent.setAutonomyTier}.
     */
    @Transactional
    public AgentResponse setAutonomyTier(UUID agentId, AgentAutonomyTier autonomyTier) {
        return settings.apply(agentId, new AgentSettingsPatch.Autonomy(autonomyTier));
    }

    /**
     * Per-agent run cap (V421) - "projection, not the full entity", read once per turn by {@code
     * AgentToolProvider} when a delegation tool asks for the turn's budget. Null = no per-agent
     * cap.
     */
    public java.math.BigDecimal getRunBudgetUsd(UUID agentId) {
        // A hidden A/B treatment clone runs under its CONTROL agent's cap; AgentProjection#from
        // resolves that.
        return projection(agentId).runBudgetUsd();
    }

    /**
     * Stores (or with null, clears) the per-agent run cap. Same "requireAccess, assertEditable,
     * mutate, saveVersioned" shape as {@link #setAutonomyTier}. The value is validated by the
     * caller ({@code AgentRunBudgetService}).
     */
    @Transactional
    public AgentResponse setRunBudgetUsd(UUID agentId, java.math.BigDecimal runBudgetUsd) {
        return settings.apply(agentId, new AgentSettingsPatch.RunBudget(runBudgetUsd));
    }

    /**
     * V331 extra sampling controls: null = unchanged, empty stopSequences = clear, {@code
     * clearSamplingParams=true} resets all five first.
     */
    static void applySamplingUpdate(Agent agent, AgentGenerationParamsUpdateRequest request) {
        AgentSettingsService.applySamplingUpdate(agent, request);
    }

    /**
     * Lets a customer tune an already-created agent's generation behavior - see {@code
     * AgentGenerationParamsUpdateRequest}'s javadoc for why all three fields are required together
     * rather than independently optional.
     */
    @Transactional
    public AgentResponse updateGenerationParams(
            UUID agentId, AgentGenerationParamsUpdateRequest request) {
        return settings.apply(agentId, new AgentSettingsPatch.GenerationParams(request));
    }

    /**
     * Direct feedback: "in agents there should be simple and manual tuning selection". Switching TO
     * SIMPLE immediately (re-)infers temperature/ topP/maxTokens from this agent's (immutable)
     * systemPrompt via {@link GenerationParamsInferenceService} and stores the result, same
     * "mutate, save, toResponse" shape as {@link #setExperienceMode}. Switching TO MANUAL only
     * flips the mode flag - whatever values are currently set (inferred or otherwise) are left in
     * place as an editable starting point, not reset to any default.
     */
    @Transactional
    public AgentResponse setTuningMode(UUID agentId, TuningMode tuningMode) {
        return settings.apply(agentId, new AgentSettingsPatch.Tuning(tuningMode));
    }

    /**
     * Edge Wave 2 (EDGE-B): sets/clears this agent's server-declared edge routing policy - null
     * CLEARS it (back to device-preference routing), a legitimate target state rather than a
     * default, unlike {@link #setTuningMode}'s MANUAL floor. Same EDITOR + edit-lock gate as every
     * other per-field agent PATCH; same "mutate, save, toResponse" shape as {@link
     * #setExperienceMode}.
     */
    @Transactional
    public AgentResponse setEdgeRoutingPolicy(UUID agentId, EdgeRoutingPolicy policy) {
        return settings.apply(agentId, new AgentSettingsPatch.EdgeRouting(policy));
    }

    /** Delegates to {@link AgentCatalogOps#listAll}, where the per-caller visibility rule lives. */
    @Transactional(readOnly = true)
    public List<AgentResponse> listAll() {
        return catalog.listAll();
    }

    @Transactional(readOnly = true)
    public AgentResponse get(UUID agentId) {
        return assembler.toResponse(access.requireAccess(agentId, PermissionLevel.VIEWER));
    }

    /**
     * Tenant-scoped lookup with NO per-user ownership check, for callers whose own membership check
     * is the authorization decision (the MCP exposed-agents grant). See {@link
     * AgentCatalogOps#grantedAgent} for the incident that made it necessary.
     */
    @Transactional(readOnly = true)
    public AgentResponse getGrantedAgent(UUID agentId) {
        return catalog.grantedAgent(agentId);
    }

    /**
     * Tenant-scoped existence check ONLY - deliberately no per-user {@code requireAccess} gate; see
     * {@link AgentCatalogOps#requireExists} for why.
     */
    // noRollbackFor: callers such as OrchestratorService#validate (itself a
    // read-only transaction) catch AgentNotFoundException and report it as a
    // validation error. Without this, the exception crossing this @Transactional
    // proxy marks the shared transaction rollback-only and the caller's commit
    // fails with UnexpectedRollbackException (a 500 instead of a 200 + errors).
    @Transactional(readOnly = true, noRollbackFor = AgentNotFoundException.class)
    public void requireExists(UUID agentId) {
        catalog.requireExists(agentId);
    }

    @Transactional
    public AgentResponse setKnowledgeBaseEnabled(UUID agentId, boolean enabled) {
        return settings.apply(agentId, new AgentSettingsPatch.Capability(AgentSettingsPatch.CapabilityFlag.KNOWLEDGE_BASE, enabled));
    }

    /**
     * Public, read-only projection of a single flag - lets AgentToolProvider (a different package)
     * decide whether to offer the knowledge search tool without exposing the full entity outside
     * this package the way getEntity() deliberately doesn't.
     */
    public boolean isKnowledgeBaseEnabled(UUID agentId) {
        return projection(agentId).knowledgeBaseEnabled();
    }

    /** Delegates to {@link AgentCapabilityService#listCapabilities}, where the behavior and its rationale live. */
    @Transactional(readOnly = true)
    public List<AgentCapabilityResponse> listCapabilities(UUID agentId) {
        return capabilityService.listCapabilities(agentId);
    }

    /** Delegates to {@link AgentCapabilityService#setCapabilityEnabled}, where the behavior and its rationale live. */
    @Transactional
    public AgentResponse setCapabilityEnabled(UUID agentId, String capabilityKey, boolean enabled) {
        return capabilityService.setCapabilityEnabled(agentId, capabilityKey, enabled);
    }

    /**
     * Same "public projection, not the full entity" reasoning as {@code isKnowledgeBaseEnabled}.
     */
    public boolean isSarvamEnabled(UUID agentId) {
        return projection(agentId).sarvamEnabled();
    }

    /** Same "public projection, not the full entity" reasoning as {@code isSarvamEnabled}. */
    public boolean isE2bEnabled(UUID agentId) {
        return projection(agentId).e2bEnabled();
    }

    /** Same "public projection, not the full entity" reasoning as {@code isE2bEnabled}. */
    public boolean isXaiServerToolsEnabled(UUID agentId) {
        return projection(agentId).xaiServerToolsEnabled();
    }

    /** Same "public projection, not the full entity" reasoning as {@code isE2bEnabled}. */
    public boolean isCodeWorkspaceEnabled(UUID agentId) {
        return projection(agentId).codeWorkspaceEnabled();
    }

    /**
     * Master Roadmap 2026 item #18 (cost optimization) - explicit per-agent opt-in for exact-match
     * LLM response caching. Same "assertEditable, mutate, save, toResponse" shape as {@link
     * #setCapabilityEnabled}. See {@link com.agenticai.core.agents.core.Agent#responseCachingEnabled}'s
     * javadoc for why this is opt-in rather than automatic.
     */
    @Transactional
    public AgentResponse setResponseCachingEnabled(UUID agentId, boolean enabled) {
        return settings.apply(agentId, new AgentSettingsPatch.Caching(AgentSettingsPatch.CacheKind.EXACT_MATCH, enabled));
    }

    /**
     * Opt this agent in or out of GATED SEMANTIC (embedding-similarity) response caching - a
     * separate, stronger opt-in than {@link #setResponseCachingEnabled} above (exact-match). Same
     * "assertEditable, mutate, save, toResponse" shape. See {@link
     * com.agenticai.core.agents.turn.cache.SemanticResponseCacheService} for the quality gates and {@link
     * Agent#semanticResponseCachingEnabled}'s javadoc for why it is a distinct flag from the
     * exact-match one.
     */
    @Transactional
    public AgentResponse setSemanticResponseCachingEnabled(UUID agentId, boolean enabled) {
        return settings.apply(agentId, new AgentSettingsPatch.Caching(AgentSettingsPatch.CacheKind.SEMANTIC, enabled));
    }

    /**
     * Shared cross-agent memory tier (#847) - explicit per-agent opt-in for both writing to and
     * automatically recalling from the tenant-wide shared memory pool, same "assertEditable,
     * mutate, save, toResponse" shape as {@link #setCapabilityEnabled}.
     */
    @Transactional
    public AgentResponse setShareMemoryWithTenant(UUID agentId, boolean enabled) {
        return settings.apply(agentId, new AgentSettingsPatch.Capability(AgentSettingsPatch.CapabilityFlag.SHARE_MEMORY_WITH_TENANT, enabled));
    }

    /**
     * Same "public projection, not the full entity" reasoning as {@code isKnowledgeBaseEnabled}.
     */
    public boolean isShareMemoryWithTenant(UUID agentId) {
        return projection(agentId).shareMemoryWithTenant();
    }

    /**
     * Face recognition (2026-08-06) - the explicit consent gate. Same EDITOR + assertEditable shape
     * as {@link #setShareMemoryWithTenant} above. FaceEnrollmentController checks {@link
     * #isBiometricConsentEnabled} before accepting any enroll/list call for this agent's
     * collections - this method is the only way that check can ever pass.
     */
    @Transactional
    public AgentResponse setBiometricConsentEnabled(UUID agentId, boolean enabled) {
        return settings.apply(agentId, new AgentSettingsPatch.Capability(AgentSettingsPatch.CapabilityFlag.BIOMETRIC_CONSENT, enabled));
    }

    /**
     * Same "public projection, not the full entity" reasoning as {@code isKnowledgeBaseEnabled}.
     */
    public boolean isBiometricConsentEnabled(UUID agentId) {
        return projection(agentId).biometricConsentEnabled();
    }

    /**
     * Autonomy/Guardrails Strategy Stage A - same "public projection, not the full entity"
     * reasoning as {@code isKnowledgeBaseEnabled}, this time for AgentToolProvider to filter its
     * resolved tool list against. Empty means no restriction - see {@code Agent.allowedToolNames}'s
     * javadoc.
     */
    public Set<String> getAllowedToolNames(UUID agentId) {
        return projection(agentId).allowedToolNames();
    }

    @Autowired(required = false)
    void setLethalTrifectaDetector(LethalTrifectaDetector lethalTrifectaDetector) {
        toolAccess.setLethalTrifectaDetector(lethalTrifectaDetector);
    }

    /** Delegates to {@link AgentToolAccessWriter#lethalTrifectaReport}. */
    @Transactional(readOnly = true)
    public LethalTrifectaDetector.Report lethalTrifectaReport(UUID agentId) {
        return toolAccess.lethalTrifectaReport(agentId);
    }

    /** Optional (test-built contexts leave it null = the advisory-only behaviour). */
    @Autowired(required = false)
    void setTrifectaEnforcement(TrifectaEnforcementService trifectaEnforcement) {
        toolAccess.setTrifectaEnforcement(trifectaEnforcement);
        capabilityService.setTrifectaEnforcement(trifectaEnforcement);
    }

    /** Delegates to {@link AgentToolAccessWriter#trifectaStatus} (needs VIEWER). */
    @Transactional(readOnly = true)
    public TrifectaStatusResponse trifectaStatus(UUID agentId) {
        return toolAccess.trifectaStatus(agentId);
    }

    /**
     * Server-side writers (template install, demo studio, service envoys, research runs): applies
     * the allow-list WITHOUT ever rejecting it on lethal-trifecta policy (see {@link
     * AgentToolAccessWriter#updateAllowedToolNames}). Empty set clears the restriction.
     */
    @Transactional
    public AgentResponse setAllowedToolNames(UUID agentId, Set<String> allowedToolNames) {
        return toolAccess.updateAllowedToolNames(agentId, allowedToolNames, false);
    }

    /**
     * The user-facing allow-list save ({@code PATCH /api/agents/{id}/tool-access}): same as {@link
     * #setAllowedToolNames}, but in block mode a save that makes the lethal trifecta worse is
     * rejected with {@link IllegalArgumentException} (400) before anything is mutated.
     */
    @Transactional
    public AgentResponse setAllowedToolNamesEnforced(UUID agentId, Set<String> allowedToolNames) {
        return toolAccess.updateAllowedToolNames(agentId, allowedToolNames, true);
    }

    /** Delegates to {@link AgentToolAccessWriter#updateExperienceMode} (server-side, never rejects). */
    @Transactional
    public AgentResponse setExperienceMode(UUID agentId, ExperienceMode experienceMode) {
        return toolAccess.updateExperienceMode(agentId, experienceMode, false);
    }

    /**
     * The user-facing switch ({@code PATCH /api/agents/{id}/experience-mode}); see {@link
     * #setAllowedToolNamesEnforced}.
     */
    @Transactional
    public AgentResponse setExperienceModeEnforced(UUID agentId, ExperienceMode experienceMode) {
        return toolAccess.updateExperienceMode(agentId, experienceMode, true);
    }

    /**
     * Knowledge Stage F1 - same "public projection" reasoning as {@code getAllowedToolNames}, for
     * AgentToolProvider (Stage F2) to scope search_knowledge_base's default collection set against.
     * Empty means no restriction - see {@code Agent.allowedKnowledgeCollectionIds}'s javadoc.
     */
    public Set<UUID> getAllowedKnowledgeCollectionIds(UUID agentId) {
        return projection(agentId).allowedKnowledgeCollectionIds();
    }

    /** Empty set clears the restriction (back to "no restriction, every collection searchable"). */
    @Transactional
    public AgentResponse setAllowedKnowledgeCollectionIds(
            UUID agentId, Set<UUID> allowedKnowledgeCollectionIds) {
        return settings.apply(agentId, new AgentSettingsPatch.KnowledgeCollections(allowedKnowledgeCollectionIds));
    }

    /**
     * Same "public projection" reasoning as {@code getAllowedKnowledgeCollectionIds}, for {@code
     * AgentToolProvider} and the code workspace tool callbacks themselves to scope {@code repo_id}
     * arguments against. Empty means DENY-ALL here (the opposite of {@code
     * allowedKnowledgeCollectionIds}' "empty = unrestricted") - see {@code
     * Agent.allowedCodeIntelRepoIds}' javadoc.
     */
    public Set<String> getAllowedCodeIntelRepoIds(UUID agentId) {
        return projection(agentId).allowedCodeIntelRepoIds();
    }

    /**
     * Empty/null set means "no repo granted" (deny-all) - see {@link #getAllowedCodeIntelRepoIds}.
     */
    @Transactional
    public AgentResponse setAllowedCodeIntelRepoIds(
            UUID agentId, Set<String> allowedCodeIntelRepoIds) {
        return settings.apply(agentId, new AgentSettingsPatch.CodeIntelRepos(allowedCodeIntelRepoIds));
    }

    /**
     * New feature (migration V198) - explicit per-agent opt-in for {@code
     * AddToKnowledgeBaseToolCallback} (the write/insert companion to {@code
     * search_knowledge_base}). Same "assertEditable, mutate, save, toResponse" shape as {@link
     * #setCapabilityEnabled}. This flag alone does not let the agent write into any collection -
     * {@link #setAllowedKnowledgeWriteCollectionIds} below is deny-all until a tenant-admin
     * explicitly grants at least one collection id, same "empty = deny-all" convention as {@link
     * #setAllowedCodeIntelRepoIds}, not {@link #setAllowedKnowledgeCollectionIds}'s "empty =
     * unrestricted".
     */
    @Transactional
    public AgentResponse setKnowledgeWriteEnabled(UUID agentId, boolean enabled) {
        return settings.apply(agentId, new AgentSettingsPatch.Capability(AgentSettingsPatch.CapabilityFlag.KNOWLEDGE_WRITE, enabled));
    }

    /**
     * Same "public projection, not the full entity" reasoning as {@code isCodeWorkspaceEnabled}.
     */
    public boolean isKnowledgeWriteEnabled(UUID agentId) {
        return projection(agentId).knowledgeWriteEnabled();
    }

    /**
     * Same "public projection" reasoning as {@code getAllowedCodeIntelRepoIds}, for {@code
     * AgentToolProvider} and {@code AddToKnowledgeBaseToolCallback} itself to scope {@code
     * collection_id} arguments against. Empty means DENY-ALL here (the opposite of {@code
     * allowedKnowledgeCollectionIds}' "empty = unrestricted") - see migration V198's header
     * comment.
     */
    public Set<UUID> getAllowedKnowledgeWriteCollectionIds(UUID agentId) {
        return projection(agentId).allowedKnowledgeWriteCollectionIds();
    }

    /**
     * Empty/null set means "no collection granted" (deny-all) - see {@link
     * #getAllowedKnowledgeWriteCollectionIds}.
     */
    @Transactional
    public AgentResponse setAllowedKnowledgeWriteCollectionIds(
            UUID agentId, Set<UUID> allowedKnowledgeWriteCollectionIds) {
        return settings.apply(agentId, new AgentSettingsPatch.KnowledgeWriteCollections(allowedKnowledgeWriteCollectionIds));
    }

    /**
     * Same "public projection"
     * reasoning as {@code getAllowedToolNames}, for {@code AgentToolProvider} to build one
     * delegation tool per collaborator. Empty means no collaborators - see {@code
     * Agent.collaboratorAgentIds}' javadoc.
     */
    public Set<UUID> getCollaboratorAgentIds(UUID agentId) {
        return projection(agentId).collaboratorAgentIds();
    }

    /** Delegates to {@link AgentToolAccessWriter#setCollaboratorAgentIds}, which validates the ids. */
    @Transactional
    public AgentResponse setCollaboratorAgentIds(UUID agentId, Set<UUID> collaboratorAgentIds) {
        return toolAccess.setCollaboratorAgentIds(agentId, collaboratorAgentIds);
    }

    /** Delegates to {@link AgentCreationService#create}, where the behavior and its rationale live. */
    @Transactional
    public AgentResponse create(AgentCreateRequest request) {
        return creationService.create(request);
    }

    /** Delegates to {@link AgentCreationService#duplicate}, where the behavior and its rationale live. */
    @Transactional
    public AgentResponse duplicate(UUID agentId) {
        return creationService.duplicate(agentId);
    }

    /** Delegates to {@link AgentModelSelectionService#updateModel}, where the behavior and its rationale live. */
    @Transactional
    public AgentResponse updateModel(
            UUID agentId, com.agenticai.core.providers.ProviderName providerName, String model) {
        return modelSelection.updateModel(agentId, providerName, model);
    }

    /** Delegates to {@link AgentModelSelectionService#setFallbackProvider}, where the behavior and its rationale live. */
    @Transactional
    public AgentResponse setFallbackProvider(
            UUID agentId, com.agenticai.core.providers.ProviderName providerName, String model) {
        return modelSelection.setFallbackProvider(agentId, providerName, model);
    }

    /** Delegates to {@link AgentModelSelectionService#setCostRouting}, where the behavior and its rationale live. */
    @Transactional
    public AgentResponse setCostRouting(
            UUID agentId,
            boolean enabled,
            com.agenticai.core.providers.ProviderName economyProviderName,
            String economyModel,
            Double jevThreshold) {
        return modelSelection.setCostRouting(
                agentId, enabled, economyProviderName, economyModel, jevThreshold);
    }

    /** Delegates to {@link AgentModelSelectionService#updateVoiceModel}, where the behavior and its rationale live. */
    @Transactional
    public AgentResponse updateVoiceModel(UUID agentId, AgentVoiceModelUpdateRequest request) {
        return modelSelection.updateVoiceModel(agentId, request);
    }

    /** Delegates to {@link AgentModelSelectionService#updateTtsModel}, where the behavior and its rationale live. */
    @Transactional
    public AgentResponse updateTtsModel(UUID agentId, AgentTtsModelUpdateRequest request) {
        return modelSelection.updateTtsModel(agentId, request);
    }

    /** Delegates to {@link AgentModelSelectionService#updateVideoModel}, where the behavior and its rationale live. */
    @Transactional
    public AgentResponse updateVideoModel(UUID agentId, AgentVideoModelUpdateRequest request) {
        return modelSelection.updateVideoModel(agentId, request);
    }

    /** Delegates to {@link AgentModelSelectionService#updateTextModel}, where the behavior and its rationale live. */
    @Transactional
    public AgentResponse updateTextModel(UUID agentId, AgentTextModelUpdateRequest request) {
        return modelSelection.updateTextModel(agentId, request);
    }

    /**
     * Fallback observability follow-up (#955) - called by {@code AgentChatService} the moment a
     * provider fallback is attempted (mirrors the same call site that already records a WARNING
     * issue in the Issues portal, see {@code AgentChatService#recordFallbackTriggeredIssue}).
     * Best-effort by design - see the javadoc on {@link AgentRepository#incrementFallbackTriggered}
     * for why this is an atomic bulk UPDATE rather than a load-mutate-save round trip.
     */
    @Transactional
    public void recordFallbackTriggered(java.util.UUID agentId) {
        repository.incrementFallbackTriggered(agentId, java.time.Instant.now());
    }

    /** Counterpart to {@code recordFallbackTriggered} for the "fallback also failed" case. */
    @Transactional
    public void recordFallbackFailed(java.util.UUID agentId) {
        repository.incrementFallbackFailed(agentId, java.time.Instant.now());
    }

    /** Delegates to {@link AgentModelSelectionService#applyTier}, where the behavior and its rationale live. */
    @Transactional
    public AgentResponse applyTier(UUID agentId, AgentTier tier) {
        return modelSelection.applyTier(agentId, tier);
    }

    /** Delegates to {@link AgentPromptHistory#updateSystemPrompt} (snapshots the old prompt first). */
    @Transactional
    public AgentResponse updateSystemPrompt(UUID agentId, String systemPrompt) {
        return promptHistory.updateSystemPrompt(agentId, systemPrompt);
    }

    /** Delegates to {@link AgentPromptHistory#listPromptVersions} (VIEWER, not edit-locked). */
    @Transactional(readOnly = true)
    public List<PromptVersionResponse> listPromptVersions(UUID agentId) {
        return promptHistory.listPromptVersions(agentId);
    }

    /** Delegates to {@link AgentPromptHistory#revertToPromptVersion} (itself non-destructive). */
    @Transactional
    public AgentResponse revertToPromptVersion(UUID agentId, UUID versionId) {
        return promptHistory.revertToPromptVersion(agentId, versionId);
    }

    /** Delegates to {@link AgentCatalogOps#delete} (EDITOR + plan gate, not edit-locked). */
    @Transactional
    public void delete(UUID agentId) {
        catalog.delete(agentId);
    }

    // Projection getters read a per-pod snapshot; authorization and edits read the row.
    private AgentProjection projection(UUID agentId) {
        return projectionCache != null
                ? projectionCache.get(agentId)
                : AgentProjection.from(getEntity(agentId), repository::findById);
    }

    @Autowired(required = false)
    private AgentProjectionCache projectionCache;

    /**
     * Delegates to {@link AgentAccessService#getEntity}, where the behavior and its rationale live.
     * Public only because the turn path moved to {@code agents.turn} (ADR-0023 step 5); other
     * callers should keep using the DTO and projection methods.
     */
    @Transactional(readOnly = true)
    public Agent getEntity(UUID agentId) {
        return access.getEntity(agentId);
    }

    /** Delegates to {@link AgentAccessService#requireAccess}, where the behavior and its rationale live. */
    public Agent requireAccess(UUID agentId, PermissionLevel required) {
        return access.requireAccess(agentId, required);
    }

    /** Delegates to {@link AgentAccessService#requireAgentSlot}, where the behavior and its rationale live. */
    public void requireAgentSlot() {
        access.requireAgentSlot();
    }
}
