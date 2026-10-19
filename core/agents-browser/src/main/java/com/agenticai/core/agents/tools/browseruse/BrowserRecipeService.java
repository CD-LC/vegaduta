package com.agenticai.core.agents.tools.browseruse;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.CreateRecipeRequest;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.RecipeDetail;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.RecipeFromRunRequest;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.RecipeSummary;
import com.agenticai.core.agents.tools.browseruse.BrowserAutomationDtos.UpdateRecipeRequest;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeRecorder.JournalCall;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.ParamSpec;
import com.agenticai.core.agents.tools.browseruse.BrowserRecipeSpec.RecipeStep;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Browser recipes (app.browser.recipes.enabled, default OFF): records a finished browser run as a
 * compact, placeholder-only JSON recipe and serves it to {@link RunBrowserRecipeToolCallback} for
 * deterministic replay.
 *
 * <p>The journal it records from is {@code tool_call_log} - the row the platform already writes for
 * every tool call of a run (agent, session, tool, input, output, error) - so no new write path
 * exists on the live run. Recording and replay logic is in the pure {@link BrowserRecipeRecorder} /
 * {@link BrowserRecipeReplayer}; this class is the persistence, access control and flag gate around
 * them.
 *
 * <p>Access: a recipe with an {@code agentId} is managed by an EDITOR of that agent and visible to
 * its RUNNERs; a tenant-wide recipe (no agent) is managed by a manager of agents ({@code
 * PERM_MANAGE_AGENTS}). With the flag off every management call answers 404 and the runtime lookups
 * return nothing.
 */
@Service
public class BrowserRecipeService {

    private static final Logger log = LoggerFactory.getLogger(BrowserRecipeService.class);

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    private static final String PERM_MANAGE_AGENTS = "PERM_MANAGE_AGENTS";
    private static final String ROLE_PLATFORM_ADMIN = "ROLE_platform-admin";
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_DESCRIPTION_LENGTH = 2_000;
    private static final int MAX_JOURNAL_ROWS = 2_000;

    private final BrowserRecipeRepository recipes;
    private final ObjectProvider<AgentService> agentService;
    private final ObjectProvider<ToolCallLogRepository> toolCallLogs;
    private final boolean enabled;
    private final int maxPerTenant;
    private final int stepTimeoutMs;
    private final int maxReplaySeconds;
    private final String secretNamePrefix;
    private final Clock clock;

    @Autowired
    public BrowserRecipeService(
            BrowserRecipeRepository recipes,
            ObjectProvider<AgentService> agentService,
            ObjectProvider<ToolCallLogRepository> toolCallLogs,
            @Value("${app.browser.recipes.enabled:false}") boolean enabled,
            @Value("${app.browser.recipes.max-per-tenant:50}") int maxPerTenant,
            @Value("${app.browser.recipes.step-timeout-ms:15000}") int stepTimeoutMs,
            @Value("${app.browser.recipes.max-replay-seconds:120}") int maxReplaySeconds,
            @Value("${app.browser.secret-name-prefix:BROWSER_}") String secretNamePrefix) {
        this(
                recipes,
                agentService,
                toolCallLogs,
                enabled,
                maxPerTenant,
                stepTimeoutMs,
                maxReplaySeconds,
                secretNamePrefix,
                Clock.systemUTC());
    }

    /** Test seam: fixed clock. */
    BrowserRecipeService(
            BrowserRecipeRepository recipes,
            ObjectProvider<AgentService> agentService,
            ObjectProvider<ToolCallLogRepository> toolCallLogs,
            boolean enabled,
            int maxPerTenant,
            int stepTimeoutMs,
            int maxReplaySeconds,
            String secretNamePrefix,
            Clock clock) {
        this.recipes = recipes;
        this.agentService = agentService;
        this.toolCallLogs = toolCallLogs;
        this.enabled = enabled;
        this.maxPerTenant = Math.max(1, maxPerTenant);
        this.stepTimeoutMs =
                Math.max(
                        BrowserRecipeValidator.MIN_TIMEOUT_MS,
                        Math.min(stepTimeoutMs, BrowserRecipeValidator.MAX_TIMEOUT_MS));
        this.maxReplaySeconds = Math.max(5, maxReplaySeconds);
        this.secretNamePrefix =
                secretNamePrefix == null
                        ? ""
                        : secretNamePrefix.strip().toUpperCase(java.util.Locale.ROOT);
        this.clock = clock;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Replay settings for one run: the configured per-step and total budgets plus the caller's
     * confirmation state.
     */
    BrowserRecipeReplayer.Options replayOptions(boolean allowIrreversible, int fromStep) {
        return new BrowserRecipeReplayer.Options(
                allowIrreversible, stepTimeoutMs, maxReplaySeconds * 1000L, Math.max(1, fromStep));
    }

    // ---- management (REST) -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<RecipeSummary> list(UUID agentId) {
        requireEnabled();
        List<BrowserRecipe> rows;
        if (agentId != null) {
            requireAgent(agentId, PermissionLevel.RUNNER);
            rows = recipes.findByAgentIdOrAgentIdIsNullOrderByNameAsc(agentId);
        } else if (isManager()) {
            rows = recipes.findAllByOrderByNameAsc();
        } else {
            rows =
                    recipes.findAllByOrderByNameAsc().stream()
                            .filter(r -> r.getAgentId() == null)
                            .toList();
        }
        return rows.stream().map(BrowserRecipeService::summary).toList();
    }

