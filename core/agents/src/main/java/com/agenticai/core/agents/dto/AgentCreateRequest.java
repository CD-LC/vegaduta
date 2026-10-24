package com.agenticai.core.agents.dto;

import com.agenticai.core.agents.core.EdgeRoutingPolicy;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.modality.TtsModelProvider;
import com.agenticai.core.providers.modality.VideoModelProvider;
import com.agenticai.core.providers.modality.VoiceModelProvider;
import com.agenticai.core.security.ExperienceMode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * model/maxTokens/temperature/topP are optional — sensible defaults are
 * applied in AgentService if omitted (see app.agents.default-* properties).
 * knowledgeBaseEnabled defaults to false if omitted (Stage 6) — see
 * PATCH /api/agents/{id}/knowledge-base to change it after creation.
 *
 * <p>{@code experienceMode} is optional and null by default, meaning
 * "inherit the tenant's current Simple/Advanced default" - passing SIMPLE
 * explicitly forces {@code knowledgeBaseEnabled} on and clears any
 * {@code allowedToolNames} restriction at creation time, see
 * {@code AgentService#create}.
 *
 * <p>{@code reasoningDepth} is optional and null by default, meaning FAST
 * (today's unchanged behavior) - see {@link ReasoningDepth}'s javadoc.
 *
 * <p>{@code tuningMode} is optional and null by default, meaning MANUAL
 * (today's unchanged behavior: temperature/topP/maxTokens above are used
 * exactly as given/defaulted). Passing {@code SIMPLE} makes AgentService
 * ignore this request's temperature/topP/maxTokens (if any) and infer them
 * from {@code systemPrompt} instead - see {@code TuningMode}'s javadoc.
 *
 * <p>{@code edgeRoutingPolicy} is optional and null by default, meaning "no server policy -
 * the device's own edge preferences decide" (today's unchanged behavior) - see {@link
 * EdgeRoutingPolicy}'s javadoc. Change it after creation via
 * {@code PATCH /api/agents/{id}/edge-routing-policy}.
 *
 * <p>{@code voiceModelProvider}/{@code voiceModel} and {@code
 * videoModelProvider}/{@code videoModel} are optional and null by default,
 * meaning "use the default fallback chain" - same semantics as {@code
 * AgentVoiceModelUpdateRequest}/{@code AgentVideoModelUpdateRequest}. When
 * present, {@code AgentService#create} applies the exact same Premium-plan
 * gate and provider/model validation that {@code #updateVoiceModel}/{@code
 * #updateVideoModel} enforce, so setting these at creation time can't bypass
 * the paywall those PATCH endpoints already enforce.
 */
public record AgentCreateRequest(
    // 255 matches the agent.name VARCHAR - an over-long name used to surface
    // as a DB error/500 instead of a 400 (audit 2026-10-02).
    @NotBlank(message = "name is required")
    @Size(max = 255, message = "name must be at most 255 characters") String name,
    // systemPrompt is a TEXT column (unbounded), but the cap is a DoS/
    // prompt-bloat control like ChatRequest's 8000-char message cap - 32k
    // chars is far past any legitimate system prompt.
    @NotBlank(message = "systemPrompt is required")
    @Size(max = 32768, message = "systemPrompt must be at most 32768 characters") String systemPrompt,
    @NotNull(message = "providerName is required") ProviderName providerName,
    String model,
    // Upper bound matches AgentGenerationParamsUpdateRequest's own - see its
    // javadoc for the 2026-08-10 incident this fixes (an unbounded maxTokens
    // gets reserved wholesale against the tenant's daily hosted-pool token
    // budget on every turn, rejecting turns regardless of actual usage).
    @Positive @MaxTokensCeiling Integer maxTokens,
    @DecimalMin(value = "0.0", message = "temperature must be between 0 and 1")
    @DecimalMax(value = "1.0", message = "temperature must be between 0 and 1")
    Double temperature,
    @DecimalMin(value = "0.0", message = "topP must be between 0 and 1")
    @DecimalMax(value = "1.0", message = "topP must be between 0 and 1")
    Double topP,
    Boolean knowledgeBaseEnabled,
    ExperienceMode experienceMode,
    ReasoningDepth reasoningDepth,
    TuningMode tuningMode,
    EdgeRoutingPolicy edgeRoutingPolicy,
    VoiceModelProvider voiceModelProvider,
    String voiceModel,
    VideoModelProvider videoModelProvider,
    String videoModel,
    // Text-to-speech (TTS) provider selection - output-side twin of
    // voiceModelProvider/voiceModel above. Optional, null by default,
    // meaning "use the default ElevenLabs-then-Sarvam fallback chain".
    TtsModelProvider ttsProvider,
    String ttsModel,
    // Extra sampling controls (migration V331) - optional, null/empty = provider default.
    @Min(value = 1, message = "topK must be between 1 and 1000")
    @Max(value = 1000, message = "topK must be between 1 and 1000")
    Integer topK,
    @Size(max = 8, message = "stopSequences may hold at most 8 entries")
    List<@NotBlank(message = "stopSequences entries must not be blank")
        @Size(max = 64, message = "each stop sequence must be at most 64 characters") String> stopSequences,
    @DecimalMin(value = "-2.0", message = "frequencyPenalty must be between -2 and 2")
    @DecimalMax(value = "2.0", message = "frequencyPenalty must be between -2 and 2")
    Double frequencyPenalty,
    @DecimalMin(value = "-2.0", message = "presencePenalty must be between -2 and 2")
    @DecimalMax(value = "2.0", message = "presencePenalty must be between -2 and 2")
    Double presencePenalty,
    Integer seed
) {
    /** Pre-V331 arity - callers that do not set the extra sampling controls. */
    public AgentCreateRequest(
        String name, String systemPrompt, ProviderName providerName, String model, Integer maxTokens,
        Double temperature, Double topP, Boolean knowledgeBaseEnabled, ExperienceMode experienceMode,
        ReasoningDepth reasoningDepth, TuningMode tuningMode, EdgeRoutingPolicy edgeRoutingPolicy,
        VoiceModelProvider voiceModelProvider, String voiceModel, VideoModelProvider videoModelProvider,
        String videoModel, TtsModelProvider ttsProvider, String ttsModel
    ) {
        this(name, systemPrompt, providerName, model, maxTokens, temperature, topP, knowledgeBaseEnabled,
            experienceMode, reasoningDepth, tuningMode, edgeRoutingPolicy, voiceModelProvider, voiceModel,
            videoModelProvider, videoModel, ttsProvider, ttsModel, null, null, null, null, null);
    }

    /**
     * Trims {@code model}, collapsing blank to {@code null} - same reasoning as
     * {@link AgentModelUpdateRequest}'s own compact constructor (a padded model
     * id is sent verbatim to the provider and rejected). Blank becomes {@code
     * null} specifically so it takes the documented "no explicit model" path in
     * {@code AgentService#create} (which picks a real id from that provider's
     * catalog) rather than persisting {@code ""}, which is neither a usable
     * model id nor null enough for {@code AgentService#resolveEffectiveModel}'s
     * self-heal to notice.
     */
    public AgentCreateRequest {
        if (model != null) {
            String trimmed = model.trim();
            model = trimmed.isEmpty() ? null : trimmed;
        }
    }
}
