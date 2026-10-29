package com.agenticai.core.providers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One platform-held (not BYOK) LLM endpoint backing the FREE subscription tier's chat traffic - see
 * migration {@code V44__platform_hosted_llm_provider.sql} for the full "why this exists / why it's
 * data instead of one factory class per provider" reasoning.
 *
 * <p>Lives in {@code control_plane}, same as {@code com.agenticai.core.tenancy.Tenant} and {@code
 * com.agenticai.core.platform.PlatformLlmTaskConfig} - platform-wide configuration, not owned by
 * any one tenant's schema. Placed in {@code core.providers} rather than {@code core.platform}
 * specifically so {@code core.agents}' {@code AgentChatService} can read it directly: {@code
 * core.agents} already depends on {@code core.providers} (for {@code LlmProviderCredential}/{@code
 * ProviderCredentialResolver}) but {@code core.platform} depends on {@code core.agents}, not the
 * other way around - putting this here avoids a module cycle. {@code core.platform}'s {@code
 * PlatformController} (which already depends on {@code core.providers}) exposes the CRUD admin
 * endpoints over it.
 *
 * <p>{@code encryptedApiKey} is ciphertext only (AES-256-GCM via {@link
 * com.agenticai.core.crypto.EncryptionService}), same posture as every other stored key in this
 * codebase - never persisted in plaintext, never serialized directly in an API response (see {@link
 * PlatformHostedLlmProviderResponse}).
 */
