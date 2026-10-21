package com.agenticai.core.providers.localmodels;

import com.agenticai.core.providers.HostedPoolEndpointProbe;
import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderRepository;
import com.agenticai.core.providers.PlatformHostedLlmProviderResponse;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.PlatformHostedLlmProviderUpsertRequest;
import com.agenticai.core.providers.ToolCallCapabilityProbe;
import com.agenticai.core.providers.ToolCallCapabilityProbe.ServerKind;
import com.agenticai.core.providers.ToolCallGrade;
import com.agenticai.core.providers.ToolRouterMode;
import com.agenticai.core.providers.localmodels.ModelRecommender.Pick;
import com.agenticai.core.providers.localmodels.ModelRecommender.Recommendation;
import com.agenticai.core.security.startup.BackgroundStartupTask;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Hardware-aware local model setup behind {@link LocalModelsController}: what this machine can run
 * ({@link HardwareDetector}), what to run on it ({@link ModelCatalog} + {@link ModelRecommender}),
 * what the configured Ollama already has ({@link LocalModelOllamaGateway}), whether a model really
 * makes tool calls ({@link ToolCallCapabilityProbe}, the same probe a hosted-pool row gets when it
 * is saved) and installing one ({@link LocalModelPullService}).
 *
 * <p>The probe is an optional bean: it lives in the chat module, so a context without it answers
 * "service unavailable" instead of failing to start.
 */
@Service
public class LocalModelsService {

    /**
     * Ollama model names: optional namespace, a name, an optional tag. No path tricks, no spaces.
     */
    static final Pattern TAG =
            Pattern.compile(
                    "^[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*){0,2}(:[A-Za-z0-9._-]+)?$");

    static final int MAX_TAG_LENGTH = 128;

    // --- the API's response shapes (field names are the contract) ---

    /**
     * {@code servingModel} is the model the platform-pool row pointing at this install's model
     * server currently serves (null when no such row exists) - so the catalog UI can mark which tag
     * agents actually use, not just which ones are downloaded.
     */
    public record Recommended(
            HardwareProfile hardware,
            Pick recommended,
            List<Pick> alternatives,
            /**
             * Every catalog row in file order, each flagged {@code fits} against {@code budgetGb}.
             */
            List<Pick> catalog,
            /** The memory, in GB, this machine can give a model (ModelRecommender#budgetGb). */
            double budgetGb,
            OllamaState ollama,
            String servingModel,
            /**
             * Why a chat cannot use the serving row although it exists, null when it can: {@code
             * pool-off} (the platform pool's master switch), {@code row-disabled}, or {@code
             * row-unusable} (its key no longer decrypts, or its URL is refused). Core repairs all
             * three at the next start.
             */
            String servingBlocked) {}

    public record OllamaState(boolean reachable, String baseUrl, List<String> installedTags) {}

    public record ProbeResult(
            String tag,
            String toolCalling,
            boolean structuredOutput,
            long latencyMs,
            String toolRouterMode) {}

    /**
     * What a runtime model switch did: the tag now on the pool row, whether the row was created
     * fresh (bootstrap had left none), whether that row is enabled, and its measured tool-call
     * grade (null when unmeasured).
     */
    public record UseResult(
            String tag, Long rowId, boolean created, boolean enabled, String toolCallGrade) {}

    /**
     * The local model's console-editable behaviour. {@code reasoningEffort}: none, low, medium,
     * high, or {@code default} (the model's own behaviour). {@code loadedContext} is what Ollama
     * actually loaded the model with (null when it is not loaded), {@code wantedContext} what core
     * asks for.
     */
    public record Settings(
            String servingModel,
            String reasoningEffort,
            String keepAlive,
            Integer contextLength,
            Integer loadedContext,
            int wantedContext) {}

    /** Body of {@code PUT /settings}; a null field is left as it is. */
    public record SettingsUpdate(String reasoningEffort, String keepAlive, Integer contextLength) {}

