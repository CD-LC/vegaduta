package com.agenticai.core.providers;

import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
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
 *   <li><b>Idempotent.</b> If a pool row with the same base URL exists, nothing is written, so an
 *       admin's later edits (model, label, priority) survive restarts.
 *   <li><b>Follows the install.</b> A row with this label but another base URL is the install's own
 *       row from before Ollama moved (the Docker container before, the computer's own Ollama now,
 *       or the reverse): it is repointed to the configured base URL and model, keeping its
 *       priority, enabled flag and overrides. Without this the pool keeps calling an Ollama that no
 *       longer runs and the local model is never found. Two instances starting together may both
 *       insert; each then re-reads the matching rows and the one with the higher id deletes itself,
 *       leaving exactly one.
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
        MOVED,
        MODEL_REPLACED,
        REPAIRED,
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

    /**
     * Optional: when a local-model warmer is present, the registered model is loaded after
     * bootstrap.
     */
    @Autowired(required = false)
    void setLocalModels(LocalModelWarmer gateway) {
        this.localModels = gateway;
    }

    private LocalModelWarmer localModels;

    /** Last on-demand repair, epoch millis; one attempt per {@link #ON_DEMAND_INTERVAL_MILLIS}. */
    private final java.util.concurrent.atomic.AtomicLong lastOnDemand =
            new java.util.concurrent.atomic.AtomicLong();

    static final long ON_DEMAND_INTERVAL_MILLIS = 30_000;

    /**
     * Called by the chat path when the platform pool resolves to nothing: runs one bootstrap pass
     * now (register, move, re-enable or re-key the local row, switch the pool back on) so the very
     * turn that found the pool empty can still be answered, with no restart. At most one attempt
     * every 30 seconds across threads; a pass that changed nothing returns false.
     */
    public boolean repairOnDemand() {
        long now = System.currentTimeMillis();
        long last = lastOnDemand.get();
        if (now - last < ON_DEMAND_INTERVAL_MILLIS || !lastOnDemand.compareAndSet(last, now)) {
            return false;
        }
        Outcome outcome = bootstrapOnce();
        boolean changed =
                outcome == Outcome.CREATED
                        || outcome == Outcome.MOVED
                        || outcome == Outcome.MODEL_REPLACED
                        || outcome == Outcome.REPAIRED;
        if (changed) {
            log.warn(
                    "Local LLM pool row repaired on demand ({}) - the chat continues on it.",
                    outcome);
        }
        return changed;
    }

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
        if ((outcome == Outcome.CREATED
                        || outcome == Outcome.ALREADY_PRESENT
                        || outcome == Outcome.MOVED
                        || outcome == Outcome.MODEL_REPLACED
                        || outcome == Outcome.REPAIRED)
                && localModels != null) {
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
        PlatformHostedLlmProvider same =
                existing.stream()
                        .filter(row -> sameBaseUrl(row, normalizedBase))
                        .findFirst()
                        .orElse(null);
        if (same != null) {
            if (replacedMissingModel(same, normalizedBase)) {
                return Outcome.MODEL_REPLACED;
            }
            if (repairUnusable(same, normalizedBase)) {
                return Outcome.REPAIRED;
            }
            log.info(
                    "Local LLM pool row '{}' ({}) already exists - bootstrap left it unchanged.",
                    label,
                    normalizedBase);
            return Outcome.ALREADY_PRESENT;
        }

        PlatformHostedLlmProvider moved =
                existing.stream()
                        .filter(row -> label.equalsIgnoreCase(row.getLabel()))
                        .findFirst()
                        .orElse(null);

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

        if (moved != null) {
            service.update(
                    moved.getId(),
                    new PlatformHostedLlmProviderUpsertRequest(
                            moved.getLabel(),
                            normalizedBase,
                            model,
                            // blank keeps the stored key (the keyless placeholder for Ollama)
                            null,
                            moved.isEnabled(),
                            moved.getPriority(),
                            moved.getRegion(),
                            null,
                            null,
                            moved.getAuthType(),
                            null,
                            null,
                            Boolean.TRUE,
                            moved.getToolRouterEnabled(),
                            moved.getSmallModel()));
            log.info(
                    "Local LLM pool row '{}' moved from {} to {} (model {}).",
                    moved.getLabel(),
                    moved.getBaseUrl(),
                    normalizedBase,
                    model);
            return Outcome.MOVED;
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

    private static boolean sameBaseUrl(PlatformHostedLlmProvider row, String normalizedBase) {
        return normalizedBase.equalsIgnoreCase(
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
     * A row whose model is not on its server while the configured one is (the model was removed, or
     * never pulled there) fails every chat; it is switched to the configured model. A model the
     * operator picked that is still installed is never touched.
     */
    private boolean replacedMissingModel(PlatformHostedLlmProvider row, String normalizedBase) {
        String current = row.getModel();
        if (current != null && sameModel(current, model)) {
            return false;
        }
        boolean currentMissing =
                current == null
                        || current.isBlank()
                        || Boolean.FALSE.equals(modelIsAvailable(normalizedBase, current));
        if (!currentMissing || !Boolean.TRUE.equals(modelIsAvailable(normalizedBase, model))) {
            return false;
        }
        service.updateModel(row.getId(), model);
        log.warn(
                "Local LLM pool row '{}' used model '{}', which is not on {}; switched it to '{}'.",
                row.getLabel(),
                current,
                normalizedBase,
                model);
        return true;
    }

    /**
     * The row exists, but a chat could not use it: the pool's master switch is off, the row is
     * disabled, or its stored key was encrypted with an {@code APP_ENCRYPTION_KEY} this install no
     * longer has (an older database kept under a new {@code .env}). Every chat then answers "No
     * platform LLM is configured" while the console shows the model as in use. The local row's key
     * is the configured placeholder, so an enabled row whose key no longer decrypts is always
     * re-keyed; the pool switch and the row's enabled flag are put back on only when nothing else
     * in the pool could serve, so an admin's deliberate choice survives a restart. A base URL the
     * SSRF guard refuses cannot be repaired here and is logged with the host to allow.
     */
    private boolean repairUnusable(PlatformHostedLlmProvider row, String normalizedBase) {
        boolean repaired = false;
        HostedLlmPoolSettingsResponse settings = service.getPoolSettings();
        if (settings != null && !settings.enabled()) {
            // Only when this row is the pool's whole content: an admin who switched the pool off
            // while a hosted row exists meant it, and a restart must not undo that.
            List<PlatformHostedLlmProvider> enabledRows =
                    repository.findAllByEnabledTrueOrderByPriorityAsc();
            boolean othersEnabled =
                    enabledRows != null
                            && enabledRows.stream().anyMatch(r -> !r.getId().equals(row.getId()));
            if (othersEnabled) {
                log.info(
                        "The platform LLM pool is switched off and other rows exist; leaving it as"
                                + " the admin set it.");
                return false;
            }
            service.setPoolEnabled(true, "local-llm-pool-bootstrap");
            log.warn(
                    "The platform LLM pool was switched off, so no chat could use the local model;"
                            + " switched it back on.");
            repaired = true;
        }
        java.util.Optional<HostedProviderSelection> usable =
                service.resolveEnabledSelectionById(row.getId());
        if (usable != null && usable.isPresent()) {
            return repaired;
        }
        if (!row.isEnabled()) {
            // A disabled local row is re-enabled only when nothing else in the pool can serve;
            // with a hosted row serving, the admin disabled it on purpose.
            List<HostedProviderSelection> chain = service.resolveEnabledChain();
            if (chain != null && !chain.isEmpty()) {
                log.info(
                        "Local LLM pool row '{}' is disabled while {} other row(s) serve; leaving"
                                + " it as the admin set it.",
                        row.getLabel(),
                        chain.size());
                return repaired;
            }
        }
        try {
            HostedLlmBaseUrlGuard.checkAllowed(
                    normalizedBase, ssrfGuard != null ? ssrfGuard : SsrfGuard.defaultGuard());
        } catch (ProviderValidationException e) {
            log.warn(
                    "Local LLM pool row '{}' points at {}, which the SSRF guard refuses: {}. Add"
                            + " '{}' to APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS.",
                    row.getLabel(),
                    normalizedBase,
                    e.getMessage(),
                    hostPort(normalizedBase));
            return repaired;
        }
        if (!StringUtils.hasText(apiKey)) {
            log.warn(
                    "Local LLM pool row '{}' cannot be used by chats (disabled, or its key no longer"
                            + " decrypts) and app.local-llm.bootstrap.api-key is blank, so it was"
                            + " not rewritten. Re-enter the row in Platform Admin.",
                    row.getLabel());
            return repaired;
        }
        String keepModel = StringUtils.hasText(row.getModel()) ? row.getModel() : model;
        service.update(
                row.getId(),
                new PlatformHostedLlmProviderUpsertRequest(
                        row.getLabel(),
                        normalizedBase,
                        keepModel,
                        apiKey,
                        true,
                        row.getPriority(),
                        row.getRegion(),
                        null,
                        null,
                        row.getAuthType(),
                        null,
                        null,
                        Boolean.TRUE,
                        row.getToolRouterEnabled(),
                        row.getSmallModel()));
        log.warn(
                "Local LLM pool row '{}' could not be used by chats ({}): re-enabled it with a fresh"
                        + " key, so the local model answers again.",
                row.getLabel(),
                row.isEnabled()
                        ? "its key was encrypted with an APP_ENCRYPTION_KEY this install no longer"
                                + " has"
                        : "it was disabled");
        return true;
    }

    Boolean modelIsAvailable(String normalizedBase) {
        return modelIsAvailable(normalizedBase, model);
    }

    /**
     * TRUE/FALSE when the server answered with a model list, null when it could not be determined
     * (the create call that follows reports an unreachable server). Ollama's {@code /api/tags}
     * first, then the OpenAI-style {@code /v1/models}.
     */
    Boolean modelIsAvailable(String normalizedBase, String wanted) {
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
                        if (entry.hasNonNull(field)
                                && sameModel(entry.get(field).asText(), wanted)) {
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
    private static boolean sameModel(String listed, String wanted) {
        String want = wanted.toLowerCase(Locale.ROOT);
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
