package com.agenticai.core.providers.localmodels;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The only door from the local-models API to Ollama: {@code GET /api/tags} (what is installed),
 * {@code POST /api/pull} (install a model, streamed) and {@code POST /api/generate} with no prompt
 * ({@link #warm}: load a model and keep it resident).
 *
 * <p><b>Which server.</b> Only the operator-configured Ollama, {@code
 * app.local-models.ollama-base-url} (default: {@code app.local-llm.bootstrap.base-url}, i.e. {@code
 * http://ollama:11434}). No request parameter can name another host, so these endpoints cannot be
 * turned into a proxy.
 *
 * <p><b>SSRF.</b> The configured URL goes through the shared {@link SsrfGuard} on every call, with
 * the same rules as every other model-server URL: a private or loopback Ollama must be listed in
 * {@code app.security.outbound.allowed-private-hosts} ({@code ollama:11434}, {@code
 * localhost:11434}). The HTTP client is the {@code SafeHttpClients} one: connects only to addresses
 * the guard validated and follows no redirects. A refusal is logged once and reported as "not
 * reachable".
 */
@Component
public class LocalModelOllamaGateway implements com.agenticai.core.providers.LocalModelWarmer {

    private static final Logger log = LoggerFactory.getLogger(LocalModelOllamaGateway.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /** Installed model tags as Ollama names them (a bare name carries {@code :latest}). */
    public record Tags(boolean reachable, List<String> installed) {
        static final Tags UNREACHABLE = new Tags(false, List.of());

        /** True when {@code tag} (or {@code tag:latest}) is installed. */
        public boolean has(String tag) {
            if (tag == null) {
                return false;
            }
            return installed.stream()
                    .anyMatch(
                            t ->
                                    t.equalsIgnoreCase(tag)
                                            || (!tag.contains(":")
                                                    && t.equalsIgnoreCase(tag + ":latest")));
        }
    }

    /** A pull that did not complete; the message is safe to show to a user. */
    public static final class PullException extends IOException {
        private static final long serialVersionUID = 1L;

        PullException(String message) {
            super(message);
        }
    }

    private final SsrfGuard guard;
    private final String baseUrl;
    private final CloseableHttpClient quickHttp;
    private final CloseableHttpClient pullHttp;
    private final RestClient quick;
    private final RestClient pull;
    private volatile boolean refusalLogged;

    /**
     * How long Ollama keeps a model loaded after {@link #warm}; Ollama's {@code keep_alive} syntax
     * ({@code 30m}, {@code 1h}, {@code -1} for forever). Field-injected so the two-argument
     * constructor the tests use stays as it is; a hand-built gateway gets the default.
     */
    @Value("${app.local-models.keep-alive:30m}")
    private String keepAlive = "30m";

    /**
     * The context core asks Ollama for on every chat (OllamaAgentChatClientFactory); compared with
     * what was loaded.
     */
    @Value("${app.llm.ollama.num-ctx:32768}")
    private int wantedContext = 32768;

    public LocalModelOllamaGateway(
            SsrfGuard guard,
            @Value(
                            "${app.local-models.ollama-base-url:${app.local-llm.bootstrap.base-url:http://ollama:11434}}")
                    String baseUrl) {
        this.guard = guard;
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = trimmed.replaceAll("/+$", "");
        this.quickHttp =
                com.agenticai.core.security.net.SafeHttpClients.create(
                        guard, Duration.ofSeconds(3), Duration.ofSeconds(8), false);
        // A pull is silent for as long as Ollama verifies a layer; the read timeout is per read,
        // not total.
        this.pullHttp =
                com.agenticai.core.security.net.SafeHttpClients.create(
                        guard, Duration.ofSeconds(3), Duration.ofMinutes(20), false);
        this.quick =
                RestClient.builder()
                        .requestFactory(new HttpComponentsClientHttpRequestFactory(quickHttp))
                        .build();
        this.pull =
                RestClient.builder()
                        .requestFactory(new HttpComponentsClientHttpRequestFactory(pullHttp))
                        .build();
    }

    @PreDestroy
    void close() {
        for (CloseableHttpClient client : List.of(quickHttp, pullHttp)) {
            try {
                client.close();
            } catch (IOException e) {
                log.debug("Closing the Ollama HTTP client failed: {}", e.toString());
            }
        }
    }

    /** The configured Ollama base URL, shown to the console. */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Loads {@code tag} into memory and asks Ollama to keep it there for {@link #keepAlive}: {@code
     * POST /api/generate} with no prompt, which Ollama documents as the warm-up call. The first
     * chat after a cold start otherwise pays the whole weight load (tens of seconds for a 4B model
     * on a laptop) before its first token, and Ollama's default unload after five idle minutes
     * makes that happen again after every pause. Blocks for the load; never throws.
     *
     * @return the load time in milliseconds, or empty when the server is not reachable, refused, or
     *     does not know the tag
     */
    @Override
    public Optional<Long> warm(String tag) {
        return warm(tag, null);
    }

    /**
     * {@link #warm(String)} with this row's own keep-alive (Providers -> Local model); blank or
     * null = the configured default.
     */
    public Optional<Long> warm(String tag, String keepAliveOverride) {
        String keep =
                keepAliveOverride == null || keepAliveOverride.isBlank()
                        ? keepAlive
                        : keepAliveOverride.trim();
        Optional<String> url = allowedBase();
        if (url.isEmpty() || !StringUtils.hasText(tag)) {
            return Optional.empty();
        }
        ObjectNode body =
                MAPPER.createObjectNode()
                        .put("model", tag)
                        .put("keep_alive", keep)
                        .put("stream", false);
        long started = System.nanoTime();
        try {
            // The pull client: a cold load is silent for longer than the quick client's 8 s read
            // timeout.
            pull.post()
                    .uri(url.get() + "/api/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .toBodilessEntity();
            long ms = (System.nanoTime() - started) / 1_000_000;
            log.info("Local model '{}' loaded in {} ms (keep_alive {}).", tag, ms, keep);
            checkLoadedContext(url.get(), tag);
            return Optional.of(ms);
        } catch (RuntimeException e) {
            log.debug("Warming local model '{}' at {} failed: {}", tag, baseUrl, e.toString());
            return Optional.empty();
        }
    }

    /**
     * The context window Ollama actually loaded {@code tag} with ({@code GET /api/ps}), or empty
     * when unknown. Some runners (the MLX runner on Apple Silicon, seen 2026-10-18) ignore the
     * per-request {@code num_ctx} core sends and load the server default of 4096 instead - smaller
     * than an agent prompt with tools, which then gets truncated and answers badly or not at all.
     */
    public Optional<Integer> loadedContextLength(String tag) {
        Optional<String> url = allowedBase();
        if (url.isEmpty() || !StringUtils.hasText(tag)) {
            return Optional.empty();
        }
        try {
            JsonNode body = quick.get().uri(url.get() + "/api/ps").retrieve().body(JsonNode.class);
            if (body != null && body.path("models").isArray()) {
                for (JsonNode model : body.get("models")) {
                    String name = model.path("name").asString(model.path("model").asString(""));
                    if (name.equalsIgnoreCase(tag)
                            || (!tag.contains(":") && name.equalsIgnoreCase(tag + ":latest"))) {
                        return model.hasNonNull("context_length")
                                ? Optional.of(model.get("context_length").asInt())
                                : Optional.empty();
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("Ollama at {} did not answer /api/ps: {}", baseUrl, e.toString());
        }
        return Optional.empty();
    }

    /** Logs, once per load, when the server loaded a smaller context than core asks for. */
    private void checkLoadedContext(String url, String tag) {
        Optional<Integer> loaded = loadedContextLength(tag);
        if (loaded.isPresent() && loaded.get() < wantedContext) {
            log.warn(
                    "Local model '{}' is loaded with a {}-token context but core sends prompts for"
                            + " {} (app.llm.ollama.num-ctx). This Ollama runner ignores the per-request"
                            + " num_ctx, so long agent prompts are truncated. Set"
                            + " OLLAMA_CONTEXT_LENGTH={} in the Ollama server's environment and restart"
                            + " it.",
                    tag,
                    loaded.get(),
                    wantedContext,
                    wantedContext);
        }
    }

    /**
     * Creates {@code derived} as {@code base} with {@code PARAMETER num_ctx}: {@code POST
     * /api/create} with {@code from}, which reuses the base model's weights (no download, no copy).
     * The only way to give a model a bigger context on a runner that ignores the per-request {@code
     * num_ctx} (the MLX runner on Apple Silicon loads 4096 whatever core sends), and it works on
     * every runner. Blocks.
     *
     * @return true when Ollama reported success
     */
    public boolean createWithContext(String base, String derived, int numCtx) {
        Optional<String> url = allowedBase();
        if (url.isEmpty()) {
            return false;
        }
        ObjectNode body =
                MAPPER.createObjectNode()
                        .put("model", derived)
                        .put("from", base)
                        .put("stream", false);
        body.putObject("parameters").put("num_ctx", numCtx);
        try {
            JsonNode reply =
                    pull.post()
                            .uri(url.get() + "/api/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body.toString())
                            .retrieve()
                            .body(JsonNode.class);
            boolean ok =
                    reply != null && "success".equalsIgnoreCase(reply.path("status").asString());
            log.info(
                    "Local model '{}' created from '{}' with a {}-token context: {}",
                    derived,
                    base,
                    numCtx,
                    ok ? "ok" : "refused");
            return ok;
        } catch (RuntimeException e) {
            log.warn("Creating local model '{}' from '{}' failed: {}", derived, base, e.toString());
            return false;
        }
    }

    /**
     * What is installed; {@link Tags#UNREACHABLE} when the server is not allowed, down, or odd.
     * Never throws.
     */
    public Tags tags() {
        Optional<String> url = allowedBase();
        if (url.isEmpty()) {
            return Tags.UNREACHABLE;
        }
        try {
            JsonNode body =
                    quick.get().uri(url.get() + "/api/tags").retrieve().body(JsonNode.class);
            List<String> names = new ArrayList<>();
            if (body != null && body.path("models").isArray()) {
                for (JsonNode model : body.get("models")) {
                    String name =
                            model.hasNonNull("name")
                                    ? model.get("name").asString()
                                    : model.path("model").asString("");
                    if (!name.isBlank()) {
                        names.add(name);
                    }
                }
            }
            return new Tags(true, List.copyOf(names));
        } catch (RuntimeException e) {
            log.debug("Ollama at {} did not answer /api/tags: {}", baseUrl, e.toString());
            return Tags.UNREACHABLE;
        }
    }

    /**
     * Installs {@code tag}: streams {@code POST /api/pull} and hands every progress object to
     * {@code onProgress} until Ollama says {@code success}. Blocks for as long as the download
     * takes.
     *
     * @throws PullException the server refused the tag, reported an error, or stopped answering
     */
    public void pull(String tag, Consumer<JsonNode> onProgress) throws PullException {
        String url =
                allowedBase()
                        .orElseThrow(() -> new PullException("The model server is not reachable."));
        ObjectNode body = MAPPER.createObjectNode().put("model", tag).put("stream", true);
        try {
            boolean ok =
                    pull.post()
                            .uri(url + "/api/pull")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body.toString())
                            .exchange(
                                    (request, response) -> {
                                        if (!response.getStatusCode().is2xxSuccessful()) {
                                            String detail =
                                                    errorText(response.getBody().readNBytes(4096));
                                            throw new PullException(
                                                    detail.isEmpty()
                                                            ? "The model server answered HTTP "
                                                                    + response.getStatusCode()
                                                                            .value()
                                                                    + "."
                                                            : detail);
                                        }
                                        try (BufferedReader reader =
                                                new BufferedReader(
                                                        new InputStreamReader(
                                                                response.getBody(),
                                                                StandardCharsets.UTF_8))) {
                                            String line;
                                            while ((line = reader.readLine()) != null) {
                                                if (line.isBlank()) {
                                                    continue;
                                                }
                                                JsonNode event = MAPPER.readTree(line);
                                                if (event.hasNonNull("error")) {
                                                    throw new PullException(
                                                            clip(event.get("error").asString()));
                                                }
                                                onProgress.accept(event);
                                                if ("success"
                                                        .equalsIgnoreCase(
                                                                event.path("status").asString())) {
                                                    return true;
                                                }
                                            }
                                        }
                                        return false;
                                    },
                                    true);
            if (!ok) {
                throw new PullException("The model server closed the download before it finished.");
            }
        } catch (RuntimeException e) {
            if (e.getCause() instanceof PullException pe) {
                throw pe;
            }
            log.warn("Pulling '{}' from {} failed: {}", tag, baseUrl, e.toString());
            throw new PullException("The model server stopped answering during the download.");
        }
    }

    private Optional<String> allowedBase() {
        if (!StringUtils.hasText(baseUrl)) {
            return Optional.empty();
        }
        try {
            guard.validate(baseUrl);
            return Optional.of(baseUrl);
        } catch (SsrfBlockedException e) {
            if (!refusalLogged) {
                refusalLogged = true;
                log.warn(
                        "The Ollama URL {} is refused by the SSRF guard ({}). List the host in"
                                + " app.security.outbound.allowed-private-hosts to use a local model"
                                + " server.",
                        baseUrl,
                        e.getMessage());
            }
            return Optional.empty();
        }
    }

    private static String errorText(byte[] bytes) {
        try {
            JsonNode node = MAPPER.readTree(bytes);
            if (node != null && node.hasNonNull("error")) {
                return clip(node.get("error").asString());
            }
        } catch (RuntimeException e) {
            // not JSON: no usable detail
        }
        return "";
    }

    private static String clip(String text) {
        String single = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return single.length() > 300 ? single.substring(0, 300) : single;
    }
}
