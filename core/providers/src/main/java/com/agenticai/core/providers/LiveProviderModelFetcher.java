package com.agenticai.core.providers;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Live "what models does this key actually see right now" fetch, per
 * provider - the tenant-key counterpart of {@link
 * PlatformHostedLlmProviderService#discoverModels} (which serves the
 * admin hosted-pool form with the PLATFORM's own key). {@link
 * ProviderModelCatalogService}'s javadoc explains why the platform-wide
 * background job must never borrow a tenant's key; this class is the
 * legitimate per-request path instead: it only ever runs with the requesting
 * tenant's OWN active BYOK key (or no key at all for OpenRouter's public
 * list), on that tenant's own dropdown request - see {@code
 * ProviderModelListingService} for the caching/merge layer on top.
 *
 * <p>Same {@code RestClient}-per-component style as every {@code
 * validation.*ProviderValidator}, with one addition: explicit connect/read
 * timeouts (5s/8s), because this sits on a user-facing request path - a
 * hung vendor endpoint must degrade to the cached catalog, not hang the
 * dropdown. Every fetch method returns an empty list on ANY failure, never
 * throws; the caller treats empty as "serve the catalog exactly as today."
 *
 * <p>Endpoint shapes per provider (auth matches that provider's validator):
 * <ul>
 *   <li>GROK - {@code GET api.x.ai/v1/language-models} (rich: modalities,
 *   context) with a {@code /v1/models} ids-only fallback.
 *   <li>OPENAI / DEEPSEEK - conventional {@code /v1/models}, ids only.
 *   <li>ANTHROPIC - {@code /v1/models} with the {@code anthropic-version}
 *   header; carries {@code display_name}.
 *   <li>GEMINI - {@code /v1beta/models}, filtered to entries whose {@code
 *   supportedGenerationMethods} include {@code generateContent} (the listing
 *   also contains embedding/TTS/video models); carries displayName,
 *   description and inputTokenLimit. NOTE: per the hard-won V165/V168
 *   lesson, Google's listing can still name retired chat models - the merge
 *   layer deliberately treats live data as "what the vendor CLAIMS exists",
 *   which is still strictly fresher than the hand-maintained seed list.
 *   <li>MISTRAL - {@code /v1/models} with description, max_context_length
 *   and capabilities.vision.
 *   <li>GROQ - OpenAI-path {@code /openai/v1/models} with context_window;
 *   rows with {@code active=false} are dropped.
 *   <li>OPENROUTER - the same public unauthenticated list {@link
 *   ProviderModelCatalogService} already consumes, plus description.
 * </ul>
 */
@Component
public class LiveProviderModelFetcher {

    private static final Logger log = LoggerFactory.getLogger(LiveProviderModelFetcher.class);

    static final String GROK_LANGUAGE_MODELS_URL = "https://api.x.ai/v1/language-models";
    static final String GROK_MODELS_URL = "https://api.x.ai/v1/models";
    static final String OPENAI_MODELS_URL = "https://api.openai.com/v1/models";
    static final String ANTHROPIC_MODELS_URL = "https://api.anthropic.com/v1/models";
    static final String ANTHROPIC_VERSION = "2023-06-01";
    static final String GEMINI_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models";
    static final String MISTRAL_MODELS_URL = "https://api.mistral.ai/v1/models";
    static final String GROQ_MODELS_URL = "https://api.groq.com/openai/v1/models";
    static final String DEEPSEEK_MODELS_URL = "https://api.deepseek.com/v1/models";
    static final String META_MODELS_URL = "https://api.meta.ai/v1/models";
    static final String OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models";

    /**
     * Conservative substring denylist for entries a provider's flat models
     * list includes but a chat dropdown must not offer (embedding, speech,
     * image/video generation endpoints). Substrings, not prefixes - vendors
     * put these markers anywhere in the id ("text-embedding-004",
     * "gpt-4o-mini-tts", "whisper-large-v3"). Deliberately short: a false
     * negative just shows one useless row, a false positive hides a real
     * chat model.
     */
    private static final List<String> NON_CHAT_MODEL_MARKERS = List.of(
        "embed", "whisper", "tts", "dall-e", "imagen", "image", "video", "sora", "veo",
        // Meta's STT endpoint id is "muse-voice-transcribe-1.0" - none of the
        // markers above match it.
        "transcribe"
    );

    /**
     * One live model row, normalized across providers. Nullable fields mean
     * "this provider's endpoint doesn't say" - the merge layer falls back to
     * the cached catalog (or a safe default) for those.
     */
    public record LiveModel(
        String modelId,
        String displayName,
        String description,
        Integer contextWindow,
        Boolean supportsImageInput,
        Boolean deprecated
    ) {}

    private final RestClient restClient;

    // Two constructors exist (this one + the package-private test seam
    // below) - Spring only auto-detects a single unannotated constructor,
    // so without @Autowired it falls back to a no-arg constructor that
    // doesn't exist and fails bean creation with "No default constructor
    // found" (caught during 2026-08-05 staging deploy verification).
    @Autowired
    public LiveProviderModelFetcher(RestClient.Builder restClientBuilder) {
        // User-facing request path: bounded connect/read timeouts so a hung
        // vendor endpoint degrades to the cached catalog instead of hanging
        // the dropdown (the platform-wide default builder carries none) -
        // same SimpleClientHttpRequestFactory approach as SidecarClient.
        this(restClientBuilder.clone().requestFactory(timeoutFactory()).build());
    }

    /**
     * Test seam: the public constructor's timeout {@code requestFactory}
     * would clobber the mock factory {@code MockRestServiceServer.bindTo}
     * installs into a builder, so tests build the client themselves.
     */
    LiveProviderModelFetcher(RestClient restClient) {
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(8_000);
        return requestFactory;
    }

    /**
     * Live model list for {@code provider} using {@code apiKey} (ignored for
     * OPENROUTER, whose list is public). Empty - never an exception - for
     * providers with no implemented live endpoint (SARVAM, OLLAMA,
     * AZURE_OPENAI, AWS_BEDROCK, GOOGLE_VERTEX, PLATFORM_HOSTED: each needs
     * per-tenant URLs or non-key auth this key-shaped path can't express)
     * and on any fetch/parse failure.
     */
    public List<LiveModel> fetch(ProviderName provider, String apiKey) {
        try {
            List<LiveModel> models = switch (provider) {
                case GROK -> fetchGrok(apiKey);
                case OPENAI -> fetchOpenAiCompatibleIds(OPENAI_MODELS_URL, apiKey);
                case ANTHROPIC -> fetchAnthropic(apiKey);
                case GEMINI -> fetchGemini(apiKey);
                case MISTRAL -> fetchMistral(apiKey);
                case GROQ -> fetchGroq(apiKey);
                case DEEPSEEK -> fetchOpenAiCompatibleIds(DEEPSEEK_MODELS_URL, apiKey);
                // Meta Model API is OpenAI-compatible; its /v1/models also
                // lists muse-image-1.0 and muse-voice-transcribe-1.0, which
                // the NON_CHAT_MODEL_MARKERS filter below drops.
                case META -> fetchOpenAiCompatibleIds(META_MODELS_URL, apiKey);
                case OPENROUTER -> fetchOpenRouter();
                default -> List.<LiveModel>of();
            };
            return models.stream().filter(m -> isLikelyChatModel(m.modelId())).toList();
        } catch (Exception e) {
            // Belt and braces - each fetch method already catches, but this
            // method's contract ("never throws") must hold even for a parse
            // surprise, since a dropdown request rides on it.
            log.warn("Live model fetch for {} failed - falling back to the cached catalog", provider, e);
            return List.of();
        }
    }

    static boolean isLikelyChatModel(String modelId) {
        String lower = modelId.toLowerCase(Locale.ROOT);
        return NON_CHAT_MODEL_MARKERS.stream().noneMatch(lower::contains);
    }

    // --- per-provider fetchers ----------------------------------------------

    private List<LiveModel> fetchGrok(String apiKey) {
        // Prefer /v1/language-models: unlike the bare-ids /v1/models it
        // carries input_modalities and pricing/context metadata per model.
        JsonNode response = getJson(GROK_LANGUAGE_MODELS_URL, spec -> spec
            .header("Authorization", "Bearer " + apiKey));
        if (response != null && response.has("models")) {
            List<LiveModel> models = new ArrayList<>();
            for (JsonNode node : response.get("models")) {
                String id = node.path("id").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                boolean image = false;
                for (JsonNode modality : node.path("input_modalities")) {
                    image |= "image".equalsIgnoreCase(modality.asText(""));
                }
                // xAI has shifted this field's name across API revisions -
                // read both known spellings, null (fall back to catalog) if
                // neither is present.
                Integer contextWindow = intOrNull(node, "context_window");
                if (contextWindow == null) {
                    contextWindow = intOrNull(node, "max_prompt_length");
                }
                models.add(new LiveModel(id, id, null, contextWindow, image, null));
            }
            if (!models.isEmpty()) {
                return models;
            }
        }
        // Fall back to the conventional OpenAI-shape ids-only endpoint.
        return fetchOpenAiCompatibleIds(GROK_MODELS_URL, apiKey);
    }

    /** Conventional OpenAI-compatible {@code /models}: {@code data[].id} only. */
    private List<LiveModel> fetchOpenAiCompatibleIds(String url, String apiKey) {
        JsonNode response = getJson(url, spec -> spec.header("Authorization", "Bearer " + apiKey));
        List<LiveModel> models = new ArrayList<>();
        if (response != null && response.has("data")) {
            for (JsonNode node : response.get("data")) {
                String id = node.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    models.add(new LiveModel(id, id, null, null, null, null));
                }
            }
        }
        return models;
    }

    private List<LiveModel> fetchAnthropic(String apiKey) {
        // Same auth-header shape as AnthropicProviderValidator.
        JsonNode response = getJson(ANTHROPIC_MODELS_URL, spec -> spec
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION));
        List<LiveModel> models = new ArrayList<>();
        if (response != null && response.has("data")) {
            for (JsonNode node : response.get("data")) {
                String id = node.path("id").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                String displayName = node.path("display_name").asText(id);
                models.add(new LiveModel(id, displayName, null, intOrNull(node, "max_input_tokens"), null, null));
            }
        }
        return models;
    }

    private List<LiveModel> fetchGemini(String apiKey) {
        // Same x-goog-api-key header as GeminiProviderValidator (never the
        // ?key= query form - keys don't belong in URLs/access logs).
        JsonNode response = getJson(GEMINI_MODELS_URL, spec -> spec.header("x-goog-api-key", apiKey));
        List<LiveModel> models = new ArrayList<>();
        if (response != null && response.has("models")) {
            for (JsonNode node : response.get("models")) {
                String name = node.path("name").asText(null);
                if (name == null || name.isBlank() || !supportsGenerateContent(node)) {
                    continue;
                }
                String id = name.startsWith("models/") ? name.substring("models/".length()) : name;
                models.add(new LiveModel(
                    id,
                    node.path("displayName").asText(id),
                    node.path("description").asText(null),
                    intOrNull(node, "inputTokenLimit"),
                    null,
                    null
                ));
            }
        }
        return models;
    }

    private static boolean supportsGenerateContent(JsonNode geminiModel) {
        for (JsonNode method : geminiModel.path("supportedGenerationMethods")) {
            if ("generateContent".equals(method.asText(""))) {
                return true;
            }
        }
        return false;
    }

    private List<LiveModel> fetchMistral(String apiKey) {
        JsonNode response = getJson(MISTRAL_MODELS_URL, spec -> spec.header("Authorization", "Bearer " + apiKey));
        List<LiveModel> models = new ArrayList<>();
        if (response != null && response.has("data")) {
            for (JsonNode node : response.get("data")) {
                String id = node.path("id").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                Boolean vision = node.path("capabilities").path("vision").isMissingNode()
                    ? null
                    : node.path("capabilities").path("vision").asBoolean(false);
                models.add(new LiveModel(
                    id,
                    node.path("name").asText(id),
                    node.path("description").asText(null),
                    intOrNull(node, "max_context_length"),
                    vision,
                    node.has("deprecation") && !node.path("deprecation").isNull() ? Boolean.TRUE : null
                ));
            }
        }
        return models;
    }

    private List<LiveModel> fetchGroq(String apiKey) {
        JsonNode response = getJson(GROQ_MODELS_URL, spec -> spec.header("Authorization", "Bearer " + apiKey));
        List<LiveModel> models = new ArrayList<>();
        if (response != null && response.has("data")) {
            for (JsonNode node : response.get("data")) {
                String id = node.path("id").asText(null);
                // Groq's list includes decommissioned rows flagged
                // active=false - drop them (a missing flag counts as active).
                if (id == null || id.isBlank() || !node.path("active").asBoolean(true)) {
                    continue;
                }
                models.add(new LiveModel(id, id, null, intOrNull(node, "context_window"), null, null));
            }
        }
        return models;
    }

    private List<LiveModel> fetchOpenRouter() {
        // Public, unauthenticated - the one provider needing no key at all
        // (same endpoint ProviderModelCatalogService's daily job consumes).
        JsonNode response = getJson(OPENROUTER_MODELS_URL, spec -> spec);
        List<LiveModel> models = new ArrayList<>();
        if (response != null && response.has("data")) {
            for (JsonNode node : response.get("data")) {
                String id = node.path("id").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                boolean image = false;
                for (JsonNode modality : node.path("architecture").path("input_modalities")) {
                    image |= "image".equalsIgnoreCase(modality.asText(""));
                }
                models.add(new LiveModel(
                    id,
                    node.path("name").asText(id),
                    node.path("description").asText(null),
                    intOrNull(node, "context_length"),
                    image,
                    null
                ));
            }
        }
        return models;
    }

    // --- plumbing ------------------------------------------------------------

    private interface HeaderCustomizer {
        RestClient.RequestHeadersSpec<?> apply(RestClient.RequestHeadersSpec<?> spec);
    }

    /** GET {@code url} as JSON; null (never an exception) on any failure. */
    private JsonNode getJson(String url, HeaderCustomizer headers) {
        try {
            return headers.apply(restClient.get().uri(url))
                .retrieve()
                .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Live model list call to {} failed ({}) - treating as empty", url, e.getMessage());
            return null;
        }
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }
}