    @Transactional(readOnly = true)
    public RecipeDetail get(UUID id) {
        requireEnabled();
        BrowserRecipe recipe = requireVisible(id);
        return new RecipeDetail(summary(recipe), parse(recipe), List.of());
    }

    @Transactional
    public RecipeDetail create(CreateRecipeRequest request) {
        requireEnabled();
        if (request == null) {
            throw new IllegalArgumentException("a request body is required");
        }
        requireManage(request.agentId());
        BrowserRecipeSpec spec = BrowserRecipeValidator.validate(request.spec());
        BrowserRecipe saved = store(request.agentId(), request.name(), request.description(), spec);
        return new RecipeDetail(summary(saved), spec, List.of());
    }

    /**
     * Records the run {@code (agentId, sessionId)} as a recipe. Reads the run's tool calls from
     * {@code tool_call_log} (only ones this agent made in that session), builds the recipe with
     * {@link BrowserRecipeRecorder} and stores it.
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
        BrowserRecipeRecorder.Recorded recorded =
                BrowserRecipeRecorder.record(
                        calls,
                        request.parameters(),
                        Boolean.TRUE.equals(request.allowLiteralText()));
        BrowserRecipe saved =
                store(request.agentId(), request.name(), request.description(), recorded.spec());
        return new RecipeDetail(summary(saved), recorded.spec(), recorded.warnings());
    }

    @Transactional
    public RecipeSummary update(UUID id, UpdateRecipeRequest request) {
        requireEnabled();
        BrowserRecipe recipe = requireVisible(id);
        requireManage(recipe.getAgentId());
        if (request == null) {
            throw new IllegalArgumentException("a request body is required");
        }
        String name = request.name() == null ? null : cleanName(request.name());
        if (name != null
                && !name.equalsIgnoreCase(recipe.getName())
                && nameTaken(recipe.getAgentId(), name)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A recipe named '" + name + "' already exists.");
        }
        recipe.edit(
                name,
                request.description() == null ? null : cleanDescription(request.description()),
                clock.instant());
        return summary(recipes.save(recipe));
    }

    @Transactional
    public void delete(UUID id) {
        requireEnabled();
        BrowserRecipe recipe = requireVisible(id);
        requireManage(recipe.getAgentId());
        recipes.delete(recipe);
    }

    // ---- runtime (tool) -------------------------------------------------------------------------

    /**
     * Recipes {@code agentId} may run (its own plus tenant-wide), empty when the feature is off.
     */
    @Transactional(readOnly = true)
    public List<BrowserRecipe> listRunnable(UUID agentId) {
        if (!enabled || agentId == null) {
            return List.of();
        }
        return recipes.findByAgentIdOrAgentIdIsNullOrderByNameAsc(agentId);
    }

    /** Looks a runnable recipe up by id or (case-insensitive) name. */
    @Transactional(readOnly = true)
    public Optional<BrowserRecipe> findRunnable(UUID agentId, String idOrName) {
        if (!enabled || agentId == null || idOrName == null || idOrName.isBlank()) {
            return Optional.empty();
        }
        String ref = idOrName.strip();
        List<BrowserRecipe> candidates =
                recipes.findByAgentIdOrAgentIdIsNullOrderByNameAsc(agentId);
        try {
            UUID id = UUID.fromString(ref);
            return candidates.stream().filter(r -> r.getId().equals(id)).findFirst();
        } catch (IllegalArgumentException notAnId) {
            // fall through to the name lookup; an agent-scoped recipe shadows a tenant-wide one of
            // the same name
            return candidates.stream()
                    .filter(r -> r.getName().equalsIgnoreCase(ref))
                    .min((a, b) -> Boolean.compare(a.getAgentId() == null, b.getAgentId() == null));
        }
    }

