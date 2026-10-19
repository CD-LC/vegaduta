package com.agenticai.core.agents.core;

import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.version.AgentConfigSnapshot;
import com.agenticai.core.agents.version.AgentConfigSnapshotCodec;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a new agent ({@link #create}) and duplicates an existing one ({@link #duplicate}). Moved
 * verbatim from {@link AgentService}, which keeps delegating methods so callers are unchanged.
 */
@Service
public class AgentCreationService {

    private final AgentRepository repository;
    private final TenantLimits tenantLimits;
    private final GenerationParamsInferenceService generationParamsInferenceService;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final AgentProviderValidators validators;
    private final AgentSchemaService agentSchemaService;
    private final AgentConfigSnapshotCodec agentConfigSnapshotCodec;
    private final AgentAccessService access;
    private final AgentVersioningService versioning;
    private final AgentModelResolver modelResolver;
    private final AgentDefaults defaults;

    public AgentCreationService(
            AgentRepository repository,
            TenantLimits tenantLimits,
            GenerationParamsInferenceService generationParamsInferenceService,
            ProviderModelCatalogService providerModelCatalogService,
            AgentProviderValidators validators,
            AgentSchemaService agentSchemaService,
            AgentConfigSnapshotCodec agentConfigSnapshotCodec,
            AgentAccessService access,
            AgentVersioningService versioning,
            AgentModelResolver modelResolver,
            AgentDefaults defaults) {
        this.repository = repository;
        this.tenantLimits = tenantLimits;
        this.generationParamsInferenceService = generationParamsInferenceService;
        this.providerModelCatalogService = providerModelCatalogService;
        this.validators = validators;
        this.agentSchemaService = agentSchemaService;
        this.agentConfigSnapshotCodec = agentConfigSnapshotCodec;
        this.access = access;
        this.versioning = versioning;
        this.modelResolver = modelResolver;
        this.defaults = defaults;
    }

    @Transactional
    public AgentResponse create(AgentCreateRequest request) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);

        // B2B's standing 100-agent ceiling (hard block, not a soft warning) -
        // re-checked on every create, not just once, so deleting an agent
        // back below the cap always re-opens a slot. tenantId can be null
        // for callers with no HTTP request context (none exist today, but
        // this guards against ever silently skipping the check rather than
        // NPE-ing); such callers simply aren't capped.
        access.requireAgentSlot();

        // Direct feedback: "free users are able to select the model, billing
        // functionality is not restricting the user" - updateModel already
        // rejects a FREE tenant's attempt to change an EXISTING agent's
        // provider/model (see its own javadoc/gate), but this method, the
        // creation path, had no equivalent check at all: a FREE tenant could
        // still request e.g. providerName=ANTHROPIC directly (bypassing
        // AgentWorkbench.tsx's now-hidden-for-FREE picker) and have that
        // persisted. Chat itself never actually reads this field for a FREE
        // tenant (AgentChatService#buildChatClientFor routes every FREE call
        // through FreeTierChatClientService's own platform pool regardless -
        // this can't be used to get free premium-model access), but the
        // persisted value is still misleading everywhere else it's shown
        // (agent list, exports, this very create response) - coerced to
        // PLATFORM_HOSTED here rather than rejecting the request outright,
        // since unlike updateModel (an explicit, optional "change it" action)
        // agent creation itself is core FREE-tier functionality that must
        // keep working even if the client sent some other provider.
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        boolean isFreePlan = plan == TenantPlan.FREE;
        ProviderName effectiveProviderName =
                isFreePlan ? ProviderName.PLATFORM_HOSTED : request.providerName();

        // Simple mode ("let the engine build everything required") forces
        // knowledge-base attachment on and never restricts tool access at
        // creation time, regardless of what the request asked for - the
        // whole point of Simple mode is that these aren't manual decisions.
        // Advanced mode (or SIMPLE resolved false) keeps today's behavior:
        // knowledgeBaseEnabled only if explicitly requested, unrestricted
        // tool access by default (see Agent.allowedToolNames's javadoc).
        ExperienceMode effectiveMode =
                request.experienceMode() != null
                        ? request.experienceMode()
                        : (tenantId != null
                                ? tenantLimits.experienceMode(tenantId)
                                : ExperienceMode.ADVANCED);
        boolean knowledgeBaseEnabled =
                effectiveMode == ExperienceMode.SIMPLE
                        || (request.knowledgeBaseEnabled() != null
                                && request.knowledgeBaseEnabled());

        // Direct feedback: "in simple it automatically selects the temp,
        // tokens, p based on the given system prompt" - TuningMode.SIMPLE
        // ignores whatever temperature/maxTokens/topP this request carried
        // (if any) and infers them from systemPrompt instead. This is a
        // distinct axis from ExperienceMode.SIMPLE above (which governs
        // tool/knowledge auto-attachment, not generation params) - an agent
        // can combine either independently.
        TuningMode tuningMode =
                request.tuningMode() != null ? request.tuningMode() : TuningMode.MANUAL;
        // Bug fix (direct feedback: "I have not set the claude sonnet itself?"
        // - a tenant whose active provider was NOT Anthropic still got a
        // literal claude-sonnet-* model ID persisted on their agent) -
        // `defaultModel` (app.agents.default-model) is a single global
        // Anthropic-shaped string; blindly using it whenever request.model()
        // was left blank silently paired a WRONG model with request.providerName()
        // for every other provider, and unlike a mismatch a tenant types in
        // themselves (see updateModel's javadoc for why create() stays
        // permissive about THOSE), this one is entirely this method's own
        // doing. Resolved the same way AssistService/WorkflowGraphGeneratorService
        // already do for their own "no explicit model" case: ask the
        // provider's own model catalog for a real model of THAT provider.
        // PLATFORM_HOSTED isn't a real BYOK provider with its own model
        // catalog (see ProviderName's javadoc) - FreeTierChatClientService
        // picks the actual pooled model independently at chat time and never
        // reads this field back, so a fixed descriptive placeholder is more
        // accurate here than running a FREE tenant's request.model() (or the
        // Anthropic-shaped defaultModel) through a catalog lookup that isn't
        // meaningful for this provider value.
        String resolvedModel =
                isFreePlan
                        ? "platform-pool"
                        : (request.model() != null
                                ? request.model()
                                : modelResolver.resolveDefaultModelFor(effectiveProviderName));

        // Audit 2026-10-02: the catalog allowlist #updateModel enforces on
        // the PATCH path used to be missing here - a paid tenant on the
        // platform-hosted pool could persist an off-catalog model via
        // create() that #updateModel would then refuse to set. Same
        // validation split as #updateModel: the curated catalog is a hard
        // allowlist only for platform-pool tenants; BYOK tenants may still
        // name any model their own key supports. PLATFORM_HOSTED as the
        // provider value skips the check - the pool picks the concrete model
        // at chat time and this field is descriptive only. FREE is already
        // coerced to PLATFORM_HOSTED above, so it never reaches this check.
        if (!isFreePlan
                && request.model() != null
                && effectiveProviderName != ProviderName.PLATFORM_HOSTED
                && access.usesPlatformLlm(tenantId)
                && providerModelCatalogService
                        .findModel(effectiveProviderName, request.model())
                        .isEmpty()) {
            throw new AgentModelNotSupportedException(
                    "\""
                            + request.model()
                            + "\" isn't a recognized model for "
                            + effectiveProviderName
                            + " - pick one from that provider's model list.");
        }
        int maxTokens;
        double temperature;
        double topP;
        if (tuningMode == TuningMode.SIMPLE) {
            GenerationParamsInferenceService.InferredParams inferred =
                    generationParamsInferenceService.infer(
                            request.systemPrompt(), effectiveProviderName, resolvedModel);
            maxTokens = inferred.maxTokens();
            temperature = inferred.temperature();
            topP = inferred.topP();
        } else {
            maxTokens = request.maxTokens() != null ? request.maxTokens() : defaults.maxTokens();
            temperature =
                    request.temperature() != null ? request.temperature() : defaults.temperature();
            topP = request.topP() != null ? request.topP() : defaults.topP();
        }

        Agent agent =
                new Agent(
                        request.name(),
                        request.systemPrompt(),
                        effectiveProviderName,
                        resolvedModel,
                        maxTokens,
                        temperature,
                        topP,
                        knowledgeBaseEnabled,
                        request.experienceMode(),
                        request.reasoningDepth() != null
                                ? request.reasoningDepth()
                                : ReasoningDepth.FAST,
                        tuningMode);
        // Cross-user resource sharing retrofit (Round 2) - private-going-forward-only
        // rollout (migration V151's own header comment): every agent created from
        // this point on records its creator's subject, making it private-by-default
        // (see ResourceAccessService#requireAccess) unless/until the owner shares it.
        // TenantContext.current() being empty (no HTTP request context) leaves
        // createdBy null, same "legacy/open" treatment as every pre-migration agent -
        // there is no meaningful owner to record for a non-request caller.
        agent.setCreatedBy(TenantContext.current().map(TenantContext::getSubject).orElse(null));
        // Audit 2026-10-02: seed the operator-configured default guardrail
        // set (see the field's javadoc) - every secondary create path
        // (duplicate, generate, community-template import) funnels through
        // here, so all of them inherit the safe default. A template's own
        // declared guardrails still win: CommunityTemplateService applies
        // them post-create via the normal PATCH path.
        if (!defaults.guardrailIds().isEmpty()) {
            agent.setEnabledGuardrails(defaults.guardrailIds());
        }
        agent.setSamplingParams(
                request.topK(),
                request.stopSequences(),
                request.frequencyPenalty(),
                request.presencePenalty(),
                request.seed());

        // Edge Wave 2 (EDGE-B): optional server routing policy for on-device
        // turns - null (the default) keeps device-preference routing, exactly
        // like every agent created before migration V190. No plan gate on
        // purpose: unlike voice/video provider selection below, a routing
        // policy only ever REDUCES what the platform serves (EDGE_ONLY) or
        // pins today's default (EDGE_FIRST/HOSTED_ONLY) - there is nothing to
        // paywall.
        agent.setEdgeRoutingPolicy(request.edgeRoutingPolicy());

        // WB-008 fix: voice/video model selection at creation time. Mirrors the exact
        // plan-gating and provider/model validation that #updateVoiceModel/#updateVideoModel
        // enforce on the PATCH path - without this, a FREE tenant (or an invalid
        // provider/model pair) could set these fields straight through create() and
        // bypass the paywall/validation those methods already enforce for existing agents.
        if (request.voiceModelProvider() != null) {
            if (isFreePlan) {
                throw new TenantLimitExceededException(
                        "Voice provider selection is a Premium feature - the FREE plan always uses"
                            + " the default transcription chain (Sidecar Whisper → Sarvam). Upgrade"
                            + " your plan to choose a specific voice provider.");
            }
            try {
                validators.voice().validate(request.voiceModelProvider(), request.voiceModel());
            } catch (IllegalArgumentException e) {
                throw new AgentModelNotSupportedException(e.getMessage());
            }
            agent.setVoiceModelProvider(request.voiceModelProvider(), request.voiceModel());
        }
        if (request.videoModelProvider() != null) {
            if (isFreePlan) {
                throw new TenantLimitExceededException(
                        "Video provider selection is a Premium feature - the FREE plan always uses"
                                + " the default transcription chain. Upgrade your plan to choose a"
                                + " specific video provider.");
            }
            try {
                validators.video().validate(request.videoModelProvider(), request.videoModel());
            } catch (IllegalArgumentException e) {
                throw new AgentModelNotSupportedException(e.getMessage());
            }
            agent.setVideoModelProvider(request.videoModelProvider(), request.videoModel());
        }
        // TTS provider selector (Stage 2, 2026-08-09) - mirrors the voice/video
        // blocks above exactly, including the same plan-gating (Premium
        // feature) and create-time validation the PATCH path enforces.
        if (request.ttsProvider() != null) {
            if (isFreePlan) {
                throw new TenantLimitExceededException(
                        "TTS provider selection is a Premium feature - the FREE plan always uses"
                                + " the default synthesis chain (ElevenLabs → Sarvam). Upgrade your"
                                + " plan to choose a specific TTS provider.");
            }
            try {
                validators.tts().validate(request.ttsProvider(), request.ttsModel());
            } catch (IllegalArgumentException e) {
                throw new AgentModelNotSupportedException(e.getMessage());
            }
            agent.setTtsProvider(request.ttsProvider(), request.ttsModel());
        }

        agentSchemaService.refresh(agent);
        // v1 of this agent's config history is the configuration it was created
        // with. Without this row a brand-new agent would have nothing to roll back
        // TO until someone had already edited it twice - the first edit would
        // record the post-edit state, and the as-created state would be gone.
        return versioning.saveVersioned(agent, "Agent created");
    }

    /**
     * Subscription Plan &amp; GTM Strategy doc, Section 4, Decision 2: a FREE tenant may duplicate
     * a locked agent into a fresh, editable copy - the copy gets its own {@code createdAt} (a
     * brand-new {@link Agent}, not a clock override on the original), so it opens its own 24h grace
     * window, and creating it goes through the exact same {@code create}-style agent-count cap
     * check every other new agent does (via the constructor path below, not bypassing it) - it
     * still consumes a slot. Available to every plan, not just FREE - PREMIUM/UNLIMITED tenants are
     * never locked but may still find "duplicate this agent" a useful shortcut.
     *
     * <p>Same-tenant only - {@code requireAccess}/{@code repository} are both already scoped to the
     * caller's own tenant schema (schema-per-tenant, like every other read in this class), so there
     * is no cross-tenant id this could ever resolve even if one were passed. This is deliberately
     * NOT the cross-tenant community template/marketplace publish-and-clone flow (a separate,
     * already-claimed workstream) - a duplicate never leaves the tenant it was cloned from.
     *
     * <p>Channel bindings are deliberately never copied. A channel binding
     * (WhatsApp/Telegram/Slack/etc. routing to a specific agent id) lives entirely outside {@link
     * Agent} and is keyed off the ORIGINAL agent's id; building a brand-new {@link Agent} row here
     * and never touching that table is what keeps the copy unbound by construction - a duplicate
     * must never silently steal a live channel's inbound traffic from the agent it was copied from.
     *
     * <p>Field inventory reuses {@link AgentConfigSnapshotCodec#capture}/{@link
     * AgentConfigSnapshotCodec#apply} - the same capture/apply pair {@code
     * AgentExperimentService#buildTreatmentClone} uses to build its own hidden clone - rather than
     * a second hand-rolled field-by-field copy. The original implementation here predated several
     * {@link Agent} fields and silently omitted them from every duplicate (fallback provider, edge
     * routing policy, the four modality provider/model pairs, guardrails, autonomy tier, result
     * schema); routing through the codec means a future field added to {@link Agent} can only ever
     * miss BOTH duplicate and restore, never just one, since {@code AgentConfigSnapshotCodecTest
     * #captureThenApplyRoundTripsEveryRestorableField} already guards that codec against exactly
     * this drift.
     */
    @Transactional
    public AgentResponse duplicate(UUID agentId) {
        // Cross-user resource sharing retrofit (Round 2) - duplicating copies the
        // FULL config into a brand-new agent, so the caller needs at least
        // VIEWER-level visibility into the source (same bar as a plain read) -
        // no requirement that the source be EDITOR-accessible, matching the
        // pre-existing "even a locked FREE-tier agent can be duplicated" posture
        // this method already had for the edit-lock axis.
        Agent source = access.requireAccess(agentId, PermissionLevel.VIEWER);
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null) {
            Optional<Integer> maxAgents = tenantLimits.maxAgents(tenantId);
            if (maxAgents.isPresent() && repository.count() >= maxAgents.get()) {
                throw new TenantLimitExceededException(
                        "The "
                                + tenantLimits.plan(tenantId)
                                + " plan allows up to "
                                + maxAgents.get()
                                + " agents at a time - upgrade your plan, or delete an existing"
                                + " agent, to duplicate this one.");
            }
        }
        AgentConfigSnapshot snapshot = agentConfigSnapshotCodec.capture(source);
        // The base constructor call and the codec.apply() call below deliberately
        // overlap on several fields (systemPrompt, provider/model, generation
        // params, autonomy/tuning/reasoning, knowledge-base flag) - same
        // "constructor satisfies non-null columns, apply() makes it byte-identical"
        // split AgentExperimentService#buildTreatmentClone uses for its own clone.
        Agent copy =
                new Agent(
                        source.getName() + " (copy)",
                        source.getSystemPrompt(),
                        source.getProviderName(),
                        source.getModel(),
                        source.getMaxTokens(),
                        source.getTemperature(),
                        source.getTopP(),
                        source.isKnowledgeBaseEnabled(),
                        source.getExperienceMode(),
                        source.getReasoningDepth(),
                        source.getTuningMode());
        copy.setSamplingParams(
                source.getTopK(),
                source.getStopSequences(),
                source.getFrequencyPenalty(),
                source.getPresencePenalty(),
                source.getSeed());
        // Never skips a field here: capture() always writes every component off an
        // already-loaded Agent (see its own javadoc), so this apply() can never
        // hit the "absent from an older stored document" case RestoreOutcome
        // exists for - its return value is intentionally unused.
        agentConfigSnapshotCodec.apply(snapshot, copy);
        // Per-agent run cap (V421) is deliberately outside the snapshot, so it is carried
        // here: a copy must not silently run under the higher platform cap.
        copy.setRunBudgetUsd(source.getRunBudgetUsd());
        // Cross-user resource sharing retrofit (Round 2) - the copy is a brand-new
        // agent (its own id, its own createdAt/grace window - see this method's own
        // javadoc), so it gets its own owner too: whoever clicked duplicate, not
        // whatever the source's createdBy was (which stays untouched on the source).
        copy.setCreatedBy(TenantContext.current().map(TenantContext::getSubject).orElse(null));
        // The copy's tool/knowledge/model scope can differ from the source in
        // exactly zero ways right now (everything was just copied verbatim), but
        // the derived schema is still regenerated rather than copied - same
        // "never copy a platform-generated derived field" rule AgentConfigSnapshot
        // itself documents for inputSchemaJson/outputSchemaJson, and the same call
        // every other config-mutating method in this class makes before saving.
        agentSchemaService.refresh(copy);
        // The copy is a brand-new agent, so this is ITS v1 - not a version of the
        // source, whose own history is untouched.
        return versioning.saveVersioned(copy, "Duplicated from agent " + agentId);
    }
}