    static final java.util.Set<String> EFFORTS =
            java.util.Set.of("none", "low", "medium", "high", "default");

    /** Context windows the console offers; 0 = the server default. */
    static final java.util.Set<Integer> CONTEXTS = java.util.Set.of(0, 4096, 8192, 16384, 32768);

    private static final Pattern CTX_SUFFIX = Pattern.compile("^(.+)-ctx([0-9]+)k$");
    static final Pattern KEEP_ALIVE = Pattern.compile("^(-1|[0-9]{1,4}[smh])$");

    @Value("${app.llm.ollama.reasoning-effort:none}")
    private String defaultReasoningEffort = "none";

    @Value("${app.local-models.keep-alive:30m}")
    private String defaultKeepAlive = "30m";

    @Value("${app.llm.ollama.num-ctx:32768}")
    private int wantedContext = 32768;

    /**
     * What {@code POST /warm} did: the tag it loaded, whether the server took it, and how long the
     * load took.
     */
    public record WarmResult(String tag, boolean loaded, long ms) {}

    private final HardwareDetector hardware;
    private final ModelCatalog catalog;
    private final ModelRecommender recommender;
    private final LocalModelOllamaGateway ollama;
    private final LocalModelPullService pulls;
    private final ObjectProvider<ToolCallCapabilityProbe> probe;
    private final PlatformHostedLlmProviderService hostedPool;
    private final PlatformHostedLlmProviderRepository hostedPoolRepository;
    private final boolean allowUncatalogedPull;
    private final String poolLabel;
    private final String poolApiKey;

    public LocalModelsService(
            HardwareDetector hardware,
            ModelCatalog catalog,
            ModelRecommender recommender,
            LocalModelOllamaGateway ollama,
            LocalModelPullService pulls,
            ObjectProvider<ToolCallCapabilityProbe> probe,
            PlatformHostedLlmProviderService hostedPool,
            PlatformHostedLlmProviderRepository hostedPoolRepository,
            @Value("${app.local-models.allow-uncataloged-pull:false}") boolean allowUncatalogedPull,
            @Value("${app.local-llm.bootstrap.label:Local model (Ollama)}") String poolLabel,
            @Value("${app.local-llm.bootstrap.api-key:ollama}") String poolApiKey) {
        this.hardware = hardware;
        this.catalog = catalog;
        this.recommender = recommender;
        this.ollama = ollama;
        this.pulls = pulls;
        this.probe = probe;
        this.hostedPool = hostedPool;
        this.hostedPoolRepository = hostedPoolRepository;
        this.allowUncatalogedPull = allowUncatalogedPull;
        this.poolLabel = poolLabel;
        this.poolApiKey = poolApiKey;
    }

    public Recommended recommendation() {
        HardwareProfile profile = hardware.detect();
        Recommendation recommendation = recommender.recommend(profile, catalog);
        LocalModelOllamaGateway.Tags tags = ollama.tags();
        Optional<PlatformHostedLlmProvider> serving = servingPoolRow();
        return new Recommended(
                profile,
                recommendation == null ? null : recommendation.recommended(),
                recommendation == null ? List.of() : recommendation.alternatives(),
                recommendation == null ? List.of() : recommendation.catalog(),
                recommender.budgetGb(profile),
                new OllamaState(tags.reachable(), ollama.baseUrl(), tags.installed()),
                serving.map(PlatformHostedLlmProvider::getModel).orElse(null),
                serving.map(this::servingBlocked).orElse(null));
    }

    /**
     * See {@link Recommended#servingBlocked()}: the same gates the chat path applies to the row.
     */
    private String servingBlocked(PlatformHostedLlmProvider row) {
        var settings = hostedPool.getPoolSettings();
        if (settings != null && !settings.enabled()) {
            return "pool-off";
        }
        if (!row.isEnabled()) {
            return "row-disabled";
        }
        var usable = hostedPool.resolveEnabledSelectionById(row.getId());
        return usable != null && usable.isPresent() ? null : "row-unusable";
    }

