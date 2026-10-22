package com.agenticai.core.providers;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Locale;

/**
 * BYOK credential for {@link ProviderName#LOCAL_OPENAI}: the base URL of a model server that speaks
 * the OpenAI chat-completions dialect (vLLM, llama.cpp {@code llama-server}, LM Studio, Jan,
 * LocalAI, ...) and an optional bearer key. Most local servers have no auth at all; the key exists
 * for one fronted by an auth proxy, or started with {@code --api-key}.
 *
 * <p>Same "one JSON string in the existing {@code encrypted_api_key} column" pattern as {@link
 * OllamaCredential}; the web console does the mirror-image encoding and calls the same {@code POST
 * /api/providers}.
 *
 * <p><b>Base URL shapes.</b> Every server documents its endpoint with the {@code /v1} suffix
 * ({@code http://localhost:8000/v1}), while Spring AI's OpenAI client appends {@code
 * /v1/chat/completions} itself. {@link #apiRoot} therefore strips a trailing {@code /v1}, so both
 * {@code http://host:8000} and {@code http://host:8000/v1} work and a sub-path ({@code
 * https://gw.example.com/openai/v1}) is preserved.
 */
public record LocalOpenAiCredential(String baseUrl, String apiKey) {

    private static final ObjectMapper MAPPER = JsonMappers.nonNull();

    public String encode() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to encode local OpenAI-compatible credential", e);
        }
    }

    /**
     * @throws IllegalStateException if {@code json} is not a well-formed encoding of this record -
     *     see {@link OllamaCredential#decode(String)} for how callers surface that.
     */
    public static LocalOpenAiCredential decode(String json) {
        try {
            return MAPPER.readValue(json, LocalOpenAiCredential.class);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed local OpenAI-compatible credential JSON", e);
        }
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** The server root without trailing slashes and without a trailing {@code /v1}. */
    public String apiRoot() {
        return apiRoot(baseUrl);
    }

    /** {@code GET} target for the model listing: {@code {root}/v1/models}. */
    public String modelsUrl() {
        return apiRoot() + "/v1/models";
    }

    static String apiRoot(String baseUrl) {
        String root = stripTrailingSlashes(baseUrl == null ? "" : baseUrl.trim());
        if (root.toLowerCase(Locale.ROOT).endsWith("/v1")) {
            root = stripTrailingSlashes(root.substring(0, root.length() - 3));
        }
        return root;
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * The {@code host:port} entry an operator adds to {@code
     * app.security.outbound.allowed-private-hosts} to permit this server, or null when the URL does
     * not parse. The default port of the scheme is spelled out so the entry is exact.
     */
    public String allowListEntry() {
        try {
            URI uri = URI.create(apiRoot());
            if (uri.getHost() == null) {
                return null;
            }
            int port =
                    uri.getPort() >= 0
                            ? uri.getPort()
                            : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            return uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * True for the host names that mean "this machine": from inside a container that is the
     * container itself, so a platform running in Docker reaches the host's servers at {@code
     * host.docker.internal}.
     */
    public boolean pointsAtLoopback() {
        try {
            String host = URI.create(apiRoot()).getHost();
            if (host == null) {
                return false;
            }
            String h = host.toLowerCase(Locale.ROOT);
            return h.equals("localhost")
                    || h.equals("127.0.0.1")
                    || h.equals("[::1]")
                    || h.equals("::1")
                    || h.endsWith(".localhost");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
