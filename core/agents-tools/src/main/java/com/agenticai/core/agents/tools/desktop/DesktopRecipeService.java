package com.agenticai.core.agents.tools.desktop;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.CreateRecipeRequest;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.RecipeDetail;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.RecipeFromRunRequest;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.RecipeSummary;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.RunRecipeRequest;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.RunRecipeResponse;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.UpdateRecipeRequest;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeRecorder.JournalCall;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeReplayer.Outcome;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.RecipeStep;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.secrets.SecretVaultService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Desktop recipes (app.assistant.desktop.recipes.enabled, default OFF): records a finished {@code
 * local_desktop_*} run as a compact, placeholder-only JSON recipe and serves it to {@code
 * run_desktop_recipe} for deterministic replay through the desktop-agent sidecar on the person's
 * laptop. The personal tier's equivalent of {@code BrowserRecipeService}.
 *
 * <p>The journal it records from is {@code tool_call_log} - the row the platform already writes
 * for every tool call of a run - so no new write path exists on the live run. Recording and
 * replay logic is in the pure {@link DesktopRecipeRecorder} / {@link DesktopRecipeReplayer}; this
 * class is the persistence, access control and flag gate around them.
 *
 * <p>Access: a recipe with an {@code agentId} is managed by an EDITOR of that agent and visible
 * to its RUNNERs; a tenant-wide recipe (no agent) is managed by a manager of agents ({@code
 * PERM_MANAGE_AGENTS}) and visible to everyone. With the flag off every management call answers
 * 404 and the runtime lookups return nothing.
 *
 * <p>{@link #replayForAgent} deliberately does NOT record the run's outcome - the caller does,
 * via {@link #recordRun}, exactly like {@code run_browser_recipe} does for browser recipes. The
 * REST path ({@link #run}) is the exception: it records itself because there is no tool wrapper
 * around it.
 */
@Service
public class DesktopRecipeService {

    private static final Logger log = LoggerFactory.getLogger(DesktopRecipeService.class);

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private static final int MAX_JOURNAL_ROWS = 2_000;

    private final DesktopRecipeRepository recipes;
    private final ObjectProvider<AgentService> agentService;
    private final ObjectProvider<ToolCallLogRepository> toolCallLogs;
    private final ObjectProvider<SecretVaultService> vault;
    private final boolean enabled;
    private final int maxPerTenant;
    private final int stepTimeoutMs;
    private final int maxReplaySeconds;
    private final int monitorStepDelayMs;
    private final String secretNamePrefix;
    private final ObjectProvider<DesktopToolSource> desktopTools;
    private final Clock clock;

    public DesktopRecipeService(
            DesktopRecipeRepository recipes,
            ObjectProvider<AgentService> agentService,
            ObjectProvider<ToolCallLogRepository> toolCallLogs,
            ObjectProvider<SecretVaultService> vault,
            ObjectProvider<DesktopToolSource> desktopTools,
            @Value("${app.assistant.desktop.recipes.enabled:false}") boolean enabled,
            @Value("${app.assistant.desktop.recipes.max-per-tenant:50}") int maxPerTenant,
            @Value("${app.assistant.desktop.recipes.step-timeout-ms:15000}") int stepTimeoutMs,
            @Value("${app.assistant.desktop.recipes.max-replay-seconds:120}") int maxReplaySeconds,
            @Value("${app.assistant.desktop.recipes.monitor-step-delay-ms:800}") int monitorStepDelayMs,
            @Value("${app.assistant.desktop.recipes.secret-name-prefix:DESKTOP_}") String secretNamePrefix) {
        this.recipes = recipes;
        this.agentService = agentService;
        this.toolCallLogs = toolCallLogs;
        this.vault = vault;
        this.desktopTools = desktopTools;
        this.enabled = enabled;
        this.maxPerTenant = Math.max(1, maxPerTenant);
        this.stepTimeoutMs =
                Math.max(
                        DesktopRecipeValidator.MIN_TIMEOUT_MS,
                        Math.min(stepTimeoutMs, DesktopRecipeValidator.MAX_TIMEOUT_MS));
        this.maxReplaySeconds = Math.max(5, maxReplaySeconds);
        this.monitorStepDelayMs = Math.max(0, monitorStepDelayMs);
        this.secretNamePrefix =
                secretNamePrefix == null
                        ? ""
                        : secretNamePrefix.strip().toUpperCase(Locale.ROOT);
        this.clock = Clock.systemUTC();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Replay settings for one run: the configured per-step, total and monitor budgets plus the
     * caller's confirmation state.
     */
    DesktopRecipeReplayer.Options replayOptions(
            boolean allowIrreversible, int fromStep, boolean monitor) {
        return new DesktopRecipeReplayer.Options(
                allowIrreversible,
                stepTimeoutMs,
                maxReplaySeconds * 1000L,
                Math.max(1, fromStep),
                monitor ? monitorStepDelayMs : 0);
    }

    // ---- management (REST) -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<RecipeSummary> list(UUID agentId) {
        requireEnabled();
        List<DesktopRecipe> rows;
        if (agentId != null) {
            requireAgent(agentId, PermissionLevel.RUNNER);
            rows = recipes.findByAgentIdOrAgentIdIsNullOrderByNameAsc(agentId);
        } else if (DesktopRecipeSupport.isManager()) {
            rows = recipes.findAllByOrderByNameAsc();
        } else {
            rows =
                    recipes.findAllByOrderByNameAsc().stream()
                            .filter(r -> r.getAgentId() == null)
                            .toList();
        }
        return rows.stream().map(DesktopRecipeSupport::summary).toList();
    }

    @Transactional(readOnly = true)
    public RecipeDetail get(UUID id) {
        requireEnabled();
        DesktopRecipe recipe = requireVisible(id);
        return new RecipeDetail(DesktopRecipeSupport.summary(recipe), parse(recipe), List.of());
    }

    @Transactional
    public RecipeDetail create(CreateRecipeRequest request) {
        requireEnabled();
        if (request == null) {
            throw new IllegalArgumentException("a request body is required");
        }
        requireManage(request.agentId());
        DesktopRecipeSpec spec = DesktopRecipeValidator.validate(request.spec());
        DesktopRecipe saved =
                store(
                        request.agentId(),
                        request.name(),
                        request.description(),
                        request.defaultMode(),
                        spec);
        return new RecipeDetail(DesktopRecipeSupport.summary(saved), spec, List.of());
    }

    /**
     * Records the run {@code (agentId, sessionId)} as a recipe. Reads the run's tool calls from
     * {@code tool_call_log} (only ones this agent made in that session), builds the recipe with
     * {@link DesktopRecipeRecorder} and stores it.
     */
    @Transactional
    public RecipeDetail saveFromRun(RecipeFromRunRequest request) {
        requireEnabled();
        if (request == null
                || request.agentId() == null
                || request.sessionId() == null
                || request.sessionId().isBlank()) {
            throw new IllegalArgumentException("agentId and sessionId are required");
        }
        requireAgent(request.agentId(), PermissionLevel.EDITOR);
        ToolCallLogRepository journal = toolCallLogs.getIfAvailable();
        if (journal == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "The run journal is not available.");
        }
        List<JournalCall> calls = new ArrayList<>();
        for (ToolCallLog row :
                journal.findByAgentIdAndSessionIdOrderByStartedAtAsc(
                        request.agentId(), request.sessionId().strip())) {
            if (calls.size() >= MAX_JOURNAL_ROWS) {
                break;
            }
            calls.add(
                    new JournalCall(
                            row.getToolName(),
                            row.getToolInput(),
                            row.getToolOutput(),
                            row.getErrorMessage()));
        }
        if (calls.isEmpty()) {
            throw new IllegalArgumentException("no tool calls were found for that run");
        }
        DesktopRecipeRecorder.Recorded recorded =
                DesktopRecipeRecorder.record(
                        calls,
                        request.parameters(),
                        Boolean.TRUE.equals(request.allowLiteralText()));
        DesktopRecipe saved =
                store(
                        request.agentId(),
                        request.name(),
                        request.description(),
                        request.defaultMode(),
                        recorded.spec());
        return new RecipeDetail(
                DesktopRecipeSupport.summary(saved), recorded.spec(), recorded.warnings());
    }

    @Transactional
    public RecipeSummary update(UUID id, UpdateRecipeRequest request) {
        requireEnabled();
        DesktopRecipe recipe = requireVisible(id);
        requireManage(recipe.getAgentId());
        if (request == null) {
            throw new IllegalArgumentException("a request body is required");
        }
        String name = request.name() == null ? null : DesktopRecipeSupport.cleanName(request.name());
        if (name != null
                && !name.equalsIgnoreCase(recipe.getName())
                && nameTaken(recipe.getAgentId(), name)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A recipe named '" + name + "' already exists.");
        }
        recipe.edit(
                name,
                request.description() == null
                        ? null
                        : DesktopRecipeSupport.cleanDescription(request.description()),
                request.defaultMode() == null
                        ? null
                        : DesktopRecipeSupport.cleanMode(request.defaultMode()),
                clock.instant());
        return DesktopRecipeSupport.summary(recipes.save(recipe));
    }

    @Transactional
    public void delete(UUID id) {
        requireEnabled();
        DesktopRecipe recipe = requireVisible(id);
        requireManage(recipe.getAgentId());
        recipes.delete(recipe);
    }

    /**
     * The REST replay path ({@code POST /api/desktop/recipes/{id}/run}). Not {@code
     * @Transactional} on purpose: the replay makes blocking calls to the laptop's sidecar and
     * sleeps between steps, so no transaction may be held across it - each repository call and
     * {@link #recordRun} commits on its own, and a vault audit write inside the replay gets its
     * own transaction too.
     */
    public RunRecipeResponse run(UUID id, RunRecipeRequest request) {
        requireEnabled();
        DesktopRecipe recipe = requireVisible(id);
        DesktopRecipeSpec spec = parse(recipe);
        boolean monitor =
                resolveMonitor(
                        recipe.getDefaultMode(), request == null ? null : request.mode());
        Map<String, String> params = request == null ? null : request.params();
        boolean confirm = request != null && Boolean.TRUE.equals(request.confirmIrreversible());
        int fromStep = request == null || request.fromStep() == null ? 1 : request.fromStep();
        Outcome outcome = doReplay(spec, params, confirm, fromStep, monitor);
        recordRun(recipe.getId(), outcome.success() ? null : outcome.failure());
        return new RunRecipeResponse(
                recipe.getId(),
                recipe.getName(),
                outcome.success(),
                outcome.stepsRun(),
                outcome.stepsTotal(),
                outcome.failure(),
                outcome.steps());
    }

    // ---- runtime (tool) -------------------------------------------------------------------------

    /**
     * Recipes {@code agentId} may run (its own plus tenant-wide), empty when the feature is off.
     */
    @Transactional(readOnly = true)
    public List<DesktopRecipe> listRunnable(UUID agentId) {
        if (!enabled || agentId == null) {
            return List.of();
        }
        return recipes.findByAgentIdOrAgentIdIsNullOrderByNameAsc(agentId);
    }

    /**
     * Looks a runnable recipe up by id or (case-insensitive) name. {@code agentId} null searches
     * every recipe of the tenant; otherwise the agent's own plus tenant-wide recipes.
     *
     * @throws IllegalArgumentException when a name matches more than one recipe (the caller
     *     should retry with the id)
     */
    @Transactional(readOnly = true)
    public Optional<DesktopRecipe> findRunnable(UUID agentId, String idOrName) {
        if (!enabled || idOrName == null || idOrName.isBlank()) {
            return Optional.empty();
        }
        String ref = idOrName.strip();
        List<DesktopRecipe> candidates =
                agentId == null
                        ? recipes.findAllByOrderByNameAsc()
                        : recipes.findByAgentIdOrAgentIdIsNullOrderByNameAsc(agentId);
        try {
            UUID id = UUID.fromString(ref);
            return candidates.stream().filter(r -> r.getId().equals(id)).findFirst();
        } catch (IllegalArgumentException notAnId) {
            List<DesktopRecipe> named =
                    candidates.stream().filter(r -> r.getName().equalsIgnoreCase(ref)).toList();
            if (named.size() > 1) {
                throw new IllegalArgumentException(
                        "more than one desktop recipe is named '"
                                + ref
                                + "' - use its id instead");
            }
            return named.stream().findFirst();
        }
    }

    /**
     * The tool-facing replay ({@code run_desktop_recipe}): looks the recipe up by id or unique
     * name, re-validates the stored spec and replays it through the configured desktop agent.
     * Failures are reported inside the {@link Outcome}, never thrown - the model gets the same
     * short, scrubbed reason the browser replay produces.
     *
     * <p>This method does NOT record the outcome; call {@link #recordRun} with the recipe's id
     * afterwards, exactly like {@code run_browser_recipe} does.
     *
     * @param agentId the agent the run belongs to (its own plus tenant-wide recipes); null = all
     *     agents
     * @param monitor true pauses between steps (MONITOR mode) regardless of the recipe default
     */
    public Outcome replayForAgent(
            UUID agentId,
            String nameOrId,
            Map<String, String> params,
            boolean allowIrreversible,
            int fromStep,
            boolean monitor) {
        if (!enabled) {
            return new Outcome(false, 0, 0, "desktop recipes are not enabled", List.of());
        }
        Optional<DesktopRecipe> found = findRunnable(agentId, nameOrId);
        if (found.isEmpty()) {
            return new Outcome(
                    false,
                    0,
                    0,
                    "no desktop recipe '"
                            + (nameOrId == null ? "" : nameOrId.strip())
                            + "' is available to this agent",
                    List.of());
        }
        DesktopRecipeSpec spec;
        try {
            spec = parse(found.get());
        } catch (IllegalArgumentException e) {
            return new Outcome(
                    false,
                    0,
                    found.get().getStepCount(),
                    "recipe '" + found.get().getName() + "' cannot be run: " + e.getMessage(),
                    List.of());
        }
        return doReplay(spec, params, allowIrreversible, fromStep, monitor);
    }

    /**
     * Parses and re-validates the stored spec: a row edited out of band can never replay
     * something the validator refuses.
     */
    DesktopRecipeSpec parse(DesktopRecipe recipe) {
        try {
            return DesktopRecipeValidator.validate(
                    MAPPER.readValue(recipe.getRecipeJson(), DesktopRecipeSpec.class));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("the stored recipe could not be read");
        }
    }

    @Transactional
    public void recordRun(UUID recipeId, String failure) {
        if (!enabled || recipeId == null) {
            return;
        }
        try {
            recipes.findById(recipeId)
                    .ifPresent(
                            r -> {
                                r.recordRun(failure, clock.instant());
                                recipes.save(r);
                            });
        } catch (RuntimeException e) {
            log.warn(
                    "Could not record the result of desktop recipe {}: {}",
                    recipeId,
                    e.getClass().getSimpleName());
        }
    }

    // ---- replay internals ---------------------------------------------------------------------

    /**
     * The shared replay path for the tool ({@link #replayForAgent}) and REST ({@link #run}).
     * Builds the sidecar executor and the vault-backed secret resolver and hands both to the
     * pure {@link DesktopRecipeReplayer}. Not transactional: it performs blocking calls.
     */
    private Outcome doReplay(
            DesktopRecipeSpec spec,
            Map<String, String> params,
            boolean allowIrreversible,
            int fromStep,
            boolean monitor) {
        DesktopRecipeReplayer.Executor executor = executor();
        if (executor == null) {
            return new Outcome(
                    false,
                    0,
                    spec.steps().size(),
                    "the desktop agent is not available - it needs a configured "
                            + "app.assistant.desktop.agent-url on a single-user install, the same "
                            + "opt-in the local_desktop_* tools require",
                    List.of());
        }
        return new DesktopRecipeReplayer()
                .replay(
                        spec,
                        params,
                        executor,
                        secretValues(),
                        replayOptions(allowIrreversible, fromStep, monitor));
    }

    /**
     * The sidecar port the replay posts steps through: the SAME client the {@code local_desktop_*}
     * tools use, so "the desktop agent is available" has one source of truth (configured URL AND
     * a single-user install - {@link DesktopToolSource#isEnabled()}). Null when the tools are
     * off. Package-private so tests can substitute a fake without a loopback server.
     */
    DesktopRecipeReplayer.Executor executor() {
        DesktopToolSource source = desktopTools.getIfAvailable();
        if (source == null || !source.isEnabled()) {
            return null;
        }
        return source.client()::post;
    }

    /**
     * Vault-backed secret resolution for {@code {{secret.NAME}}}: audited reads as the calling
     * user, restricted to the secrets the deployment named for desktop use (the {@code
     * app.assistant.desktop.recipes.secret-name-prefix}, default {@code DESKTOP_}) so a recipe
     * cannot aim the keyboard at an unrelated credential.
     */
    DesktopRecipeReplayer.SecretValues secretValues() {
        SecretVaultService v = vault.getIfAvailable();
        if (v == null) {
            return DesktopRecipeReplayer.SecretValues.NONE;
        }
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return name -> {
            if (!secretNamePrefix.isEmpty()
                    && !name.toUpperCase(Locale.ROOT).startsWith(secretNamePrefix)) {
                return Optional.empty();
            }
            try {
                return v.resolveForRun(name.toUpperCase(Locale.ROOT), subject);
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        };
    }

    private static boolean resolveMonitor(String defaultMode, String requested) {
        String mode = requested == null || requested.isBlank() ? defaultMode : requested;
        return DesktopRecipe.MODE_MONITOR.equalsIgnoreCase(mode);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private DesktopRecipe store(
            UUID agentId,
            String rawName,
            String rawDescription,
            String rawMode,
            DesktopRecipeSpec spec) {
        String name = DesktopRecipeSupport.cleanName(rawName);
        String mode = DesktopRecipeSupport.cleanMode(rawMode);
        requireDesktopSecretNames(spec);
        if (nameTaken(agentId, name)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A recipe named '" + name + "' already exists.");
        }
        if (recipes.count() >= maxPerTenant) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This workspace already has the maximum of "
                            + maxPerTenant
                            + " desktop recipes - delete one first.");
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("the recipe could not be serialized");
        }
        return recipes.save(
                new DesktopRecipe(
                        agentId,
                        name,
                        DesktopRecipeSupport.cleanDescription(rawDescription),
                        spec.allowedApps(),
                        mode,
                        json,
                        spec.steps().size(),
                        DesktopRecipeSupport.subject(),
                        clock.instant()));
    }

    /**
     * Vault secrets a recipe types must be ones the deployment named for desktop use (the {@code
     * secret-name-prefix}, default DESKTOP_), so a recipe author cannot aim the keyboard at an
     * unrelated secret. The same rule is enforced again when the replay resolves them.
     */
    private void requireDesktopSecretNames(DesktopRecipeSpec spec) {
        if (secretNamePrefix.isEmpty()) {
            return;
        }
        int n = 0;
        for (RecipeStep step : spec.steps()) {
            n++;
            for (DesktopRecipePlaceholders.Token token :
                    DesktopRecipePlaceholders.tokens(step.text())) {
                if (token.kind() != DesktopRecipePlaceholders.Kind.PARAM
                        && !token.name().startsWith(secretNamePrefix)) {
                    throw new IllegalArgumentException(
                            "step "
                                    + n
                                    + " uses the vault secret "
                                    + token.name()
                                    + ", but desktop recipes may only use secrets whose name starts with "
                                    + secretNamePrefix
                                    + " - rename it in the vault (or point the placeholder at one that is)");
                }
            }
        }
    }

    private boolean nameTaken(UUID agentId, String name) {
        return agentId == null
                ? recipes.existsByAgentIdIsNullAndNameIgnoreCase(name)
                : recipes.existsByAgentIdAndNameIgnoreCase(agentId, name);
    }

    private DesktopRecipe requireVisible(UUID id) {
        DesktopRecipe recipe =
                recipes.findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Desktop recipe not found."));
        if (recipe.getAgentId() != null) {
            requireAgent(recipe.getAgentId(), PermissionLevel.RUNNER);
        }
        return recipe;
    }

    private void requireManage(UUID agentId) {
        if (agentId != null) {
            requireAgent(agentId, PermissionLevel.EDITOR);
        } else if (!DesktopRecipeSupport.isManager()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Managing tenant-wide desktop recipes needs the manage-agents permission.");
        }
    }

    /**
     * Agent access check; throws ResourceAccessDeniedException (404) when the caller cannot see
     * the agent.
     */
    private void requireAgent(UUID agentId, PermissionLevel level) {
        AgentService agents = agentService.getIfAvailable();
        if (agents == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Agents are not available.");
        }
        agents.requireAccess(agentId, level);
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Desktop recipes are not enabled.");
        }
    }
}