    /**
     * Measures how the installed model {@code tag} makes tool calls.
     *
     * @throws ResponseStatusException 400 bad tag, 404 not installed, 503 server or probe
     *     unavailable, 502 the probe could not grade the model
     */
    public ProbeResult probe(String tag) {
        String clean = requireValidTag(tag);
        ToolCallCapabilityProbe grader = probe.getIfAvailable();
        if (grader == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "The tool-calling probe is not available.");
        }
        LocalModelOllamaGateway.Tags tags = ollama.tags();
        if (!tags.reachable()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "The model server is not reachable.");
        }
        if (!tags.has(clean)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Model '" + clean + "' is not installed. Pull it first.");
        }
        long started = System.nanoTime();
        Optional<ToolCallCapabilityProbe.Result> result =
                grader.probe(ollama.baseUrl(), null, clean, null);
        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
        if (result.isEmpty() || result.get().grade() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "The model did not answer the probe. Is it still loading, or is the probe turned off (app.llm.tool-call-probe.enabled)?");
        }
        ToolCallCapabilityProbe.Result measured = result.get();
        return new ProbeResult(
                clean,
                toolCalling(measured.grade()),
                structuredOutput(grader, measured.serverKind(), clean),
                latencyMs,
                routerMode(measured.grade(), measured.serverKind()));
    }

    /**
     * Starts the download of {@code tag}.
     *
     * @return the job id
     * @throws ResponseStatusException 400 bad or non-catalog tag, 503 server unreachable, 429 too
     *     many downloads
     */
    public String startPull(String tag) {
        String clean = requireValidTag(tag);
        if (!allowUncatalogedPull && catalog.find(clean).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only models in the catalog can be installed from here.");
        }
        if (!ollama.tags().reachable()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "The model server is not reachable.");
        }
        try {
            return pulls.start(clean);
        } catch (LocalModelPullService.TooManyPullsException e) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        }
    }

    public LocalModelPullService.Status pullStatus(String jobId) {
        return pulls.status(jobId == null ? "" : jobId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Unknown download."));
    }

    /**
     * Runtime model switch ("use this model" on /troubleshoot): repoints the platform-pool row that
     * serves the configured local model server at {@code tag} - no restart, no reinstall. Only a
     * row whose base URL IS the operator-configured server is touched (an admin's unrelated pool
     * rows are never rewritten), and when no row points at this server one is created
     * bootstrap-shaped (enabled, zero-cost, top priority), which is how a personal install that
     * started before Ollama did - or with the bootstrap off - still gets its provider entry. The
     * write path is {@link PlatformHostedLlmProviderService}'s own, so the row is re-graded for
     * tool calls exactly as a platform-admin edit would be.
     *
     * @throws ResponseStatusException 400 bad tag, 404 not installed, 503 server unreachable
     */
    public UseResult useModel(String tag) {
        String clean = requireValidTag(tag);
        LocalModelOllamaGateway.Tags tags = ollama.tags();
        if (!tags.reachable()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "The model server is not reachable.");
        }
        if (!tags.has(clean)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Model '" + clean + "' is not installed. Pull it first.");
        }
        Optional<PlatformHostedLlmProvider> row = servingPoolRow();
        if (row.isPresent()) {
            PlatformHostedLlmProviderResponse updated =
                    hostedPool.updateModel(row.get().getId(), clean);
            warmInBackground(clean);
            return new UseResult(
                    clean, updated.id(), false, updated.enabled(), updated.toolCallGrade());
        }
        int priority =
                hostedPoolRepository.findAllByOrderByPriorityAsc().stream()
                                .mapToInt(PlatformHostedLlmProvider::getPriority)
                                .min()
                                .orElse(1)
                        - 1;
        PlatformHostedLlmProviderResponse created =
                hostedPool.createAllowingBlankKey(
                        new PlatformHostedLlmProviderUpsertRequest(
                                poolLabel,
                                HostedPoolEndpointProbe.normalizeBaseUrl(ollama.baseUrl()),
                                clean,
                                poolApiKey,
                                true,
                                priority,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                // A model the operator runs themselves is free - never priced,
                                // never
                                // debited (same flag LocalLlmPoolBootstrap sets).
                                Boolean.TRUE));
        warmInBackground(clean);
        return new UseResult(clean, created.id(), true, created.enabled(), created.toolCallGrade());
    }

    /** The serving row's settings; the configured defaults for whatever the row leaves unset. */
    public Settings settings() {
        PlatformHostedLlmProvider row = servingPoolRow().orElse(null);
        String model = row == null ? null : row.getModel();
        String effort =
                row != null && row.getReasoningEffort() != null
                        ? row.getReasoningEffort()
                        : defaultReasoningEffort;
        String keep =
                row != null && row.getKeepAlive() != null ? row.getKeepAlive() : defaultKeepAlive;
        Integer loaded = model == null ? null : ollama.loadedContextLength(model).orElse(null);
        return new Settings(
                model,
                effort == null || effort.isBlank() ? "default" : effort,
                keep,
                contextOf(model),
                loaded,
                wantedContext);
    }

    /**
     * The base tag of a console-made context variant ({@code qwen3.5:4b-ctx16k} -> {@code
     * qwen3.5:4b}); any other tag as it is.
     */
    static String baseTag(String model) {
        java.util.regex.Matcher m = model == null ? null : CTX_SUFFIX.matcher(model);
        return m != null && m.matches() ? m.group(1) : model;
    }

    /**
     * The context a console-made variant carries ({@code -ctx16k} -> 16384), or null for a plain
     * tag.
     */
    static Integer contextOf(String model) {
        java.util.regex.Matcher m = model == null ? null : CTX_SUFFIX.matcher(model);
        return m != null && m.matches() ? Integer.valueOf(m.group(2)) * 1024 : null;
    }

    /**
     * Points the serving row at {@code base} with a context window of {@code context} tokens: a
     * variant made with {@link LocalModelOllamaGateway#createWithContext} (no download), or the
     * base tag itself for 0. The row is re-measured by the pool service like any model change.
     */
    private void applyContext(PlatformHostedLlmProvider row, int context) {
        if (!CONTEXTS.contains(context)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Context must be 4096, 8192, 16384, 32768 or 0 for the server default.");
        }
        String base = baseTag(row.getModel());
        String target = context == 0 ? base : base + "-ctx" + (context / 1024) + "k";
        if (target.equals(row.getModel())) {
            return;
        }
        LocalModelOllamaGateway.Tags tags = ollama.tags();
        if (!tags.reachable()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "The model server is not reachable.");
        }
        if (!tags.has(target) && !ollama.createWithContext(base, target, context)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "The model server could not make that context size for " + base + ".");
        }
        hostedPool.updateModel(row.getId(), target);
    }

    /**
     * Saves the serving row's reasoning switch and keep-alive and reloads the model with the new
     * keep-alive in the background. Takes effect on the next message: the chat client reads the row
     * per turn.
     *
     * @throws ResponseStatusException 400 on a value outside the allowed set, 404 when no local
     *     model row exists
     */
    public Settings updateSettings(SettingsUpdate update) {
        PlatformHostedLlmProvider row =
                servingPoolRow()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "No local model is configured."));
        if (update.contextLength() != null) {
            applyContext(row, update.contextLength());
            // The model change saved the row; read it back so the switches below do not overwrite
            // it.
            row = servingPoolRow().orElse(row);
        }
        if (update.reasoningEffort() != null) {
            String effort = update.reasoningEffort().trim().toLowerCase(java.util.Locale.ROOT);
            if (!EFFORTS.contains(effort)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Reasoning must be none, low, medium, high or default.");
            }
            row.setReasoningEffort("default".equals(effort) ? "" : effort);
        }
        if (update.keepAlive() != null) {
            String keep = update.keepAlive().trim();
            if (!KEEP_ALIVE.matcher(keep).matches()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Keep loaded for must look like 30m, 2h or -1 (always).");
            }
            row.setKeepAlive(keep);
        }
        hostedPoolRepository.save(row);
        String model = row.getModel();
        String keep = row.getKeepAlive();
        BackgroundStartupTask.run(true, "local-model-warm", () -> ollama.warm(model, keep));
        return settings();
    }

    /**
     * Loads a model into the server's memory ahead of the first chat ({@link
     * LocalModelOllamaGateway#warm}). {@code tag} null or blank means the model the local pool row
     * serves. The console calls this when a chat surface opens, so a laptop that unloaded the model
     * during a pause has it back before the user finishes typing. Blocks for the load.
     *
     * @throws ResponseStatusException 400 bad tag, 404 no local model to warm
     */
    public WarmResult warm(String tag) {
        String clean =
                tag == null || tag.isBlank()
                        ? servingPoolRow().map(PlatformHostedLlmProvider::getModel).orElse(null)
                        : requireValidTag(tag);
        if (clean == null || clean.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No local model is configured.");
        }
        Optional<Long> ms =
                ollama.warm(
                        clean,
                        servingPoolRow().map(PlatformHostedLlmProvider::getKeepAlive).orElse(null));
        return new WarmResult(clean, ms.isPresent(), ms.orElse(0L));
    }

    /** {@link #warm} without waiting: the switch replies at once and the load runs behind it. */
    private void warmInBackground(String tag) {
        BackgroundStartupTask.run(true, "local-model-warm", () -> ollama.warm(tag));
    }

    /**
     * The platform-pool row serving the configured model server, if any: matched by normalized base
     * URL only (a row coincidentally sharing the bootstrap label but pointing at a different host
     * is not this install's row). Lowest-priority-number first, so a deliberate multi-row fallback
     * chain is reported - and edited - at the rung that actually serves.
     */
    private Optional<PlatformHostedLlmProvider> servingPoolRow() {
        String base = HostedPoolEndpointProbe.normalizeBaseUrl(ollama.baseUrl());
        if (base == null || base.isEmpty() || hostedPoolRepository == null) {
            return Optional.empty();
        }
        return hostedPoolRepository.findAllByOrderByPriorityAsc().stream()
                .filter(
                        r ->
                                r.getBaseUrl() != null
                                        && base.equalsIgnoreCase(
                                                HostedPoolEndpointProbe.normalizeBaseUrl(
                                                        r.getBaseUrl())))
                .findFirst();
    }

    static String requireValidTag(String tag) {
        String clean = tag == null ? "" : tag.trim();
        if (clean.isEmpty() || clean.length() > MAX_TAG_LENGTH || !TAG.matcher(clean).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "That is not a valid model tag.");
        }
        return clean;
    }

    static String toolCalling(ToolCallGrade grade) {
        return switch (grade) {
            case STRUCTURED -> "native";
            case TEXT -> "constrained";
            case NONE -> "none";
        };
    }

    /**
     * The router mode a hosted-pool row with this grade and server would get ({@code
     * PlatformHostedLlmProvider#effectiveToolRouterMode} with no admin override): the detected mode
     * for a TEXT or NONE grade, "off" otherwise.
     */
    static String routerMode(ToolCallGrade grade, ServerKind kind) {
        ToolRouterMode mode = ToolRouterMode.forServer(kind);
        boolean weak = grade == ToolCallGrade.TEXT || grade == ToolCallGrade.NONE;
        return weak && mode != null ? mode.name() : "off";
    }

    private boolean structuredOutput(ToolCallCapabilityProbe grader, ServerKind kind, String tag) {
        if (kind == null) {
            return false;
        }
        return switch (kind) {
            // Ollama is only asked when the grade did not already imply it, so ask here.
            case OLLAMA -> grader.honoursJsonSchema(ollama.baseUrl(), null, tag, null);
            case LLAMA_CPP, VLLM -> true;
            case OLLAMA_WITHOUT_FORMAT, OTHER -> false;
        };
    }
}