@com.agenticai.core.security.PlatformConfigEntity
@Entity
@Table(name = "platform_hosted_llm_provider", schema = "control_plane")
public class PlatformHostedLlmProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String label;

    // Full OpenAI-chat-completions-compatible host+path prefix, e.g.
    // "https://api.groq.com/openai" - NOT including a trailing "/v1"; see
    // OpenRouterAgentChatClientFactory's BASE_URL comment for exactly why
    // (Spring AI's OpenAiApi already appends its own default completionsPath
    // onto baseUrl - a trailing "/v1" here would double up into
    // ".../v1/v1/chat/completions", the same production 404 already hit and
    // fixed once for OpenRouter/Grok). HostedLlmChatClientFactory's javadoc
    // repeats this warning at the point it's actually used.
    @Column(name = "base_url", nullable = false, length = 255)
    private String baseUrl;

    // Nullable. Set ONLY for providers whose OpenAI-compatible chat-completions
    // endpoint does NOT follow the conventional "{baseUrl}/v1/chat/completions"
    // shape that Spring AI's OpenAiApi assumes by default. The canonical case is
    // Google Gemini's OpenAI-compat surface, whose full endpoint is
    // "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    // - note there is no bare "/v1" segment, so the default completionsPath is
    // wrong for it. When this is non-null, HostedLlmChatClientFactory passes it
    // to OpenAiApi.builder().completionsPath(...) AND uses base_url verbatim
    // (the /v1-stripping normalizeBaseUrl is deliberately skipped, since the
    // whole point of a custom path is a non-standard layout). When null, the
    // row behaves exactly as before: default "/v1/chat/completions" path +
    // /v1-stripping normalization.
    @Column(name = "completions_path", length = 255)
    private String completionsPath;

    @Column(nullable = false, length = 128)
    private String model;

    // Nullable since V169 - a VERTEX_ADC row (see authType below) stores no
    // secret at all. Still the ciphertext home for a VERTEX_SERVICE_ACCOUNT
    // row's pasted service-account JSON, exactly like a plain API key -
    // PlatformHostedLlmProviderService encrypts/decrypts it identically
    // either way, see that class's create()/update().
    @Column(name = "encrypted_api_key", columnDefinition = "TEXT")
    private String encryptedApiKey;

    // #971 - admin-facing labeling only (e.g. "us-east", "apac", "eu-west"),
    // nullable/free-text - see migration V85's header comment for why this
    // isn't an enum or a real routing dimension: no tenant-level region
    // field exists yet to route against, so this exists purely so a
    // platform-admin can see/organize which provider serves which region.
    @Column(length = 64)
    private String region;

    @Column(nullable = false)
    private boolean enabled;

    // Lower = tried first. Lets an admin configure failover across multiple
    // enabled rows (e.g. Groq primary, Together AI fallback) with no code
    // change - see FreeTierProviderSelector (core/agents) for the actual
    // pick-highest-priority-enabled-row logic.
    @Column(nullable = false)
    private int priority;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    // #generic-config (2026-07-26) - which core.providers.ProviderName this
    // row was configured from, if any. Nullable: rows entered via a raw
    // base_url (Groq, Together AI, Cerebras, ...) never set this. Stored as
    // plain text (not @Enumerated) so an unrecognized/retired name never
    // fails to load a row - see PlatformHostedLlmProviderResponse, which
    // echoes it back verbatim. Pure admin-facing/auto-fill metadata - see
    // migration V136's header comment - not read by HostedLlmChatClientFactory.
    @Column(name = "provider_name", length = 64)
    private String providerName;

    // #generic-config (2026-07-26) - true only when `model` was chosen by
    // PlatformHostedLlmProviderService's auto-selection (blank model + a
    // known provider_name at save time) rather than hand-typed. Lets the
    // admin UI show an "(auto)" indicator and re-blank the field later to
    // force re-selection - the answer to a vendor deprecating the
    // previously-picked model id without the admin having to retype the
    // whole row.
    @Column(name = "model_auto_selected", nullable = false)
    private boolean modelAutoSelected;

    // V169 - which of PlatformHostedLlmAuthType this row activates with.
    // Plain text, not @Enumerated - see that class's javadoc for why an
    // unrecognized value must never fail to load a row. Never null in
    // practice: every constructor/apply overload below routes through
    // PlatformHostedLlmAuthType#normalize, which collapses null/blank to
    // API_KEY - the DB column also carries NOT NULL DEFAULT 'API_KEY'.
    @Column(name = "auth_type", nullable = false, length = 32)
    private String authType;

    // V169 - required when authType is a Vertex type, unused/null for API_KEY.
    @Column(name = "vertex_project_id", length = 128)
    private String vertexProjectId;

    // V169 - required when authType is a Vertex type; PlatformHostedLlmProviderService
    // defaults a blank value to PlatformHostedLlmAuthType.DEFAULT_VERTEX_LOCATION
    // ("us-central1") at save time. Unused/null for API_KEY.
    @Column(name = "vertex_location", length = 64)
    private String vertexLocation;

    // V484 - the operator runs this model themselves (Ollama, vLLM, llama.cpp,
    // any OpenAI-compatible server nobody bills per token). Calls are priced at
    // $0, never debit the per-user dollar budget, and - when every enabled row
    // is zero-cost - are not counted against the monthly hosted-pool token
    // quota either (see FreeTierChatClientService#buildChainForFreeTenant).
    // The failover order puts these rows first. Defaults to false; the
    // bootstrap and the OLLAMA provider choice set it to true.
    @Column(name = "zero_cost", nullable = false)
    private boolean zeroCost;

    // V499 - how this model makes tool calls, measured by ToolCallCapabilityProbe when the row
    // is saved: one of ToolCallGrade's names, or null when not measured (Vertex rows, a probe
    // that could not reach the server, rows saved before the column existed). Stored as text so
    // a value from a newer build never breaks an older one reading the same table.
    @Column(name = "tool_call_grade", length = 16)
    private String toolCallGrade;

    // V500 - what the probed server can enforce (ToolRouterMode's names), null when unknown.
    @Column(name = "tool_router_mode", length = 16)
    private String toolRouterMode;

    // V500 - admin override for the constrained tool router; null = derive from the grade.
    @Column(name = "tool_router_enabled")
    private Boolean toolRouterEnabled;

    // V501 - the model's size as the server reports it, null when unknown.
    @Column(name = "parameter_billions")
    private Double parameterBillions;

    // V501 - small-model mode override; null = derive from the size (see effectiveSmallModel).
    @Column(name = "small_model")
    private Boolean smallModel;

    // V611 - console-editable local model behaviour; null = the configured default.
    @Column(name = "reasoning_effort", length = 16)
    private String reasoningEffort;

    @Column(name = "keep_alive", length = 16)
    private String keepAlive;

    // V674 - console-editable local model behaviour; null = the server/configured default.
    // num_batch: tokens per prompt step (llama.cpp n_batch), a per-request option.
    @Column(name = "num_batch")
    private Integer numBatch;

    // num_parallel is deliberately NOT a column: like -ctxNk it lives in the tag ({base}-parN).

    // response_timeout_ms: this row's stream idle-timeout override.
    @Column(name = "response_timeout_ms")
    private Long responseTimeoutMs;

    // tool_deferral: true forces search_tools/call_tool on this row's turns.
    @Column(name = "tool_deferral")
    private Boolean toolDeferral;

    protected PlatformHostedLlmProvider() {
        // JPA
    }

    /**
     * Pre-generic-config shape, kept so every existing call site (tests, mainly) still compiles
     * unchanged.
     */
    public PlatformHostedLlmProvider(
            String label,
            String baseUrl,
            String model,
            String encryptedApiKey,
            boolean enabled,
            int priority,
            String region,
            String completionsPath) {
        this(
                label,
                baseUrl,
                model,
                encryptedApiKey,
                enabled,
                priority,
                region,
                completionsPath,
                null,
                false);
    }

    /**
     * Pre-V169 (pre-Vertex-auth) shape, kept so every existing call site still compiles unchanged -
     * defaults to authType API_KEY, no Vertex fields.
     */
    public PlatformHostedLlmProvider(
            String label,
            String baseUrl,
            String model,
            String encryptedApiKey,
            boolean enabled,
            int priority,
            String region,
            String completionsPath,
            String providerName,
            boolean modelAutoSelected) {
        this(
                label,
                baseUrl,
                model,
                encryptedApiKey,
                enabled,
                priority,
                region,
                completionsPath,
                providerName,
                modelAutoSelected,
                null,
                null,
                null);
    }

    public PlatformHostedLlmProvider(
            String label,
            String baseUrl,
            String model,
            String encryptedApiKey,
            boolean enabled,
            int priority,
            String region,
            String completionsPath,
            String providerName,
            boolean modelAutoSelected,
            String authType,
            String vertexProjectId,
            String vertexLocation) {
        this.label = label;
        this.baseUrl = baseUrl;
        this.model = model;
        this.encryptedApiKey = encryptedApiKey;
        this.enabled = enabled;
        this.priority = priority;
        this.region = region;
        this.completionsPath = completionsPath;
        this.providerName = providerName;
        this.modelAutoSelected = modelAutoSelected;
        this.authType = PlatformHostedLlmAuthType.normalize(authType);
        this.vertexProjectId = vertexProjectId;
        this.vertexLocation = vertexLocation;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Pre-generic-config shape, kept so every existing call site still compiles unchanged. */
    public void apply(
            String label,
            String baseUrl,
            String model,
            String newEncryptedApiKeyOrNull,
            boolean enabled,
            int priority,
            String region,
            String completionsPath) {
        apply(
                label,
                baseUrl,
                model,
                newEncryptedApiKeyOrNull,
                enabled,
                priority,
                region,
                completionsPath,
                null,
                false);
    }

    /**
     * Pre-V169 (pre-Vertex-auth) shape, kept so every existing call site still compiles unchanged -
     * resets authType to API_KEY, clears Vertex fields.
     */
    public void apply(
            String label,
            String baseUrl,
            String model,
            String newEncryptedApiKeyOrNull,
            boolean enabled,
            int priority,
            String region,
            String completionsPath,
            String providerName,
            boolean modelAutoSelected) {
        apply(
                label,
                baseUrl,
                model,
                newEncryptedApiKeyOrNull,
                enabled,
                priority,
                region,
                completionsPath,
                providerName,
                modelAutoSelected,
                null,
                null,
                null);
    }

    /**
     * Applies an admin edit - {@code newEncryptedApiKeyOrNull} is only applied when non-null, same
     * "blank/absent value leaves the stored key untouched" posture {@code
     * PlatformLlmTaskConfig#apply} already establishes, so editing just the label/priority/enabled
     * flag never wipes out an already-stored key (or, for a VERTEX_SERVICE_ACCOUNT row, the
     * already-stored service-account JSON).
     */
    public void apply(
            String label,
            String baseUrl,
            String model,
            String newEncryptedApiKeyOrNull,
            boolean enabled,
            int priority,
            String region,
            String completionsPath,
            String providerName,
            boolean modelAutoSelected,
            String authType,
            String vertexProjectId,
            String vertexLocation) {
        this.label = label;
        this.baseUrl = baseUrl;
        this.model = model;
        if (newEncryptedApiKeyOrNull != null) {
            this.encryptedApiKey = newEncryptedApiKeyOrNull;
        }
        this.enabled = enabled;
        this.priority = priority;
        this.region = region;
        this.completionsPath = completionsPath;
        this.providerName = providerName;
        this.modelAutoSelected = modelAutoSelected;
        this.authType = PlatformHostedLlmAuthType.normalize(authType);
        this.vertexProjectId = vertexProjectId;
        this.vertexLocation = vertexLocation;
        this.updatedAt = Instant.now();
    }

    public void markValidatedNow() {
        this.lastValidatedAt = Instant.now();
    }

    /**
     * Wipes any previously-stored secret - used when an admin switches a row to {@code VERTEX_ADC}
     * (see {@code PlatformHostedLlmAuthType}), which authenticates via Application Default
     * Credentials and stores no secret at all. Deliberately a separate call from {@link #apply},
     * whose {@code newEncryptedApiKeyOrNull} parameter only ever overwrites - never clears - a
     * stored key, so a row previously configured as {@code API_KEY}/{@code VERTEX_SERVICE_ACCOUNT}
     * doesn't leave an orphaned decryptable secret behind after switching to ADC.
     */
    public void clearEncryptedSecret() {
        this.encryptedApiKey = null;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    /**
     * Non-null only for non-standard OpenAI-compat endpoints (e.g. Gemini) - see this field's
     * javadoc.
     */
    public String getCompletionsPath() {
        return completionsPath;
    }

    public String getEncryptedApiKey() {
        return encryptedApiKey;
    }

    public String getRegion() {
        return region;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastValidatedAt() {
        return lastValidatedAt;
    }

    /** Nullable - see this field's javadoc. */
    public String getProviderName() {
        return providerName;
    }

    public boolean isModelAutoSelected() {
        return modelAutoSelected;
    }

    /** V484 - see the field's comment. */
    public boolean isZeroCost() {
        return zeroCost;
    }

    public void setZeroCost(boolean zeroCost) {
        this.zeroCost = zeroCost;
    }

    /** V499 - the measured tool-calling grade, or null when not measured. */
    public ToolCallGrade getToolCallGrade() {
        return ToolCallGrade.fromStored(toolCallGrade);
    }

    public void setToolCallGrade(ToolCallGrade grade) {
        this.toolCallGrade = grade == null ? null : grade.name();
    }

    /** True when the probe graded this row {@link ToolCallGrade#NONE}: chat only, no tool use. */
    public boolean isChatOnly() {
        return getToolCallGrade() == ToolCallGrade.NONE;
    }

    /** V500 - the router mode the probed server supports, or null when unknown. */
    public ToolRouterMode getToolRouterMode() {
        return ToolRouterMode.fromStored(toolRouterMode);
    }

    public void setToolRouterMode(ToolRouterMode mode) {
        this.toolRouterMode = mode == null ? null : mode.name();
    }

    /** V500 - the admin override, null when the grade decides. */
    public Boolean getToolRouterEnabled() {
        return toolRouterEnabled;
    }

    public void setToolRouterEnabled(Boolean toolRouterEnabled) {
        this.toolRouterEnabled = toolRouterEnabled;
    }

    /** V501 - billions of parameters as reported by the server, or null. */
    public Double getParameterBillions() {
        return parameterBillions;
    }

    public void setParameterBillions(Double parameterBillions) {
        this.parameterBillions = parameterBillions;
    }

    /** V501 - the admin override, null when the size decides. */
    public Boolean getSmallModel() {
        return smallModel;
    }

    public void setSmallModel(Boolean smallModel) {
        this.smallModel = smallModel;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public String getKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(String keepAlive) {
        this.keepAlive = keepAlive;
    }

    public Integer getNumBatch() {
        return numBatch;
    }

    public void setNumBatch(Integer numBatch) {
        this.numBatch = numBatch;
    }

    public Long getResponseTimeoutMs() {
        return responseTimeoutMs;
    }

    public void setResponseTimeoutMs(Long responseTimeoutMs) {
        this.responseTimeoutMs = responseTimeoutMs;
    }

    public Boolean getToolDeferral() {
        return toolDeferral;
    }

    public void setToolDeferral(Boolean toolDeferral) {
        this.toolDeferral = toolDeferral;
    }

    /**
     * Code default of {@code app.providers.small-model-max-billions}: models at or below this many
     * billions of parameters run in small-model mode unless the row says otherwise.
     */
    public static final double SMALL_MODEL_MAX_BILLIONS = 8.0;

    /**
     * The configured limit (SmallModelThresholdConfiguration sets it at start-up); the code default
     * until then.
     */
    private static volatile double smallModelMaxBillions = SMALL_MODEL_MAX_BILLIONS;

    /**
     * Sets the size limit of small-model mode ({@code app.providers.small-model-max-billions});
     * negative = never by size.
     */
    public static void useSmallModelMaxBillions(double maxBillions) {
        smallModelMaxBillions = maxBillions;
    }

    /** The size limit of small-model mode in effect. */
    public static double smallModelMaxBillions() {
        return smallModelMaxBillions;
    }

    /**
     * V501 - whether turns on this row run in small-model mode (at most six tools, compact
     * directives, FAST depth). The override wins; otherwise the reported size, then a size written
     * in the model tag ({@code llama3.2:3b}, {@code qwen3:8b}, {@code llama-3.1-8b-instant}); a
     * model of unknown size is not treated as small.
     */
    public boolean effectiveSmallModel() {
        if (smallModel != null) {
            return smallModel;
        }
        Double billions = effectiveParameterBillions();
        return billions != null && billions <= smallModelMaxBillions;
    }

    /**
     * The size this row is treated as: the measured value, unless the model tag names a size that
     * disagrees with it by more than half - then the tag wins. A measurement belongs to the model
     * it was taken on; a row repointed from {@code qwen3.5:9b} to {@code qwen3.5:4b} by a path that
     * did not re-measure (seen 2026-10-18: the 4B model kept 9.7B, ran RIGOROUS depth and timed out
     * every turn) must not keep the old model's settings. Null when neither source knows.
     */
    public Double effectiveParameterBillions() {
        Double tagged = parameterBillionsFromTag(model);
        if (parameterBillions == null) {
            return tagged;
        }
        if (tagged != null && Math.abs(parameterBillions - tagged) > tagged * 0.5) {
            return tagged;
        }
        return parameterBillions;
    }

    private static final java.util.regex.Pattern TAG_SIZE =
            java.util.regex.Pattern.compile(
                    "(?<![0-9.])([0-9]{1,3}(?:\\.[0-9])?)b(?![a-z0-9])",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** "qwen3.5:4b" -> 4.0, "llama-3.1-8b-instant" -> 8.0, "gemini-2.0-flash-lite" -> null. */
    public static Double parameterBillionsFromTag(String modelTag) {
        if (modelTag == null) {
            return null;
        }
        java.util.regex.Matcher m = TAG_SIZE.matcher(modelTag);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    /**
     * V500 - the mode the constrained tool router runs this row in, or null for the provider's own
     * tool loop. Explicit {@code false} wins; explicit {@code true} uses the detected mode, {@link
     * ToolRouterMode#FORMAT} when none was detected (every OpenAI-compatible server that honours
     * {@code response_format} works, one that ignores it replies in prose, which is the normal
     * fallback). Unset: on for a {@link ToolCallGrade#TEXT} or {@link ToolCallGrade#NONE} row whose
     * server has a detected mode, off for {@link ToolCallGrade#STRUCTURED} and unmeasured rows.
     */
    public ToolRouterMode effectiveToolRouterMode() {
        ToolRouterMode mode = getToolRouterMode();
        if (Boolean.FALSE.equals(toolRouterEnabled)) {
            return null;
        }
        if (Boolean.TRUE.equals(toolRouterEnabled)) {
            return mode == null ? ToolRouterMode.FORMAT : mode;
        }
        ToolCallGrade grade = getToolCallGrade();
        boolean weakGrade = grade == ToolCallGrade.TEXT || grade == ToolCallGrade.NONE;
        return weakGrade ? mode : null;
    }

    /**
     * Always one of {@link PlatformHostedLlmAuthType}'s constants - never null/blank, see the
     * field's javadoc.
     */
    public String getAuthType() {
        return authType;
    }

    /** Nullable - only meaningful when {@link #getAuthType()} is a Vertex type. */
    public String getVertexProjectId() {
        return vertexProjectId;
    }

    /** Nullable - only meaningful when {@link #getAuthType()} is a Vertex type. */
    public String getVertexLocation() {
        return vertexLocation;
    }
}
