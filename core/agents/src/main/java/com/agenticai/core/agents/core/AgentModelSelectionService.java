package com.agenticai.core.agents.core;

import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.dto.AgentTextModelUpdateRequest;
import com.agenticai.core.agents.dto.AgentTtsModelUpdateRequest;
import com.agenticai.core.agents.dto.AgentVideoModelUpdateRequest;
import com.agenticai.core.agents.dto.AgentVoiceModelUpdateRequest;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.modality.TextToSpeechProviderValidator;
import com.agenticai.core.providers.modality.VideoProviderValidator;
import com.agenticai.core.providers.modality.VoiceProviderValidator;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider/model selection for an existing agent: primary model, fallback provider, cost routing,
 * the voice/TTS/video/text modality providers and the Consumer/Professional/Expert tier presets.
 * All of them are Premium-gated (FREE tenants are rejected before any catalog lookup). Moved
 * verbatim from {@link AgentService}, which keeps delegating methods so callers are unchanged.
 */
@Service
public class AgentModelSelectionService {

    private final AgentAccessService access;
    private final AgentVersioningService versioning;
    private final TenantLimits tenantLimits;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final AgentProviderValidators validators;
    private final AgentSchemaService agentSchemaService;

    public AgentModelSelectionService(
            AgentAccessService access,
            AgentVersioningService versioning,
            TenantLimits tenantLimits,
            ProviderModelCatalogService providerModelCatalogService,
            AgentProviderValidators validators,
            AgentSchemaService agentSchemaService) {
        this.access = access;
        this.versioning = versioning;
        this.tenantLimits = tenantLimits;
        this.providerModelCatalogService = providerModelCatalogService;
        this.validators = validators;
        this.agentSchemaService = agentSchemaService;
    }