    /**
     * Parses and re-validates the stored spec: a row edited out of band can never replay something
     * the validator refuses.
     */
    BrowserRecipeSpec parse(BrowserRecipe recipe) {
        try {
            return BrowserRecipeValidator.validate(
                    MAPPER.readValue(recipe.getRecipeJson(), BrowserRecipeSpec.class));
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
                    "Could not record the result of browser recipe {}: {}",
                    recipeId,
                    e.getClass().getSimpleName());
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private BrowserRecipe store(
            UUID agentId, String rawName, String rawDescription, BrowserRecipeSpec spec) {
        String name = cleanName(rawName);
        requireBrowserSecretNames(spec);
        if (nameTaken(agentId, name)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A recipe named '" + name + "' already exists.");
        }
        if (recipes.count() >= maxPerTenant) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This workspace already has the maximum of "
                            + maxPerTenant
                            + " browser recipes - delete one first.");
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("the recipe could not be serialized");
        }
        return recipes.save(
                new BrowserRecipe(
                        agentId,
                        name,
                        cleanDescription(rawDescription),
                        spec.allowedDomains(),
                        json,
                        spec.steps().size(),
                        subject(),
                        clock.instant()));
    }

    /**
     * Vault secrets a recipe types (secret/TOTP placeholders) must be ones the owner named for
     * browser use (prefix app.browser.secret-name-prefix, default BROWSER_), so a recipe author
     * cannot aim a page at an unrelated secret. The same rule is enforced again when the replay
     * resolves them.
     */
    private void requireBrowserSecretNames(BrowserRecipeSpec spec) {
        if (secretNamePrefix.isEmpty()) {
            return;
        }
        int n = 0;
        for (RecipeStep step : spec.steps()) {
            n++;
            for (BrowserRecipePlaceholders.Token token :
                    BrowserRecipePlaceholders.tokens(step.text())) {
                if (token.kind() != BrowserRecipePlaceholders.Kind.PARAM
                        && !token.name().startsWith(secretNamePrefix)) {
                    throw new IllegalArgumentException(
                            "step "
                                    + n
                                    + " uses the vault secret "
                                    + token.name()
                                    + ", but browser recipes may only use secrets whose name starts with "
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

    private BrowserRecipe requireVisible(UUID id) {
        BrowserRecipe recipe =
                recipes.findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Browser recipe not found."));
        if (recipe.getAgentId() != null) {
            requireAgent(recipe.getAgentId(), PermissionLevel.RUNNER);
        }
        return recipe;
    }

    private void requireManage(UUID agentId) {
        if (agentId != null) {
            requireAgent(agentId, PermissionLevel.EDITOR);
        } else if (!isManager()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Managing tenant-wide browser recipes needs the manage-agents permission.");
        }
    }

    /**
     * Agent access check; throws ResourceAccessDeniedException (404) when the caller cannot see the
     * agent.
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
                    HttpStatus.NOT_FOUND, "Browser recipes are not enabled.");
        }
    }

    private static String subject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    private static boolean isManager() {
        return TenantContext.current()
                .map(ctx -> ctx.hasRole(PERM_MANAGE_AGENTS) || ctx.hasRole(ROLE_PLATFORM_ADMIN))
                .orElse(false);
    }

    private static String cleanName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("a recipe name is required");
        }
        String name = raw.strip();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "a recipe name is at most " + MAX_NAME_LENGTH + " characters");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IllegalArgumentException(
                        "a recipe name must not contain control characters");
            }
        }
        return name;
    }

    private static String cleanDescription(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String description = raw.strip();
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "a recipe description is at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return description;
    }

    static RecipeSummary summary(BrowserRecipe r) {
        List<String> paramNames = new ArrayList<>();
        try {
            for (ParamSpec p :
                    MAPPER.readValue(r.getRecipeJson(), BrowserRecipeSpec.class).params()) {
                paramNames.add(p.name());
            }
        } catch (Exception e) {
            // A summary must still render for a row that cannot be parsed; the replay path
            // re-validates and refuses it.
        }
        return new RecipeSummary(
                r.getId(),
                r.getAgentId(),
                r.getName(),
                r.getDescription(),
                r.getAllowedDomainList(),
                paramNames,
                r.getStepCount(),
                r.getRunCount(),
                r.getFailCount(),
                r.getLastRunAt(),
                r.getLastFailure(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
