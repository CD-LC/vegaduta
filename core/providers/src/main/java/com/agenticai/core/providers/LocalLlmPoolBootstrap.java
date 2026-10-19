package com.agenticai.core.providers;

import com.agenticai.core.providers.validation.ProviderValidationException;
import com.agenticai.core.security.net.SsrfGuard;
import com.agenticai.core.security.startup.BackgroundStartupTask;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Registers a local Ollama (or any OpenAI-compatible local server) as the FREE-tier platform pool
 * row at startup, so a fresh install needs no manual {@code POST
 * /api/platform/hosted-llm-providers}.
 *
 * <p>Off by default; turn it on with {@code app.local-llm.bootstrap.enabled=true} (env {@code
 * APP_LOCAL_LLM_BOOTSTRAP_ENABLED}). Runs once on {@link ApplicationReadyEvent} and never fails
 * startup: every problem (server unreachable, SSRF guard refusal, model not pulled, database error)
 * is logged at WARN and the application carries on.
 *
 * <ul>
 *   <li><b>Idempotent.</b> If a pool row with the same label or the same base URL exists, nothing
 *       is written, so an admin's later edits survive restarts. Two instances starting together may
 *       both insert; each then re-reads the matching rows and the one with the higher id deletes
 *       itself, leaving exactly one.
 *   <li><b>Goes through {@link PlatformHostedLlmProviderService}</b>, so the key is encrypted with
 *       {@code APP_ENCRYPTION_KEY}, the base URL passes the SSRF guard and the endpoint is probed
 *       exactly as for a hand-made row. A private host such as {@code ollama:11434} must be listed
 *       in {@code app.security.outbound.allowed-private-hosts}.
 *   <li><b>Priority.</b> The new row sorts ahead of every existing row.
 *   <li><b>Keyless.</b> An empty {@code api-key} stores no secret and the chat client sends no
 *       {@code Authorization} header. The default is a harmless placeholder bearer value, which
 *       Ollama ignores.
 *   <li><b>API shape only.</b> The jobs and worker shapes list this class under {@code
 *       app.scheduling.skip-listener-classes}, so its listener is not registered there.
 * </ul>
 *
 * The control-plane table is schema-qualified, so no tenant context is needed.
 */
