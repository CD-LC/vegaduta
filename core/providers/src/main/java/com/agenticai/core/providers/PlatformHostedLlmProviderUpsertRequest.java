package com.agenticai.core.providers;

import jakarta.validation.constraints.Size;
/**
 * Request body for {@code POST/PUT /api/platform/hosted-llm-providers[/{id}]}.
 * {@code apiKey} is optional/nullable on an update - see {@link
 * PlatformHostedLlmProvider#apply} for why a blank/absent value leaves any
 * already-stored key untouched. Required (validated in {@link
 * PlatformHostedLlmProviderService#create}) on a new row - there is no such
 * thing as a hosted provider with no key.
 *
 * <p>{@code providerName} + blank {@code baseUrl}/{@code model} is the
 * "generic, like tenant BYOK" flow (2026-07-26 feedback): pick any {@link
 * ProviderName} the pool supports (currently {@code OPENAI}/{@code GROK}/
 * {@code OPENROUTER}/{@code GEMINI}/{@code MISTRAL}/{@code DEEPSEEK}/{@code
 * GROQ}/{@code META} - see {@code
 * PlatformHostedLlmProviderService#HOSTED_POOL_COMPATIBLE_PROVIDERS}), paste a
 * key, and leave {@code baseUrl}/{@code model} blank to mean "auto-derive the
 * endpoint and auto-pick the best currently-available model for this
 * provider." An explicit {@code baseUrl}/{@code model} always wins over the
 * provider-derived default - {@code providerName} never overrides a value the
 * admin actually typed. See {@code PlatformHostedLlmProviderService#resolveRequest}.
 */
