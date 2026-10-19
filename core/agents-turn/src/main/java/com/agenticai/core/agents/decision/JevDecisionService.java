package com.agenticai.core.agents.decision;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.json.JsonNodes;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.providers.capability.ProviderCredentialService;
import com.agenticai.core.providers.capability.ProviderKeys;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TtlCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * TypeSafe AI's Jev - a "System One" decision model - wired in as the platform's
 * out-of-the-box decision layer for the places that today fall back to lexical
 * heuristics ({@code QueryComplexityClassifier}, {@code AgentRelevanceSelector}).
 *
 * <p><b>What Jev is.</b> Not a chat model: it never generates text. One
 * endpoint, {@code POST {baseUrl}/v1/systemone}, takes a {@code state} plus a
 * map of named typed questions - {@code noul} (calibrated yes/no probability
 * 0..1), {@code choice} (winner + per-option probabilities + confidence over a
 * criteria map of up to 255 options), {@code score} (probability-weighted score
 * over 2-10 ordered levels) - and answers all of them in a single round trip at
 * a fraction of a frontier model's cost and latency (~$0.042/1M input tokens,
 * no output charge, per TypeSafe's published pricing). That is exactly the
 * decision shape this codebase's lexical selectors approximate for free: "is
 * this turn simple?", "which agent fits this task?", "is this other agent also
 * relevant?" - each selector's own javadoc already names a learned decision
 * model as the natural follow-up. This service is that follow-up.
 *
 * <p><b>Configuration (out of the box).</b> No schema changes: the API key is a
 * {@link PlatformToolKey#TYPESAFE} row in {@code platform_tool_credential},
 * settable at runtime from web-admin's Voice &amp; Tool Credentials page; when no
 * row exists the {@code app.jev.api-key} property (env {@code APP_JEV_API_KEY}
 * or {@code TYPESAFE_API_KEY}) is used instead. With neither present - and with
 * {@code app.jev.enabled=false} - every method here returns empty and every
 * caller behaves exactly as it did before this class existed.
 *
 * <p><b>Fail-open, always.</b> A Jev call sits in front of real user traffic, so
 * it degrades to the pre-existing heuristic path on ANY failure - timeout, 4xx/
 * 5xx, malformed body, missing key - and never throws. Timeouts are tight by
 * default (2s connect / 3s read, {@code app.jev.*-timeout}): a decision model
 * that takes longer than that is worth less than the heuristic it replaces.
 *
 * <p><b>Registry position.</b> This is impl #1 of the {@link DecisionModel}
 * SPI behind {@link DecisionEngine} - the vendor-specific wire client, nothing
 * more. Tenant keys come from the unified {@code provider_credential} table
 * ({@code provider_key=TYPESAFE}); the tenant decision policy moved to {@code
 * capability_policy} and is served by the engine, not this class. A second
 * decision vendor implements {@code DecisionModel} + a {@code
 * ProviderCredentialValidator} and needs no changes here.
 */
@Service
public class JevDecisionService implements DecisionModel {

    private static final Logger log = LoggerFactory.getLogger(JevDecisionService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /** TypeSafe's public endpoint host. Override only for a compatible gateway/proxy (e.g. LiteLLM's /typesafe pass-through). */
    static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    static final String DEFAULT_MODEL = "jev-latest";
    /** TypeSafe caps choice criteria at 255 options. */
    static final int MAX_CRITERIA = 255;
    /** Hard cap on the state payload - keeps per-call token spend bounded on long inputs (Jev bills input tokens). */
    static final int MAX_STATE_CHARS = 12_000;

    private record ResolvedKey(String apiKey, KeySource source) {
    }

    private final RestClient restClient;
    // Nullable: slim test contexts that never declare the credential services
    // still get a fully functional (env-key-only) service, same posture as
    // AgentIntegrationToolResolver's optional collaborators.
    private final PlatformToolCredentialService platformToolCredentials;
    private final ProviderCredentialService providerCredentials;
    private final boolean enabled;
    private final String envApiKey;
    private final String baseUrl;
    private final String model;
    // Resolved keys cached per resolution scope (one entry per tenant that has
    // traffic, plus a platform entry) - 30s bounds how long a credential
    // rotate/deactivate takes to reach every running pod. Negative results
    // cached too, so a credential appearing mid-TTL takes at most that long
    // to notice.
    private final TtlCache<Optional<ResolvedKey>> keyCache;

    @Autowired
    public JevDecisionService(
        RestClient.Builder restClientBuilder,
        ObjectProvider<ClientHttpRequestFactorySettings> baseSettings,
        ObjectProvider<PlatformToolCredentialService> platformToolCredentials,
        ObjectProvider<ProviderCredentialService> providerCredentials,
        @Value("${app.jev.enabled:true}") boolean enabled,
        @Value("${app.jev.api-key:${TYPESAFE_API_KEY:}}") String envApiKey,
        @Value("${app.jev.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
        @Value("${app.jev.model:" + DEFAULT_MODEL + "}") String model,
        @Value("${app.jev.connect-timeout:2s}") Duration connectTimeout,
        @Value("${app.jev.read-timeout:3s}") Duration readTimeout,
        @Value("${app.jev.key-cache-ttl:30s}") Duration keyCacheTtl
    ) {
        // Same transport-selection posture as LlmHttpClientFactory: inherit the
        // app-wide settings (SSL bundles, proxy) and override only the timeouts,
        // on whichever HTTP stack Boot detected for this deployment.
        ClientHttpRequestFactorySettings settings = baseSettings
            .getIfAvailable(ClientHttpRequestFactorySettings::defaults)
            .withConnectTimeout(connectTimeout)
            .withReadTimeout(readTimeout);
        this.restClient = restClientBuilder
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .build();
        this.platformToolCredentials = platformToolCredentials.getIfAvailable();
        this.providerCredentials = providerCredentials.getIfAvailable();
        this.enabled = enabled;
        this.envApiKey = envApiKey;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model;
        this.keyCache = new TtlCache<>("jev-api-key", keyCacheTtl, 256);
    }

    /**
     * Test seam: callers hand a fully-built {@link RestClient} (e.g. one bound
     * to a {@code MockRestServiceServer}) and the credential/key values
     * directly - mirrors the package-private overload pattern {@code
     * ToolRelevanceSelector} uses so tests never touch real transport or config.
     */
    JevDecisionService(
        RestClient restClient, PlatformToolCredentialService platformToolCredentials,
        ProviderCredentialService providerCredentials,
        boolean enabled, String envApiKey, String baseUrl, String model, Duration keyCacheTtl
    ) {
        this.restClient = restClient;
        this.platformToolCredentials = platformToolCredentials;
        this.providerCredentials = providerCredentials;
        this.enabled = enabled;
        this.envApiKey = envApiKey;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model;
        this.keyCache = new TtlCache<>("jev-api-key", keyCacheTtl, 256);
    }

    @Override
    public String providerKey() {
        return ProviderKeys.TYPESAFE;
    }

    /**
     * True when Jev is enabled AND a usable key resolves for the CURRENT
     * request's tenant. Cheap to call per turn: the resolved key is cached for
     * {@code app.jev.key-cache-ttl} (negative results included), so this is not
     * a DB hit on every call.
     */
    @Override
    public boolean isAvailable() {
        return enabled && resolveKey().isPresent();
    }

    /** The platform kill-switch itself ({@code app.jev.enabled}) - status surfaces it separately from "is a key configured" so an admin can see the difference. */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Which key source the current request resolves to - lets the status
     * surfaces tell a tenant "Jev is running on YOUR key" vs "the platform's"
     * vs "not configured".
     */
    @Override
    public Optional<KeySource> keySource() {
        return enabled ? resolveKey().map(ResolvedKey::source) : Optional.empty();
    }

    /**
     * One {@code noul} question: "yes/no" answered as a probability in [0,1].
     * Empty when unavailable or on any failure.
     */
    @Override
    public Optional<Double> noul(String state, String instructions) {
        Map<String, Double> out = nouls(state, Map.of("q", instructions));
        return Optional.ofNullable(out.get("q"));
    }

    /**
     * Several {@code noul} questions in ONE call - Jev evaluates every question
     * in the request in parallel against the same state, so batching costs one
     * round trip instead of N. Keys are caller-chosen and come back untouched.
     * Missing/failed answers are simply absent from the returned map.
     */
    @Override
    public Map<String, Double> nouls(String state, Map<String, String> instructionsByKey) {
        if (instructionsByKey == null || instructionsByKey.isEmpty()) {
            return Map.of();
        }
        Map<String, ObjectNode> questions = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : instructionsByKey.entrySet()) {
            if (StringUtils.hasText(e.getValue())) {
                questions.put(e.getKey(), MAPPER.createObjectNode()
                    .put("type", "noul")
                    .put("instructions", e.getValue()));
            }
        }
        Optional<JsonNode> answers = evaluate(state, questions);
        if (answers.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (String key : questions.keySet()) {
            JsonNode answer = answers.get().path(key);
            if ("noul".equals(answer.path("type").asText(null)) && answer.path("noul").isNumber()) {
                out.put(key, answer.path("noul").asDouble());
            }
        }
        return out;
    }

    /**
     * One {@code choice} question over {@code criteria} (option key -&gt; plain-
     * language description). Empty when unavailable, on failure, or when the
     * response's winning key is not one of the offered options (never trust a
     * choice the caller can't map back).
     */
    @Override
    public Optional<Choice> choose(String state, String instructions, Map<String, String> criteria) {
        if (criteria == null || criteria.size() < 2) {
            return Optional.empty();
        }
        ObjectNode criteriaNode = MAPPER.createObjectNode();
        criteria.entrySet().stream().limit(MAX_CRITERIA).forEach(e ->
            criteriaNode.put(e.getKey(), e.getValue() == null ? "" : e.getValue()));
        ObjectNode question = MAPPER.createObjectNode()
            .put("type", "choice")
            .put("instructions", instructions)
            .set("criteria", criteriaNode);
        Optional<JsonNode> answers = evaluate(state, Map.of("q", question));
        if (answers.isEmpty()) {
            return Optional.empty();
        }
        JsonNode answer = answers.get().path("q");
        if (!"choice".equals(answer.path("type").asText(null))) {
            return Optional.empty();
        }
        String winning = answer.path("choice").asText(null);
        if (winning == null || !criteria.containsKey(winning)) {
            return Optional.empty();
        }
        return Optional.of(new Choice(
            winning,
            toDoubleMap(answer.path("probabilities")),
            answer.path("confidence").isNumber() ? answer.path("confidence").asDouble() : null
        ));
    }

    /**
     * One {@code score} question over 2-10 ordered level descriptions - returns
     * the probability-weighted score (may land between levels) plus the full
     * distribution. Empty when unavailable or on failure.
     */
    @Override
    public Optional<Score> score(String state, String instructions, List<String> levels) {
        if (levels == null || levels.size() < 2 || levels.size() > 10) {
            return Optional.empty();
        }
        ArrayNode criteriaNode = MAPPER.createArrayNode();
        levels.forEach(lvl -> criteriaNode.add(lvl == null ? "" : lvl));
        ObjectNode question = MAPPER.createObjectNode()
            .put("type", "score")
            .put("instructions", instructions)
            .set("criteria", criteriaNode);
        Optional<JsonNode> answers = evaluate(state, Map.of("q", question));
        if (answers.isEmpty()) {
            return Optional.empty();
        }
        JsonNode answer = answers.get().path("q");
        if (!"score".equals(answer.path("type").asText(null)) || !answer.path("score").isNumber()) {
            return Optional.empty();
        }
        return Optional.of(new Score(
            answer.path("score").asDouble(),
            toDoubleMap(answer.path("probabilities")),
            answer.path("confidence").isNumber() ? answer.path("confidence").asDouble() : null
        ));
    }

    /**
     * The single POST to {@code /v1/systemone}. Returns the raw {@code answers}
     * object on success; empty on ANY failure mode - see the class javadoc's
     * fail-open contract. Response shape (TypeSafe's own, verified against the
     * API reference):
     * {@code {"model": "jev-1.13.0", "answers": {"<key>": {...}}, "usage": {...}}}.
     */
    private Optional<JsonNode> evaluate(String state, Map<String, ObjectNode> questions) {
        if (!isAvailable() || questions.isEmpty()) {
            return Optional.empty();
        }
        JsonNode response = callUpstream(state, questions).response();
        JsonNode answers = response == null ? null : response.path("answers");
        if (answers == null || !answers.isObject()) {
            return Optional.empty();
        }
        return Optional.of(answers);
    }

    /**
     * The consumer playground's entry point ({@code POST /api/jev/playground}):
     * unlike the internal callers above this does NOT fail open - a tenant
     * experimenting with Jev needs to SEE their key work or fail, not silently
     * fall back to a heuristic. Validates the caller-supplied question shapes
     * (bounded so a playground call can't become a cost blowout), then returns
     * the upstream result verbatim - answers + usage + model on success, the
     * failure's status on error. Empty when no key resolves at all (the
     * controller turns that into the "not configured" 400).
     */
    @Override
    public Optional<PlaygroundOutcome> playground(String state, Map<String, JsonNode> questions) {
        Optional<ResolvedKey> key = resolveKey();
        if (!enabled || key.isEmpty()) {
            return Optional.empty();
        }
        Map<String, ObjectNode> validated = validatePlaygroundQuestions(questions);
        Resolved resolved = callUpstreamWithKey(state, validated, key.get().apiKey());
        if (resolved.error() != null) {
            return Optional.of(new PlaygroundOutcome(
                null, null, model, key.get().source(), resolved.errorStatus(), resolved.error()));
        }
        JsonNode response = resolved.response();
        JsonNode answers = response == null ? null : response.path("answers");
        if (answers == null || !answers.isObject()) {
            return Optional.of(new PlaygroundOutcome(
                null, null, model, key.get().source(), null, "Jev returned a response with no answers object."));
        }
        return Optional.of(new PlaygroundOutcome(
            answers, response.path("usage"), response.path("model").asText(model), key.get().source(), null, null));
    }

    /** Bounds on caller-supplied playground questions - a playground is for experimenting, not an open proxy into someone's key. */
    static final int MAX_PLAYGROUND_QUESTIONS = 10;
    static final int MAX_PLAYGROUND_KEY_CHARS = 40;
    static final int MAX_PLAYGROUND_INSTRUCTION_CHARS = 2000;
    static final int MAX_PLAYGROUND_CRITERION_CHARS = 500;

    private Map<String, ObjectNode> validatePlaygroundQuestions(Map<String, JsonNode> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("questions must contain at least one question");
        }
        if (questions.size() > MAX_PLAYGROUND_QUESTIONS) {
            throw new IllegalArgumentException("at most " + MAX_PLAYGROUND_QUESTIONS + " questions per playground call");
        }
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : questions.entrySet()) {
            String key = e.getKey();
            if (!StringUtils.hasText(key) || key.length() > MAX_PLAYGROUND_KEY_CHARS) {
                throw new IllegalArgumentException("question keys must be 1-" + MAX_PLAYGROUND_KEY_CHARS + " characters");
            }
            JsonNode q = e.getValue();
            if (q == null || !q.isObject()) {
                throw new IllegalArgumentException("question '" + key + "' must be an object");
            }
            String type = q.path("type").asText(null);
            String instructions = q.path("instructions").asText(null);
            if (!StringUtils.hasText(instructions) || instructions.length() > MAX_PLAYGROUND_INSTRUCTION_CHARS) {
                throw new IllegalArgumentException("question '" + key + "' needs instructions of 1-" + MAX_PLAYGROUND_INSTRUCTION_CHARS + " characters");
            }
            ObjectNode validated = MAPPER.createObjectNode()
                .put("type", type)
                .put("instructions", instructions);
            switch (type == null ? "" : type) {
                case "noul" -> { /* instructions only */ }
                case "choice" -> validated.set("criteria", validateChoiceCriteria(key, q.path("criteria")));
                case "score" -> validated.set("criteria", validateScoreCriteria(key, q.path("criteria")));
                default -> throw new IllegalArgumentException(
                    "question '" + key + "' has unsupported type '" + type + "' - expected noul, choice or score");
            }
            out.put(key, validated);
        }
        return out;
    }

    private ObjectNode validateChoiceCriteria(String key, JsonNode criteria) {
        if (!criteria.isObject() || criteria.size() < 2 || criteria.size() > MAX_CRITERIA) {
            throw new IllegalArgumentException(
                "choice question '" + key + "' needs 2-" + MAX_CRITERIA + " criteria (option key -> description)");
        }
        ObjectNode out = MAPPER.createObjectNode();
        JsonNodes.fields(criteria).forEachRemaining(f -> {
            String label = f.getValue().isTextual() ? f.getValue().asText() : f.getValue().toString();
            out.put(f.getKey(), label.length() <= MAX_PLAYGROUND_CRITERION_CHARS
                ? label : label.substring(0, MAX_PLAYGROUND_CRITERION_CHARS));
        });
        return out;
    }

    private ArrayNode validateScoreCriteria(String key, JsonNode criteria) {
        if (!criteria.isArray() || criteria.size() < 2 || criteria.size() > 10) {
            throw new IllegalArgumentException(
                "score question '" + key + "' needs 2-10 ordered level descriptions");
        }
        ArrayNode out = MAPPER.createArrayNode();
        criteria.forEach(level -> {
            String text = level.isTextual() ? level.asText() : level.toString();
            out.add(text.length() <= MAX_PLAYGROUND_CRITERION_CHARS
                ? text : text.substring(0, MAX_PLAYGROUND_CRITERION_CHARS));
        });
        return out;
    }

    /** Success carries the raw response body; failure carries the status + a safe message. */
    private record Resolved(JsonNode response, Integer errorStatus, String error) {
    }

    private Resolved callUpstream(String state, Map<String, ObjectNode> questions) {
        return resolveKey()
            .map(k -> callUpstreamWithKey(state, questions, k.apiKey()))
            .orElseGet(() -> new Resolved(null, null, "no Jev key resolved"));
    }

    private Resolved callUpstreamWithKey(String state, Map<String, ObjectNode> questions, String apiKey) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("state", truncate(state));
        body.set("questions", MAPPER.valueToTree(questions));
        try {
            JsonNode response = restClient.post()
                .uri(baseUrl + "/v1/systemone")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
            return new Resolved(response, null, null);
        } catch (RestClientResponseException e) {
            // Deliberately reports status only, not the upstream body: TypeSafe
            // error bodies can echo request details.
            log.warn("Jev evaluation failed (HTTP {}) - falling back to the local heuristic.", e.getStatusCode().value());
            return new Resolved(null, e.getStatusCode().value(), "Jev returned HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("Jev evaluation failed ({}) - falling back to the local heuristic.", e.getMessage());
            return new Resolved(null, null, "Jev call failed: " + e.getMessage());
        }
    }

    /**
     * Resolution order: the TENANT's own BYOK row first when the request has a
     * tenant context (their key, their bill, their experiment), then the
     * platform-wide TYPESAFE credential an admin manages at runtime, then the
     * env/property bootstrap key. Cached per scope - tenant-scoped keys cache
     * under the tenant id, platform/env under a single shared entry. Empty
     * result is cached too - a credential appearing mid-TTL takes at most
     * {@code key-cache-ttl} to notice, the documented staleness bound.
     */
    private Optional<ResolvedKey> resolveKey() {
        if (!enabled) {
            return Optional.empty();
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        String cacheKey = tenantId != null
            ? TtlCache.key("tenant", tenantId, "jev")
            : TtlCache.key("platform", "typesafe");
        return keyCache.get(cacheKey, () -> {
            if (tenantId != null && providerCredentials != null) {
                Optional<String> tenant = providerCredentials.resolveDecryptedSecret(ProviderKeys.TYPESAFE);
                if (tenant.isPresent()) {
                    return Optional.of(new ResolvedKey(tenant.get(), KeySource.TENANT));
                }
            }
            if (platformToolCredentials != null) {
                Optional<String> managed = platformToolCredentials.resolveActiveApiKey(PlatformToolKey.TYPESAFE);
                if (managed.isPresent()) {
                    return managed.map(k -> new ResolvedKey(k, KeySource.PLATFORM));
                }
            }
            return StringUtils.hasText(envApiKey)
                ? Optional.of(new ResolvedKey(envApiKey.trim(), KeySource.ENV))
                : Optional.empty();
        });
    }

    private static String truncate(String state) {
        if (state == null) {
            return "";
        }
        return state.length() <= MAX_STATE_CHARS ? state : state.substring(0, MAX_STATE_CHARS);
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static Map<String, Double> toDoubleMap(JsonNode node) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            JsonNodes.fields(node).forEachRemaining(e -> {
                if (e.getValue().isNumber()) {
                    out.put(e.getKey(), e.getValue().asDouble());
                }
            });
        }
        return out;
    }
}