@Component
@ConditionalOnProperty(name = "app.local-llm.bootstrap.enabled", havingValue = "true")
public class LocalLlmPoolBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LocalLlmPoolBootstrap.class);

    private static final int MAX_ATTEMPTS = 6;

    /** What one attempt did; {@code UNREACHABLE} is the only outcome worth retrying. */
    enum Outcome {
        CREATED,
        ALREADY_PRESENT,
        REFUSED,
        UNREACHABLE,
        INVALID_CONFIG,
        FAILED
    }

    private final PlatformHostedLlmProviderService service;
    private final PlatformHostedLlmProviderRepository repository;
    private final RestClient restClient;
    private final String baseUrl;
    private final String model;
    private final String label;
    private final String apiKey;
    private final long retryDelayMillis;
    private SsrfGuard ssrfGuard;

    public LocalLlmPoolBootstrap(
            PlatformHostedLlmProviderService service,
            PlatformHostedLlmProviderRepository repository,
            RestClient.Builder restClientBuilder,
            @Value("${app.local-llm.bootstrap.base-url:http://ollama:11434}") String baseUrl,
            @Value("${app.local-llm.bootstrap.model:qwen3.5:4b}") String model,
            @Value("${app.local-llm.bootstrap.label:Local model (Ollama)}") String label,
            @Value("${app.local-llm.bootstrap.api-key:ollama}") String apiKey,
            @Value("${app.local-llm.bootstrap.retry-delay-ms:10000}") long retryDelayMillis) {
        this.service = service;
        this.repository = repository;
        this.restClient = restClientBuilder.build();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.label = label == null ? "" : label.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.retryDelayMillis = retryDelayMillis;
    }

    @Autowired(required = false)
    void setSsrfGuard(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    /** Optional: when a local-model warmer is present, the registered model is loaded after bootstrap. */
    @Autowired(required = false)
    void setLocalModels(LocalModelWarmer gateway) {
        this.localModels = gateway;
    }

    private LocalModelWarmer localModels;

    /** Off the main thread: a slow or absent Ollama must not hold up readiness. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        BackgroundStartupTask.run(true, "local-llm-pool-bootstrap", this::runWithRetries);
    }

    /** Retries only while the server is unreachable (Ollama often boots after core). */
    Outcome runWithRetries() {
        Outcome outcome = Outcome.FAILED;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            outcome = bootstrapOnce();
            if (outcome != Outcome.UNREACHABLE || attempt == MAX_ATTEMPTS) {
                break;
            }
            try {
                Thread.sleep(retryDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (outcome == Outcome.UNREACHABLE) {
            log.warn(
                    "Local LLM pool bootstrap gave up: {} was not reachable. Start the server and"
                            + " restart core, or add the row in Platform Admin.",
                    baseUrl);
        }
        if ((outcome == Outcome.CREATED || outcome == Outcome.ALREADY_PRESENT) && localModels != null) {
            // Still on the bootstrap thread: load the pool row's model now so the first chat of
            // the session does not pay the cold start on top of its own latency.
            String normalizedBase = HostedPoolEndpointProbe.normalizeBaseUrl(baseUrl);
            repository.findAllByOrderByPriorityAsc().stream()
                    .filter(row -> matches(row, normalizedBase))
                    .findFirst()
                    .map(PlatformHostedLlmProvider::getModel)
                    .ifPresent(localModels::warm);
        }
        return outcome;
    }

    /** One bootstrap attempt. Never throws. */
    Outcome bootstrapOnce() {
        try {
            return doBootstrap();
        } catch (ProviderValidationException e) {
            // Thrown by the pool service for an SSRF refusal or a failed probe; the message
            // is operator-facing and carries no secrets.
            log.warn("Local LLM pool bootstrap could not register '{}': {}", label, e.getMessage());
            return isUnreachable(e) ? Outcome.UNREACHABLE : Outcome.REFUSED;
        } catch (RuntimeException e) {
            log.warn("Local LLM pool bootstrap failed for '{}' (startup continues)", label, e);
            return Outcome.FAILED;
        }
    }

    private Outcome doBootstrap() {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(model)) {
            log.warn(
                    "Local LLM pool bootstrap is enabled but app.local-llm.bootstrap.base-url or"
                            + " .model is empty - nothing registered.");
            return Outcome.INVALID_CONFIG;
        }
        String normalizedBase = HostedPoolEndpointProbe.normalizeBaseUrl(baseUrl);
        List<PlatformHostedLlmProvider> existing = repository.findAllByOrderByPriorityAsc();
        if (existing.stream().anyMatch(row -> matches(row, normalizedBase))) {
            log.info(
                    "Local LLM pool row '{}' ({}) already exists - bootstrap left it unchanged.",
                    label,
                    normalizedBase);
            return Outcome.ALREADY_PRESENT;
        }

        // Guard before the first outbound byte so a refusal comes with a precise hint.
        try {
            HostedLlmBaseUrlGuard.checkAllowed(
                    normalizedBase, ssrfGuard != null ? ssrfGuard : SsrfGuard.defaultGuard());
        } catch (ProviderValidationException e) {
            log.warn(
                    "Local LLM pool bootstrap refused {} by the SSRF guard: {}. To allow it, add"
                            + " its host[:port] to app.security.outbound.allowed-private-hosts"
                            + " (env APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS), e.g. '{}'.",
                    normalizedBase,
                    e.getMessage(),
                    hostPort(normalizedBase));
            return Outcome.REFUSED;
        }

        Boolean modelPresent = modelIsAvailable(normalizedBase);
        if (Boolean.FALSE.equals(modelPresent)) {
            log.warn(
                    "Model '{}' is not available on {}. The pool row is still created; run: ollama"
                            + " pull {}",
                    model,
                    normalizedBase,
                    model);
        }

        int priority =
                existing.stream().mapToInt(PlatformHostedLlmProvider::getPriority).min().orElse(1)
                        - 1;
        PlatformHostedLlmProviderResponse created =
                service.createAllowingBlankKey(
                        new PlatformHostedLlmProviderUpsertRequest(
                                label,
                                normalizedBase,
                                model,
                                apiKey,
                                true,
                                priority,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                // V484 - a model the operator runs is free: never priced,
                                // never debited, first in the failover order.
                                Boolean.TRUE));
        resolveRaceIfDuplicated(created.id(), normalizedBase);
        log.info(
                "Local LLM pool row '{}' registered (model {}, base URL {}, priority {}, tool calls:"
                        + " {}).",
                label,
                model,
                normalizedBase,
                priority,
                created.toolCallGrade() == null ? "not measured" : created.toolCallGrade());
        if (ToolCallGrade.NONE.name().equals(created.toolCallGrade())) {
            log.warn(
                    "Model '{}' is chat only: it does not make tool calls, so agents with tools will"
                            + " not work on it while it is the only pool row. Pull a tools-capable"
                            + " model (scripts/vegaduta models list) and add it as another row.",
                    model);
        }
        return Outcome.CREATED;
    }

    private boolean matches(PlatformHostedLlmProvider row, String normalizedBase) {
        return label.equalsIgnoreCase(row.getLabel())
                || normalizedBase.equalsIgnoreCase(
                        HostedPoolEndpointProbe.normalizeBaseUrl(row.getBaseUrl()));
    }

    /** Two instances racing both insert; the higher id removes itself. */
    private void resolveRaceIfDuplicated(Long ownId, String normalizedBase) {
        try {
            List<PlatformHostedLlmProvider> same =
                    repository.findAllByOrderByPriorityAsc().stream()
                            .filter(row -> matches(row, normalizedBase))
                            .toList();
            boolean lowerIdExists =
                    same.stream().anyMatch(row -> row.getId() != null && row.getId() < ownId);
            if (lowerIdExists) {
                repository.deleteById(ownId);
                log.info(
                        "Local LLM pool row created concurrently by another instance; kept theirs.");
            }
        } catch (RuntimeException e) {
            log.warn("Local LLM pool bootstrap could not check for a concurrent duplicate", e);
        }
    }

    /**
     * TRUE/FALSE when the server answered with a model list, null when it could not be determined
     * (the create call that follows reports an unreachable server). Ollama's {@code /api/tags}
     * first, then the OpenAI-style {@code /v1/models}.
     */
    Boolean modelIsAvailable(String normalizedBase) {
        for (String path : List.of("/api/tags", "/v1/models")) {
            try {
                JsonNode body =
                        restClient.get().uri(normalizedBase + path).retrieve().body(JsonNode.class);
                if (body == null) {
                    continue;
                }
                JsonNode list = body.has("models") ? body.get("models") : body.get("data");
                if (list == null || !list.isArray()) {
                    continue;
                }
                for (JsonNode entry : list) {
                    for (String field : List.of("name", "model", "id")) {
                        if (entry.hasNonNull(field) && sameModel(entry.get(field).asText())) {
                            return Boolean.TRUE;
                        }
                    }
                }
                return Boolean.FALSE;
            } catch (RuntimeException e) {
                log.debug("Model listing at {}{} failed: {}", normalizedBase, path, e.toString());
            }
        }
        return null;
    }

    /** "llama3.2" matches "llama3.2:latest"; an explicit tag must match exactly. */
    private boolean sameModel(String listed) {
        String want = model.toLowerCase(Locale.ROOT);
        String have = listed.toLowerCase(Locale.ROOT);
        return have.equals(want) || have.equals(want + ":latest");
    }

    private static boolean isUnreachable(ProviderValidationException e) {
        return e.getCause() != null
                || (e.getMessage() != null && e.getMessage().startsWith("Could not reach"));
    }

    private static String hostPort(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
        } catch (RuntimeException e) {
            return url;
        }
    }
}