public record PlatformHostedLlmProviderUpsertRequest(
    String label,
    String baseUrl,
    String model,
    @Size(max = 4096) String apiKey,
    boolean enabled,
    int priority,
    // #971 - optional admin-facing region label (e.g. "us-east", "apac") -
    // see PlatformHostedLlmProvider's javadoc for why this is free-text
    // metadata, not a routing dimension.
    String region,
    // Optional. Set ONLY for providers whose OpenAI-compat chat-completions
    // endpoint is not "{baseUrl}/v1/chat/completions" - e.g. Google Gemini,
    // where an admin enters baseUrl "https://generativelanguage.googleapis.com"
    // + completionsPath "/v1beta/openai/chat/completions". Leave null/blank for
    // the conventional providers (Groq, Together AI, ...) - see
    // PlatformHostedLlmProvider#completionsPath and HostedLlmChatClientFactory.
    String completionsPath,
    // Optional - see this record's javadoc. Null means "conventional raw
    // base_url row" (Groq, Together AI, ...), preserving every pre-existing
    // row/flow exactly as before.
    ProviderName providerName,
    // V169 - one of PlatformHostedLlmAuthType's constants. Null/blank means
    // API_KEY (see PlatformHostedLlmAuthType#normalize), so every pre-V169
    // client (web-admin build not yet updated, an existing saved script, ...)
    // keeps behaving exactly as before. VERTEX_ADC/VERTEX_SERVICE_ACCOUNT
    // route apiKey/vertexProjectId/vertexLocation through
    // PlatformHostedLlmProviderService#create/update's Vertex branch instead
    // of the plain-API-key path - see that class for the full validation
    // rules per type.
    String authType,
    // V169 - required (non-blank) when authType is a Vertex type; ignored for API_KEY.
    String vertexProjectId,
    // V169 - optional even for a Vertex type; defaults to
    // PlatformHostedLlmAuthType.DEFAULT_VERTEX_LOCATION ("us-central1") when
    // blank - see PlatformHostedLlmProviderService.
    String vertexLocation,
    // V484 - null means "derive": true for the OLLAMA provider, false otherwise.
    // See PlatformHostedLlmProvider#isZeroCost and effectiveZeroCost().
    Boolean zeroCost,
    // V500 - constrained tool router override: TRUE / FALSE, null = derive from the measured
    // tool-call grade (see PlatformHostedLlmProvider#effectiveToolRouterMode).
    Boolean toolRouterEnabled,
    // V501 - small-model mode override: TRUE / FALSE, null = derive from the model's size
    // (see PlatformHostedLlmProvider#effectiveSmallModel).
    Boolean smallModel
) {

    /** Pre-V501 shape (no smallModel), kept so every existing call site still compiles unchanged. */
    public PlatformHostedLlmProviderUpsertRequest(
        String label, String baseUrl, String model, String apiKey, boolean enabled, int priority, String region,
        String completionsPath, ProviderName providerName, String authType, String vertexProjectId, String vertexLocation,
        Boolean zeroCost, Boolean toolRouterEnabled
    ) {
        this(label, baseUrl, model, apiKey, enabled, priority, region, completionsPath, providerName, authType,
            vertexProjectId, vertexLocation, zeroCost, toolRouterEnabled, null);
    }

    /** Writes the per-row overrides (V500 router, V501 small model) onto {@code entity}. */
    public void applyOverridesTo(PlatformHostedLlmProvider entity) {
        entity.setToolRouterEnabled(toolRouterEnabled);
        entity.setSmallModel(smallModel);
    }

    /** Pre-V500 shape (no toolRouterEnabled), kept so every existing call site still compiles unchanged. */
    public PlatformHostedLlmProviderUpsertRequest(
        String label, String baseUrl, String model, String apiKey, boolean enabled, int priority, String region,
        String completionsPath, ProviderName providerName, String authType, String vertexProjectId, String vertexLocation,
        Boolean zeroCost
    ) {
        this(label, baseUrl, model, apiKey, enabled, priority, region, completionsPath, providerName, authType,
            vertexProjectId, vertexLocation, zeroCost, null);
    }
    /**
     * Trims every free-text field, collapsing blank to {@code null}, before the
     * value can be validated, persisted, or used to build an upstream request -
     * same compact-constructor posture (and the same underlying cause) as {@code
     * ProviderActivationRequest}'s own.
     *
     * <p>Confirmed in production: a stored {@code model} of {@code
     * " gemini-2.0-flash-lite"} - one leading space, invisible in the admin form -
     * made EVERY platform-hosted turn fail with Google's {@code 404 NOT_FOUND},
     * because the model id is sent verbatim in the request body. Nothing caught
     * it earlier: {@code PlatformHostedLlmProviderService#validateKey} probes
     * {@code {baseUrl}/v1/models} and never looks at the model at all, so the row
     * saved clean and only chat broke. The same hazard applies to {@code baseUrl}
     * and {@code completionsPath} (a leading space makes the URI unparseable) and
     * to {@code apiKey} - note {@code validateKey} already called {@code
     * apiKey.trim()} for its probe while {@code create}/{@code update} encrypted
     * the UNtrimmed value, so a pasted key with a trailing newline validated
     * successfully and then 401'd on every real call. Trimming here fixes all of
     * them at the single point every write path shares.
     *
     * <p>Blank collapses to {@code null} rather than {@code ""} so the "unset"
     * value is the one the entity/service already document ({@code region} and
     * {@code completionsPath} are both nullable-means-absent), and so a blank
     * required field fails the explicit check in {@code
     * PlatformHostedLlmProviderService} with a 400 instead of reaching a NOT NULL
     * constraint as a 500.
     */
    public PlatformHostedLlmProviderUpsertRequest {
        label = trimToNull(label);
        baseUrl = trimToNull(baseUrl);
        model = trimToNull(model);
        apiKey = trimToNull(apiKey);
        region = trimToNull(region);
        completionsPath = trimToNull(completionsPath);
        authType = trimToNull(authType);
        vertexProjectId = trimToNull(vertexProjectId);
        vertexLocation = trimToNull(vertexLocation);
    }

    /**
     * Pre-V169 (pre-Vertex-auth) shape, kept so every existing call site
     * (tests, mainly - the web-admin form always builds the full canonical
     * constructor's JSON) still compiles unchanged. Defaults to {@code
     * authType} null (normalized to {@code API_KEY} by the compact
     * constructor's callers - see {@link PlatformHostedLlmAuthType#normalize})
     * and no Vertex fields.
     */
    public PlatformHostedLlmProviderUpsertRequest(
        String label, String baseUrl, String model, String apiKey, boolean enabled, int priority, String region,
        String completionsPath, ProviderName providerName
    ) {
        this(label, baseUrl, model, apiKey, enabled, priority, region, completionsPath, providerName, null, null, null, null);
    }

    /** Pre-V484 shape (no zeroCost), kept so every existing call site still compiles unchanged. */
    public PlatformHostedLlmProviderUpsertRequest(
        String label, String baseUrl, String model, String apiKey, boolean enabled, int priority, String region,
        String completionsPath, ProviderName providerName, String authType, String vertexProjectId, String vertexLocation
    ) {
        this(label, baseUrl, model, apiKey, enabled, priority, region, completionsPath, providerName, authType,
            vertexProjectId, vertexLocation, null);
    }

    /**
     * V484 - the stored {@code zero_cost} value: an explicit choice wins; otherwise a row configured
     * from the OLLAMA provider is local by construction and therefore free, anything else is not.
     */
    public boolean effectiveZeroCost() {
        if (zeroCost != null) {
            return zeroCost;
        }
        return providerName == ProviderName.OLLAMA;
    }

    /**
     * {@code apiKey} is strictly required for {@code API_KEY} (the default)
     * and {@code VERTEX_SERVICE_ACCOUNT} (it carries the pasted service-account
     * JSON there, not a bearer token - see {@code
     * PlatformHostedLlmProviderService#create}), but NOT for {@code
     * VERTEX_ADC} - there is no secret to paste when authenticating via
     * Application Default Credentials.
     */
    public boolean requiresApiKey() {
        return !PlatformHostedLlmAuthType.VERTEX_ADC.equals(PlatformHostedLlmAuthType.normalize(authType));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
