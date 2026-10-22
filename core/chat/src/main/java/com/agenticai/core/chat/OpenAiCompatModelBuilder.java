package com.agenticai.core.chat;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.providers.ProviderName;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;

import okio.Buffer;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The single place every OpenAI-compatible chat factory in this package (Azure OpenAI, DeepSeek,
 * Grok, Groq, Local OpenAI-compatible, Meta, Mistral, Ollama, OpenAI, OpenRouter, Sarvam and {@link
 * HostedLlmChatClientFactory}) builds its Spring AI transport and model (ADR-0012 P1b, re-based on
 * the official OpenAI Java SDK in P3). It owns the parts that must be identical for all of them:
 *
 * <ul>
 *   <li>the HTTP transport: every SDK client runs its requests on the pooled, timeout-bounded
 *       shared client of {@link LlmHttpClientFactory} (or its SSRF-guarded variant for
 *       tenant-supplied endpoints), behind the platform retry policy ({@link LlmRetryInterceptor});
 *   <li>the request URL: Spring AI 1.x sent {@code baseUrl + completionsPath} with the path
 *       defaulting to {@code /v1/chat/completions}. The SDK has no {@code completionsPath}; it
 *       appends {@code chat/completions} to the base URL. {@link Api#build} therefore hands the SDK
 *       {@code base + "/v1"} when no path is set (identical URL) and, when a provider needs a
 *       different path (Azure deployments, hosted-pool rows such as Gemini's {@code
 *       /v1beta/openai/chat/completions}), rewrites the URL in an interceptor to exactly {@code
 *       base + completionsPath};
 *   <li>wire parity with Spring AI 1.x for the fields the SDK now adds on its own (see {@link
 *       #legacyWire}): no {@code stream_options.include_obfuscation}, no {@code stream_options} at
 *       all for providers whose stream usage is disabled, no explicit {@code "strict": false} on
 *       tool functions;
 *   <li>the default options every model starts from: model id and {@code streamUsage} per {@link
 *       LlmModelSupport#streamUsage};
 *   <li>the model wiring: shared tool-calling manager and the app's observation registry. Retries
 *       are NOT the SDK's ({@code maxRetries(0)}) - see {@link LlmRetryInterceptor}.
 * </ul>
 *
 * <p>Factories keep only what is provider-specific (URL, sampling rules, caching keys, metrics
 * advisor). Not a Spring bean: each factory creates one from the collaborators it already takes, so
 * constructor signatures and bean wiring are unchanged.
 */
final class OpenAiCompatModelBuilder {

    private static final String CHAT_COMPLETIONS_SUFFIX = "/chat/completions";
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMappers.plain();

    private final LlmHttpClientFactory httpClients;
    private final ResilientToolCallingManagerFactory toolCallingManagers;
    private final LlmModelSupport modelSupport;

    OpenAiCompatModelBuilder(
            LlmHttpClientFactory httpClients,
            ResilientToolCallingManagerFactory toolCallingManagers,
            LlmModelSupport modelSupport) {
        this.httpClients = httpClients;
        this.toolCallingManagers = toolCallingManagers;
        this.modelSupport = modelSupport;
    }

    /** Starts describing the API endpoint; pass the {@link Api} to {@link #chatModel}. */
    Api api(String baseUrl) {
        return new Api(baseUrl);
    }

    /**
     * {@code OpenAiChatOptions.builder().model(model).streamUsage(...)} - the base every factory
     * extends.
     */
    OpenAiChatOptions.Builder options(ProviderName provider, String model) {
        return baseOptions(model, modelSupport.streamUsage(provider));
    }

    /**
     * The same base for callers that already resolved the stream-usage decision (static,
     * unit-testable helpers).
     */
    static OpenAiChatOptions.Builder baseOptions(String model, boolean streamUsage) {
        return OpenAiChatOptions.builder().model(model).streamUsage(streamUsage);
    }

    /**
     * The chat model: the endpoint/key/headers of {@code api} are folded into the options (the SDK
     * reads them from there), the transport is the shared pooled client, and the tool-calling
     * manager and observation registry are the app's. The caller's {@code options} carry the model,
     * sampling and caching keys.
     */
    OpenAiChatModel chatModel(Api api, OpenAiChatOptions options) {
        OpenAiChatOptions.Builder configured =
                options.mutate()
                        .baseUrl(api.sdkBaseUrl())
                        .apiKey(api.sdkApiKey())
                        // The real clocks live on the shared transport; this SDK-side shell client
                        // must not add a
                        // second, shorter call deadline of its own (0 = none in OkHttp).
                        .timeout(Duration.ZERO)
                        // Retries are the platform policy (LlmRetryInterceptor), not the SDK's.
                        .maxRetries(0);
        if (api.headers != null && !api.headers.isEmpty()) {
            configured.customHeaders(api.headers);
        }
        return OpenAiChatModel.builder()
                .options(configured.build())
                .toolCallingManager(toolCallingManagers.create())
                .observationRegistry(modelSupport.observationRegistry())
                .httpClientBuilderCustomizer(
                        api.customizer(httpClients, modelSupport.retry(), !streamUsageOn(options)))
                .build();
    }

    private static boolean streamUsageOn(OpenAiChatOptions options) {
        OpenAiChatOptions.StreamOptions so = options.getStreamOptions();
        return so != null && Boolean.TRUE.equals(so.includeUsage());
    }

    /**
     * Rewrites a chat-completions request body to the wire shape Spring AI 1.x produced, so strict
     * OpenAI-compatible servers see no field they did not see before the SDK move: drops {@code
     * stream_options.include_obfuscation} (always sent by the SDK model), drops {@code
     * stream_options} entirely when {@code dropStreamOptions}, and drops {@code "strict": false}
     * from tool functions. Also restores two request-shape details the SDK model changed without
     * meaning: it omits {@code "stream": false} on a blocking call (Spring AI 1.x always sent it)
     * and sends a single stop sequence as a bare string instead of a one-element array. Both
     * spellings are valid OpenAI API, but strict OpenAI-compatible servers were only ever exercised
     * with the old shape, so it is kept byte-for-byte. A body that is not a JSON object is returned
     * untouched.
     */
    static byte[] legacyWire(byte[] body, boolean dropStreamOptions) {
        try {
            JsonNode root = JSON.readTree(body);
            if (!(root instanceof ObjectNode request)) {
                return body;
            }
            boolean changed = false;
            if (!request.has("stream")) {
                request.put("stream", false);
                changed = true;
            }
            if (request.get("stop") instanceof tools.jackson.databind.node.StringNode stop) {
                request.set("stop", JSON.createArrayNode().add(stop.stringValue()));
                changed = true;
            }
            JsonNode streamOptions = request.get("stream_options");
            if (streamOptions instanceof ObjectNode so) {
                if (dropStreamOptions) {
                    request.remove("stream_options");
                    changed = true;
                } else if (so.remove("include_obfuscation") != null) {
                    changed = true;
                }
            }
            if (request.get("tools") instanceof ArrayNode tools) {
                for (JsonNode tool : tools) {
                    JsonNode function = tool.get("function");
                    if (function instanceof ObjectNode fn
                            && fn.has("strict")
                            && fn.get("strict").isBoolean()
                            && !fn.get("strict").booleanValue()) {
                        fn.remove("strict");
                        changed = true;
                    }
                }
            }
            return changed ? JSON.writeValueAsBytes(request) : body;
        } catch (RuntimeException e) {
            return body; // this hook must never be the reason a chat request fails
        }
    }

    /**
     * Fluent description of one OpenAI-compatible endpoint; unset parts keep the SDK's own
     * defaults.
     */
    static final class Api {

        private final String baseUrl;
        private String apiKey;
        private boolean noApiKey;
        private Map<String, String> headers;
        private String completionsPath;
        private boolean guarded;
        private UnaryOperator<byte[]> bodyTransform;

        private Api(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /** Bearer key sent as {@code Authorization}. */
        Api apiKey(String key) {
            this.apiKey = key;
            this.noApiKey = false;
            return this;
        }

        /** No {@code Authorization} header: auth travels in {@link #headers} or is not required. */
        Api noApiKey() {
            this.noApiKey = true;
            return this;
        }

        Api headers(Map<String, String> headers) {
            this.headers = headers == null ? null : new LinkedHashMap<>(headers);
            return this;
        }

        /**
         * Overrides the default {@code /v1/chat/completions}; the request goes to exactly {@code
         * baseUrl + completionsPath} (query string allowed, as Azure needs). A null path keeps the
         * default.
         */
        Api completionsPath(String completionsPath) {
            this.completionsPath = completionsPath;
            return this;
        }

        /**
         * Use the SSRF-guarded transport (address-pinned, no redirects) for tenant-supplied
         * endpoints.
         */
        Api guarded() {
            this.guarded = true;
            return this;
        }

        /**
         * Extra request-body rewrite applied after the legacy-wire normalisation (xAI server tools,
         * OpenAI strict tool schemas); several compose in registration order.
         */
        Api bodyTransform(UnaryOperator<byte[]> transform) {
            UnaryOperator<byte[]> before = this.bodyTransform;
            this.bodyTransform =
                    before == null || transform == null
                            ? (transform == null ? before : transform)
                            : bytes -> transform.apply(before.apply(bytes));
            return this;
        }

        private static String trimSlash(String url) {
            String u = url == null ? "" : url.trim();
            while (u.endsWith("/")) {
                u = u.substring(0, u.length() - 1);
            }
            return u;
        }

        /**
         * The base URL the SDK sees; its default path {@code chat/completions} completes the legacy
         * URL.
         */
        String sdkBaseUrl() {
            return trimSlash(baseUrl) + "/v1";
        }

        /** The exact URL Spring AI 1.x would have called. */
        String requestUrl() {
            return completionsPath == null
                    ? trimSlash(baseUrl) + "/v1" + CHAT_COMPLETIONS_SUFFIX
                    : trimSlash(baseUrl) + completionsPath;
        }

        String sdkApiKey() {
            if (noApiKey) {
                return ""; // the SDK's documented no-auth mode: no Authorization header is sent
            }
            if (apiKey == null) {
                throw new IllegalArgumentException("An API key is required for " + baseUrl);
            }
            return apiKey;
        }

        OpenAiHttpClientBuilderCustomizer customizer(
                LlmHttpClientFactory http, LlmRetry retry, boolean dropStreamOptions) {
            HttpUrl target = completionsPath == null ? null : HttpUrl.parse(requestUrl());
            if (completionsPath != null && target == null) {
                throw new IllegalArgumentException("Not a valid endpoint URL: " + requestUrl());
            }
            UnaryOperator<byte[]> body =
                    bytes -> {
                        byte[] normalised = legacyWire(bytes, dropStreamOptions);
                        return bodyTransform == null ? normalised : bodyTransform.apply(normalised);
                    };
            Interceptor terminal = new LlmRetryInterceptor(http.transport(guarded), retry);
            return builder -> {
                if (target != null) {
                    builder.interceptor(rewriteUrl(target));
                }
                builder.interceptor(rewriteBody(body));
                // The shell client's own clocks are irrelevant (no socket is opened); the shared
                // one owns them.
                builder.timeout(Duration.ZERO);
                // Must be last: the terminal interceptor never proceeds down the shell client's
                // chain.
                builder.interceptor(terminal);
            };
        }
    }

    /** Sends chat-completions requests to {@code target} instead of the SDK-computed URL. */
    static Interceptor rewriteUrl(HttpUrl target) {
        return chain -> {
            Request request = chain.request();
            if (request.url().encodedPath().endsWith(CHAT_COMPLETIONS_SUFFIX)) {
                request = request.newBuilder().url(target).build();
            }
            return chain.proceed(request);
        };
    }

    /**
     * Applies {@code transform} to every request body (JSON POSTs). Package-private: shared with
     * {@link AnthropicModelBuilder}.
     */
    static Interceptor rewriteBody(UnaryOperator<byte[]> transform) {
        return chain -> {
            Request request = chain.request();
            RequestBody body = request.body();
            if (body == null || body.contentLength() == 0) {
                return chain.proceed(request);
            }
            byte[] original = read(body);
            byte[] rewritten = transform.apply(original);
            return chain.proceed(
                    request.newBuilder()
                            .method(
                                    request.method(),
                                    RequestBody.create(rewritten, body.contentType()))
                            .build());
        };
    }

    private static byte[] read(RequestBody body) throws IOException {
        Buffer buffer = new Buffer();
        body.writeTo(buffer);
        return buffer.readByteArray();
    }
}
