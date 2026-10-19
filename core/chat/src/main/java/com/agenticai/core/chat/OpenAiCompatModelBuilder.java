package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The single place every OpenAI-compatible chat factory in this package (Azure OpenAI, DeepSeek,
 * Grok, Groq, Local OpenAI-compatible, Meta, Mistral, Ollama, OpenAI, OpenRouter, Sarvam and {@link
 * HostedLlmChatClientFactory}) builds its Spring AI transport and model (ADR-0012 P1b). It owns the
 * parts that must be identical for all of them:
 *
 * <ul>
 *   <li>the HTTP transport: bounded connect/read timeouts and pool from {@link LlmHttpClientFactory},
 *       or its SSRF-guarded variants for tenant-supplied endpoints ({@link Api#guarded()});</li>
 *   <li>base URL, optional {@code completionsPath}, API key (or the no-key marker) and extra headers;</li>
 *   <li>the default options every model starts from: model id and {@code streamUsage} per {@link
 *       LlmModelSupport#streamUsage};</li>
 *   <li>the model wiring: shared tool-calling manager, bounded retry template, observation registry.</li>
 * </ul>
 *
 * <p>Factories keep only what is provider-specific (URL, sampling rules, caching keys, metrics
 * advisor). Phase P3 replaces {@code OpenAiApi}/{@code OpenAiChatModel} by editing this class only.
 * Not a Spring bean: each factory creates one from the collaborators it already takes, so
 * constructor signatures and bean wiring are unchanged.
 */
final class OpenAiCompatModelBuilder {

    private final LlmHttpClientFactory httpClients;
    private final ResilientToolCallingManagerFactory toolCallingManagers;
    private final LlmModelSupport modelSupport;

    OpenAiCompatModelBuilder(
        LlmHttpClientFactory httpClients,
        ResilientToolCallingManagerFactory toolCallingManagers,
        LlmModelSupport modelSupport
    ) {
        this.httpClients = httpClients;
        this.toolCallingManagers = toolCallingManagers;
        this.modelSupport = modelSupport;
    }

    /** Starts describing the API endpoint; call {@link Api#build()} when done. */
    Api api(String baseUrl) {
        return new Api(baseUrl);
    }

    /** A fresh timeout-bounded blocking-path builder, for factories that must customise it (Grok). */
    RestClient.Builder restClientBuilder() {
        return httpClients.restClientBuilder();
    }

    /** A fresh timeout-bounded streaming-path builder, for factories that must customise it (Grok). */
    WebClient.Builder webClientBuilder() {
        return httpClients.webClientBuilder();
    }

    /** {@code OpenAiChatOptions.builder().model(model).streamUsage(...)} - the base every factory extends. */
    OpenAiChatOptions.Builder options(ProviderName provider, String model) {
        return baseOptions(model, modelSupport.streamUsage(provider));
    }

    /** The same base for callers that already resolved the stream-usage decision (static, unit-testable helpers). */
    static OpenAiChatOptions.Builder baseOptions(String model, boolean streamUsage) {
        return OpenAiChatOptions.builder()
            .model(model)
            .streamUsage(streamUsage);
    }

    /** The chat model with the shared tool-calling manager, bounded retry and the app's observation registry. */
    OpenAiChatModel chatModel(OpenAiApi api, OpenAiChatOptions options) {
        return OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(options)
            .toolCallingManager(toolCallingManagers.create())
            .retryTemplate(modelSupport.retryTemplate())
            .observationRegistry(modelSupport.observationRegistry())
            .build();
    }

    /** Fluent description of one {@link OpenAiApi}; unset parts keep Spring AI's own defaults. */
    final class Api {

        private final String baseUrl;
        private String apiKey;
        private boolean noApiKey;
        private MultiValueMap<String, String> headers;
        private String completionsPath;
        private boolean guarded;
        private RestClient.Builder restClientBuilder;
        private WebClient.Builder webClientBuilder;

        private Api(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /** Bearer key sent as {@code Authorization} (may be null, exactly as passed to Spring AI). */
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

        Api headers(MultiValueMap<String, String> headers) {
            this.headers = headers;
            return this;
        }

        /** Overrides Spring AI's default {@code /v1/chat/completions}; a null path keeps the default. */
        Api completionsPath(String completionsPath) {
            this.completionsPath = completionsPath;
            return this;
        }

        /** Use the SSRF-guarded transport (address-pinned, no redirects) for tenant-supplied endpoints. */
        Api guarded() {
            this.guarded = true;
            return this;
        }

        /** Replaces the default blocking-path builder (see {@link OpenAiCompatModelBuilder#restClientBuilder()}). */
        Api restClientBuilder(RestClient.Builder builder) {
            this.restClientBuilder = builder;
            return this;
        }

        /** Replaces the default streaming-path builder (see {@link OpenAiCompatModelBuilder#webClientBuilder()}). */
        Api webClientBuilder(WebClient.Builder builder) {
            this.webClientBuilder = builder;
            return this;
        }

        OpenAiApi build() {
            OpenAiApi.Builder builder = OpenAiApi.builder().baseUrl(baseUrl);
            if (noApiKey) {
                builder.apiKey(new NoopApiKey());
            } else {
                builder.apiKey(apiKey);
            }
            if (headers != null) {
                builder.headers(headers);
            }
            if (completionsPath != null) {
                builder.completionsPath(completionsPath);
            }
            builder.restClientBuilder(restClientBuilder != null ? restClientBuilder
                : guarded ? httpClients.guardedRestClientBuilder() : httpClients.restClientBuilder());
            builder.webClientBuilder(webClientBuilder != null ? webClientBuilder
                : guarded ? httpClients.guardedWebClientBuilder() : httpClients.webClientBuilder());
            return builder.build();
        }
    }
}