    /**
     * Unlimited-plan capability - change which provider/model an existing agent talks to. Gated by
     * {@code assertEditable}, same as every other after-creation PATCH on this resource
     * (generation-params, tuning-mode, reasoning-depth, etc.) - a FREE-tier agent past its 24h
     * grace window can't have its model swapped either, same as it can't have anything else
     * changed.
     *
     * <p>Model validation splits on {@code usesPlatformLlm}:
     *
     * <ul>
     *   <li><b>Platform-LLM / FREE</b> tenants don't choose their own model (their agent's
     *       provider/model is ignored at chat time for the hosted pool), so the curated {@link
     *       ProviderModelCatalogService} stays a hard allowlist for them - an unrecognized pair is
     *       rejected with {@link AgentModelNotSupportedException}, unchanged. This also caught a
     *       real production crash where the web console's "Change model" editor let a provider
     *       dropdown change (e.g. ANTHROPIC to GEMINI) go out without resetting the free-text model
     *       field, silently persisting {@code providerName=GEMINI, model="claude-sonnet-4-5"}; that
     *       mismatch only surfaced later at chat time as an opaque upstream 404 instead of an
     *       immediate, actionable error.
     *   <li><b>BYOK</b> tenants (PREMIUM/UNLIMITED not on the platform pool) drive their own key,
     *       so the catalog is only the UI's suggestion list, not an allowlist: any non-blank model
     *       string is accepted for the requested provider. A paid tenant's own key may support
     *       models the platform's hand-maintained seed list hasn't caught up to yet (new vendor
     *       releases, fine-tunes, region-specific ids) - rejecting those purely for absence from a
     *       curated list would be wrong. A blank model is still rejected. Provider-activation rules
     *       are unchanged and enforced at chat time (the tenant still needs a usable key/credential
     *       for {@code providerName}); this method validates the model string only.
     * </ul>
     */
    @Transactional
    public AgentResponse updateModel(
            UUID agentId, com.agenticai.core.providers.ProviderName providerName, String model) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);
        // FREE tenants always route chat through the platform-hosted pool
        // regardless of what's saved here, so model selection is a Premium
        // feature - reject outright (direct feedback: "disable model selection
        // for free users"). Paid tenants who opted into the shared pool
        // (usePlatformLlm) keep the catalog as a hard allowlist (their model is
        // still pool-routed); paid BYOK tenants may set ANY non-blank model -
        // the catalog is only a suggestion list for them ("activate any
        // provider/LLM, no restrictions"). access.usesPlatformLlm() is true for FREE
        // too, but the FREE throw above means it's only reached for paid tenants.
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        if (plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                    "Model selection is a Premium feature - the FREE plan always uses the"
                        + " platform's shared hosted models at no extra cost. Upgrade your plan to"
                        + " choose your own provider/model.");
        }
        if (access.usesPlatformLlm(tenantId)) {
            if (providerModelCatalogService.findModel(providerName, model).isEmpty()) {
                throw new AgentModelNotSupportedException(
                        "\""
                                + model
                                + "\" isn't a recognized model for "
                                + providerName
                                + " - pick one from that provider's model list.");
            }
        } else if (!org.springframework.util.StringUtils.hasText(model)) {
            throw new AgentModelNotSupportedException(
                    "A model id is required for " + providerName + ".");
        }
        agent.setProviderModel(providerName, model);
        agentSchemaService.refresh(agent);
        return versioning.saveVersioned(agent, "Changed model to " + providerName + "/" + model);
    }

    /**
     * Direct feedback: "set a fallback provider/model so future provider outages degrade gracefully
     * instead of failing the run." Same edit-lock gate and catalog validation as {@link
     * #updateModel} - a fallback pointing at a model that doesn't exist would just move the "run
     * fails" problem from the primary provider to the fallback one. {@code providerName == null}
     * clears the fallback (see {@link com.agenticai.core.agents.dto.AgentFallbackUpdateRequest}'s
     * javadoc).
     */
    @Transactional
    public AgentResponse setFallbackProvider(
            UUID agentId, com.agenticai.core.providers.ProviderName providerName, String model) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        // WB-025 fix: same FREE-plan gate as updateModel - defense-in-depth consistency
        // with its sibling update methods (a FREE tenant's chat never reaches this
        // setting anyway, but the server should still reject the write like its
        // siblings do rather than silently accepting it).
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        if (plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                    "Fallback provider selection is a Premium feature - the FREE plan always uses"
                        + " the platform's shared hosted models at no extra cost. Upgrade your plan"
                        + " to configure a fallback provider/model.");
        }
        if (providerName != null) {
            if (!org.springframework.util.StringUtils.hasText(model)) {
                throw new AgentModelNotSupportedException(
                        "model is required when fallbackProviderName is set");
            }
            // Same catalog posture as updateModel: an allowlist only for platform-LLM/
            // FREE tenants; a BYOK tenant may point its fallback at any model its own
            // key supports (the catalog is a suggestion list, not an allowlist).
            if (access.usesPlatformLlm(tenantId)
                    && providerModelCatalogService.findModel(providerName, model).isEmpty()) {
                throw new AgentModelNotSupportedException(
                        "\""
                                + model
                                + "\" isn't a recognized model for "
                                + providerName
                                + " - pick one from that provider's model list.");
            }
            if (providerName == agent.getProviderName() && model.equals(agent.getModel())) {
                throw new AgentModelNotSupportedException(
                        "The fallback provider/model must be different from the primary"
                                + " provider/model - otherwise a primary-provider outage would just"
                                + " fail again on retry.");
            }
        }
        agent.setFallbackProvider(providerName, model);
        return versioning.saveVersioned(
                agent,
                providerName == null
                        ? "Cleared fallback provider"
                        : "Changed fallback provider to " + providerName + "/" + model);
    }

    /**
     * #1077 fast-follow (Master Roadmap #18, cost optimization: routing) - opt this agent into
     * RouteLLM/NotDiamond-style per-turn routing to a cheaper "economy" model, and configure which
     * one. Same edit-lock gate and catalog validation as {@code setFallbackProvider} - an economy
     * model pointing at a model that doesn't exist would mean the discount never actually applies.
     * Unlike {@code setFallbackProvider}, routing to the SAME provider/model as the agent's
     * primary is allowed (simply a no-op economically, not an error) rather than rejected - a
     * tenant turning the feature on before picking a genuinely cheaper model isn't a mistake worth
     * blocking. {@code economyProviderName == null} clears the economy-model pairing regardless of
     * {@code enabled}'s value - see {@link Agent#setCostRouting}'s javadoc.
     */
    @Transactional
    public AgentResponse setCostRouting(
            UUID agentId,
            boolean enabled,
            com.agenticai.core.providers.ProviderName economyProviderName,
            String economyModel,
            Double jevThreshold) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        // WB-025 fix: same FREE-plan gate as updateModel - defense-in-depth consistency
        // with its sibling update methods (a FREE tenant's chat never reaches this
        // setting anyway, but the server should still reject the write like its
        // siblings do rather than silently accepting it).
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        if (plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                    "Cost routing is a Premium feature - the FREE plan always uses the platform's"
                        + " shared hosted models at no extra cost. Upgrade your plan to configure"
                        + " economy-model routing.");
        }
        if (economyProviderName != null) {
            if (!org.springframework.util.StringUtils.hasText(economyModel)) {
                throw new AgentModelNotSupportedException(
                        "economyModel is required when economyProviderName is set");
            }
            // Same catalog posture as updateModel: an allowlist only for platform-LLM/
            // FREE tenants; a BYOK tenant may route to any economy model its own key
            // supports (the catalog is a suggestion list, not an allowlist).
            if (access.usesPlatformLlm(tenantId)
                    && providerModelCatalogService
                            .findModel(economyProviderName, economyModel)
                            .isEmpty()) {
                throw new AgentModelNotSupportedException(
                        "\""
                                + economyModel
                                + "\" isn't a recognized model for "
                                + economyProviderName
                                + " - pick one from that provider's model list.");
            }
        }
        agent.setCostRouting(enabled, economyProviderName, economyModel, jevThreshold);
        return versioning.saveVersioned(
                agent,
                (enabled ? "Enabled" : "Disabled")
                        + " cost routing"
                        + (economyProviderName == null
                                ? ""
                                : " (" + economyProviderName + "/" + economyModel + ")"));
    }

    /**
     * Multi-provider voice/video/text architecture foundation - selects the speech-to-text provider
     * for this agent's voice turns. When set, voice turns route through the selected provider
     * (e.g., Sarvam, Sidecar Whisper) instead of the voice endpoint's default fallback chain.
     *
     * <p>Same edit-lock gate as {@code updateModel} (Premium feature for UNLIMITED tenants only -
     * FREE tenants can't customize agent providers at all). The selected provider must be available
     * on this deployment at request time - {@link
     * com.agenticai.core.providers.modality.VoiceProviderValidator} checks this and rejects
     * unavailable providers.
     *
     * <p>Both provider and model must be set together. {@code provider == null} clears the
     * selection and reverts to the default fallback chain (Sidecar → Sarvam → error).
     */
    @Transactional
    public AgentResponse updateVoiceModel(UUID agentId, AgentVoiceModelUpdateRequest request) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);

        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;

        // Voice provider selection is a Premium feature - FREE tenants always use
        // the default fallback chain regardless of what's saved here, same as LLM
        // provider selection.
        if (plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                    "Voice provider selection is a Premium feature - the FREE plan always uses the"
                        + " default transcription chain (Sidecar Whisper → Sarvam). Upgrade your"
                        + " plan to choose a specific voice provider.");
        }

        // Validate the provider/model selection before persisting
        try {
            validators.voice().validate(request.voiceModelProvider(), request.voiceModel());
        } catch (IllegalArgumentException e) {
            throw new AgentModelNotSupportedException(e.getMessage());
        }

        agent.setVoiceModelProvider(request.voiceModelProvider(), request.voiceModel());
        return versioning.saveVersioned(
                agent,
                request.voiceModelProvider() == null
                        ? "Cleared voice (speech-to-text) provider"
                        : "Changed voice provider to "
                                + request.voiceModelProvider()
                                + "/"
                                + request.voiceModel());
    }

    /**
     * Updates this agent's text-to-speech provider selection - output-side twin of {@link
     * #updateVoiceModel}, same shape, same Premium-feature gating. {@code provider == null} clears
     * the selection and reverts to the default ElevenLabs-then-Sarvam-then-text-only fallback chain
     * ({@code VoiceCallController#synthesizeSpeech}).
     *
     * <p>Both provider and model must be set together. The provider must be available on this
     * deployment - {@link TextToSpeechProviderValidator} checks this and rejects unavailable
     * providers.
     */
    @Transactional
    public AgentResponse updateTtsModel(UUID agentId, AgentTtsModelUpdateRequest request) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);

        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;

        if (plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                    "TTS provider selection is a Premium feature - the FREE plan always uses the "
                            + "default synthesis chain (ElevenLabs → Sarvam). Upgrade your plan to "
                            + "choose a specific TTS provider.");
        }

        try {
            validators.tts().validate(request.ttsProvider(), request.ttsModel());
        } catch (IllegalArgumentException e) {
            throw new AgentModelNotSupportedException(e.getMessage());
        }

        agent.setTtsProvider(request.ttsProvider(), request.ttsModel());
        return versioning.saveVersioned(
                agent,
                request.ttsProvider() == null
                        ? "Cleared TTS provider"
                        : "Changed TTS provider to "
                                + request.ttsProvider()
                                + "/"
                                + request.ttsModel());
    }

    /**
     * Updates this agent's video model provider selection. Allows agents to route video-to-text
     * transcription through a specific provider (OpenAI Whisper Video, Sidecar Whisper Video, etc.)
     * instead of using the platform's default fallback chain.
     *
     * <p>Same edit-lock gate as {@code updateModel} (Premium feature for UNLIMITED tenants only -
     * FREE tenants can't customize agent video providers at all). The selected provider must be
     * available on this deployment at request time - {@link
     * com.agenticai.core.providers.modality.VideoProviderValidator} checks this and rejects
     * unavailable providers.
     *
     * <p>Both provider and model must be set together. {@code provider == null} clears the
     * selection and reverts to the default fallback chain (platform default or agent-specific
     * chain).
     */
    @Transactional
    public AgentResponse updateVideoModel(UUID agentId, AgentVideoModelUpdateRequest request) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);

        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;

        // Video provider selection is a Premium feature - FREE tenants always use
        // the default fallback chain regardless of what's saved here, same as LLM
        // provider selection.
        if (plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                    "Video provider selection is a Premium feature - the FREE plan always uses the"
                            + " default transcription chain. Upgrade your plan to choose a specific"
                            + " video provider.");
        }

        // Validate the provider/model selection before persisting
        try {
            validators.video().validate(request.videoModelProvider(), request.videoModel());
        } catch (IllegalArgumentException e) {
            throw new AgentModelNotSupportedException(e.getMessage());
        }

        agent.setVideoModelProvider(request.videoModelProvider(), request.videoModel());
        return versioning.saveVersioned(
                agent,
                request.videoModelProvider() == null
                        ? "Cleared video provider"
                        : "Changed video provider to "
                                + request.videoModelProvider()
                                + "/"
                                + request.videoModel());
    }

    /**
     * Updates this agent's text model provider selection. Allows agents to route text/LLM
     * completions through a specific provider (ANTHROPIC, OPENAI, GEMINI, etc.) instead of using
     * the agent's primary provider.
     *
     * <p>Text provider selection is a Premium/Unlimited feature - FREE tenants cannot select a
     * custom text provider and always use the platform's hosted LLM pool. Both provider and model
     * must be set together; null values clear the selection and revert to the agent's primary
     * provider.
     *
     * <p>The selected provider must be available on this deployment at the time of the request -
     * this method validates that and rejects unavailable providers.
     *
     * @param agentId the agent to update
     * @param request the text provider/model selection (or null to clear)
     * @return updated agent response
     * @throws TenantLimitExceededException if FREE plan tries to select a custom provider
     * @throws AgentModelNotSupportedException if the selected provider is unavailable
     */
    @Transactional
    public AgentResponse updateTextModel(UUID agentId, AgentTextModelUpdateRequest request) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);

        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;

        // Text provider selection is a Premium/Unlimited feature - FREE tenants
        // always use the platform's hosted LLM pool regardless of what's saved here.
        if (request.textModelProvider() != null && plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                    "Text model provider selection is a Premium feature - the FREE plan always uses"
                        + " the platform's hosted LLM pool. Upgrade your plan to choose a specific"
                        + " text provider.");
        }

        // Validate the provider/model selection before persisting
        try {
            validators.text().validate(request.textModelProvider(), request.textModel());
        } catch (IllegalArgumentException e) {
            throw new AgentModelNotSupportedException(e.getMessage());
        }

        agent.setTextModelProvider(request.textModelProvider(), request.textModel());
        agentSchemaService.refresh(agent);
        return versioning.saveVersioned(
                agent,
                request.textModelProvider() == null
                        ? "Cleared text model provider (back to the primary provider)"
                        : "Changed text model provider to "
                                + request.textModelProvider()
                                + "/"
                                + request.textModel());
    }

    // Per-tier reasoning-depth/autonomy-tier presets applied by applyTier()
    // below - see that method's javadoc for the full rationale.
    private static final java.util.Map<AgentTier, ReasoningDepth> TIER_REASONING_DEPTH =
            java.util.Map.of(
                    AgentTier.CONSUMER, ReasoningDepth.FAST,
                    AgentTier.PROFESSIONAL, ReasoningDepth.CAREFUL,
                    AgentTier.EXPERT, ReasoningDepth.PLANNED);

    private static final java.util.Map<AgentTier, AgentAutonomyTier> TIER_AUTONOMY =
            java.util.Map.of(
                    AgentTier.CONSUMER, AgentAutonomyTier.SEMI_AUTONOMOUS,
                    AgentTier.PROFESSIONAL, AgentAutonomyTier.SEMI_AUTONOMOUS,
                    AgentTier.EXPERT, AgentAutonomyTier.SUPERVISED);

    /**
     * Hand-maintained per-provider, per-tier model picks - same "curated by hand, refreshed by the
     * same pipeline as everything else" approach {@code ProviderModelCatalogService.SEED_MODELS}
     * already uses, kept here (not there) because {@code core.providers} cannot depend on {@link
     * AgentTier} (core.agents depends on core.providers, not the reverse - see {@code TenantPlan}'s
     * javadoc for why this direction is fixed). {@code OPENROUTER} and {@code PLATFORM_HOSTED} are
     * deliberately absent: OpenRouter's catalog is fetched live (no stable "top vs. cheap" id to
     * hand-pick), and platform-hosted agents don't choose their own model at all. For both,
     * applying a tier still updates reasoning depth/autonomy tier, just leaves the model untouched.
     */
    private static final java.util.Map<
                    com.agenticai.core.providers.ProviderName, java.util.Map<AgentTier, String>>
            TIER_MODELS =
                    java.util.Map.of(
                            com.agenticai.core.providers.ProviderName.ANTHROPIC,
                                    java.util.Map.of(
                                            AgentTier.CONSUMER, "claude-haiku-4-5-20251001",
                                            AgentTier.PROFESSIONAL, "claude-sonnet-5",
                                            AgentTier.EXPERT, "claude-opus-4-8"),
                            com.agenticai.core.providers.ProviderName.OPENAI,
                                    java.util.Map.of(
                                            AgentTier.CONSUMER, "gpt-4o-mini",
                                            AgentTier.PROFESSIONAL, "gpt-4o",
                                            AgentTier.EXPERT, "gpt-5.1"),
                            com.agenticai.core.providers.ProviderName.GEMINI,
                                    java.util.Map.of(
                                            AgentTier.CONSUMER, "gemini-3.1-flash-lite",
                                            AgentTier.PROFESSIONAL, "gemini-3.5-flash",
                                            AgentTier.EXPERT, "gemini-2.5-pro"),
                            // Grok 3.x/4.0 line retired by xAI 2026-05-15 - tier picks moved
                            // to the current lineup (see ProviderModelCatalogService's GROK
                            // seed comment and the V182 catalog repair).
                            com.agenticai.core.providers.ProviderName.GROK,
                                    java.util.Map.of(
                                            AgentTier.CONSUMER, "grok-4.20-0309-non-reasoning",
                                            AgentTier.PROFESSIONAL, "grok-4.3",
                                            AgentTier.EXPERT, "grok-4.5"),
                            com.agenticai.core.providers.ProviderName.SARVAM,
                                    java.util.Map.of(
                                            AgentTier.CONSUMER, "sarvam-105b",
                                            AgentTier.PROFESSIONAL, "sarvam-105b",
                                            AgentTier.EXPERT, "sarvam-105b"),
                            // Meta Model API ships ONE chat family (Muse Spark) whose 1.1/
                            // 1.2/1.3 ids are successive releases, not quality tiers, and the
                            // only cheaper option ("-contributor") is cheaper because Meta
                            // trains on the traffic - never an acceptable tier default. So
                            // every tier pins the current recommended release, same shape as
                            // SARVAM above.
                            com.agenticai.core.providers.ProviderName.META,
                                    java.util.Map.of(
                                            AgentTier.CONSUMER, "muse-spark-1.3",
                                            AgentTier.PROFESSIONAL, "muse-spark-1.3",
                                            AgentTier.EXPERT, "muse-spark-1.3"));

    /**
     * Direct feedback: "give this as an extra feature instead of [changing] the simple workflow...
     * make it available in premium and unlimited." One-click preset that pre-fills three
     * already-independent fields ({@code Agent.model} within its current provider, {@link
     * ReasoningDepth}, {@link AgentAutonomyTier}) via the exact same mechanics {@link
     * #updateModel}/{@code setReasoningDepth}/{@code setAutonomyTier} already use - see {@link
     * AgentTier}'s javadoc for why nothing new is persisted to mean "this agent is Professional
     * tier." Deliberately does NOT touch {@code providerName} - a tier is a quality/behavior preset
     * for whichever provider the tenant already activated, not a provider picker.
     */
    @Transactional
    public AgentResponse applyTier(UUID agentId, AgentTier tier) {
        Agent agent = access.requireAccess(agentId, PermissionLevel.EDITOR);
        access.assertEditable(agent);

        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        if (plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                    "Tier presets (Consumer/Professional/Expert quick setup) are a Premium feature"
                        + " - this agent can still be configured manually (model, reasoning depth,"
                        + " autonomy) at no extra cost, or upgrade your plan to use one-click"
                        + " presets.");
        }

        java.util.Map<AgentTier, String> providerTierModels =
                TIER_MODELS.get(agent.getProviderName());
        String suggestedModel = providerTierModels != null ? providerTierModels.get(tier) : null;
        if (suggestedModel != null
                && !suggestedModel.equals(agent.getModel())
                && providerModelCatalogService
                        .findModel(agent.getProviderName(), suggestedModel)
                        .isPresent()) {
            agent.setProviderModel(agent.getProviderName(), suggestedModel);
        }
        agent.setReasoningDepth(TIER_REASONING_DEPTH.get(tier));
        agent.setAutonomyTier(TIER_AUTONOMY.get(tier));
        // One row for the whole preset, not three. A tier is a single tenant
        // action that happens to move three fields, and rolling back "I clicked
        // Expert" should undo all three together.
        return versioning.saveVersioned(agent, "Applied " + tier + " tier preset");
    }
}
