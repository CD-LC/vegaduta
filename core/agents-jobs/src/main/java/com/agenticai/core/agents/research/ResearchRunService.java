package com.agenticai.core.agents.research;

import com.agenticai.core.agents.capability.AgentCapabilityState;
import com.agenticai.core.agents.capability.AgentCapabilityStateId;
import com.agenticai.core.agents.capability.AgentCapabilityStateRepository;
import com.agenticai.core.agents.capability.DeepResearchCapability;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentCreateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.job.AgentJobNotFoundException;
import com.agenticai.core.agents.job.AgentJobService;
import com.agenticai.core.agents.job.dto.AgentJobResponse;
import com.agenticai.core.agents.job.dto.AgentJobStartRequest;
import com.agenticai.core.agents.job.dto.AgentJobSummaryResponse;
import com.agenticai.core.agents.research.dto.ResearchRunStartRequest;
import com.agenticai.core.agents.research.dto.ResearchStatusResponse;
import com.agenticai.core.agents.tools.web.WebSearchProviderRegistry;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Research runs: a thin facade that turns "research this question" into an agent job, with no table
 * of its own. A run IS an {@code agent_job} row; its structured cited report is {@code
 * agent_job.result_json}; history, cancel, cost, progress notes and the trace stream are the job's.
 *
 * <h2>One research agent per USER, not per tenant</h2>
 *
 * Agents are private to their creator unless shared ({@code ResourceAccessService}). A single
 * tenant-wide research agent would either 404 for every user but the one who happened to create it,
 * or - if opened up - show everyone's questions in everyone's history. So each user gets their own
 * agent, found by {@code createdBy == caller}, the fixed name {@link ResearchRunPlan#AGENT_NAME}
 * and an enabled {@code deep-research} capability row. Every read and write then goes through
 * {@link AgentJobService}, which enforces agent access exactly as it does for any other job, and a
 * job id from somebody else's agent is simply not found.
 *
 * <h2>What the run cap here does and does not do</h2>
 *
 * {@link ResearchRunUsageService} counts runs started through this facade; it is the page's "runs
 * left today". It does NOT bound search spend - the research agent can also be chatted with or
 * given jobs directly. Spend is bounded inside the {@code deep_research} tool call by {@code
 * DeepResearchDailyAllowance} (per user per day, every path).
 *
 * <h2>The research agent takes one of the plan's agent slots</h2>
 *
 * It is created through {@link AgentService#create}, so the plan's agent ceiling applies. When the
 * workspace is full the refusal is a research-specific 403 that does not tell a FREE user to delete
 * an agent (FREE cannot delete). A research agent whose setup did not finish is never deleted
 * (again: FREE cannot) - the next run adopts it and finishes the setup instead of creating another.
 *
 * <p>Deliberately NOT {@code @Transactional}: {@code AgentJobService#submit} writes the row and
 * then dispatches it to a worker; wrapping it in an outer transaction would hand the worker a job
 * id whose insert has not committed yet.
 */
@Service
public class ResearchRunService {

    private static final Logger log = LoggerFactory.getLogger(ResearchRunService.class);

    static final String MSG_RUNS_OFF = "Research runs are not available on this deployment.";
    static final String MSG_DEEP_OFF =
            "Deep research is not switched on for this deployment yet. Please try again later.";
    static final String MSG_NO_SEARCH_KEY =
            "Deep research needs the platform web search key, which is not set up yet. "
                    + "Ask your platform admin to add one.";

    static final String MSG_SLOT_FREE_PLAN = "Upgrade your plan to use research.";
    static final String MSG_SLOT_PAID_PLAN =
            "Upgrade your plan, or delete an agent you no longer need, then try again.";

    private final ResearchRunProperties properties;
    private final ResearchRunUsageService usage;
    private final AgentService agentService;
    private final AgentRepository agentRepository;
    private final AgentCapabilityStateRepository capabilityStates;
    private final AgentJobService jobService;
    private final ObjectProvider<PlatformToolCredentialService> platformToolCredentialService;
    private final ObjectProvider<ProviderCredentialResolver> providerCredentialResolver;
    private final TenantLimits tenantLimits;

    /**
     * Striped locks so two simultaneous first runs by one user do not create two research agents.
     */
    private final Object[] createLocks = new Object[32];

    public ResearchRunService(
            ResearchRunProperties properties,
            ResearchRunUsageService usage,
            AgentService agentService,
            AgentRepository agentRepository,
            AgentCapabilityStateRepository capabilityStates,
            AgentJobService jobService,
            ObjectProvider<PlatformToolCredentialService> platformToolCredentialService,
            ObjectProvider<ProviderCredentialResolver> providerCredentialResolver,
            TenantLimits tenantLimits) {
        this.properties = properties;
        this.usage = usage;
        this.agentService = agentService;
        this.agentRepository = agentRepository;
        this.capabilityStates = capabilityStates;
        this.jobService = jobService;
        this.platformToolCredentialService = platformToolCredentialService;
        this.providerCredentialResolver = providerCredentialResolver;
        this.tenantLimits = tenantLimits;
        for (int i = 0; i < createLocks.length; i++) {
            createLocks[i] = new Object();
        }
    }

    /** Always answers (never 404/503): this is how the page learns the feature is off. */
    public ResearchStatusResponse status() {
        TenantContext context = requireContext();
        return new ResearchStatusResponse(
                properties.isDeepEnabled(),
                searchConfigured(),
                properties.isRunsEnabled(),
                new ResearchStatusResponse.Limits(
                        properties.getMaxSearchesPerRun(),
                        properties.getMaxFetchesPerRun(),
                        properties.getMaxSources(),
                        properties.getTotalDeadlineSeconds(),
                        properties.getRunBudgetUsd(),
                        properties.getMaxPerUserPerDay(),
                        properties.getTimeoutSeconds()),
                usage.remainingToday(context.getTenantId(), context.getSubject()));
    }

    /**
     * Starts a run. Order matters: availability (404/503) before the caller (401) before validation
     * (400) before the agent (403 when the plan's agent ceiling is reached) before the daily cap
     * (429), so nothing is counted for a request that could not have run.
     */
    public AgentJobResponse start(ResearchRunStartRequest request) {
        requireRunsEnabled();
        if (!properties.isDeepEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, MSG_DEEP_OFF);
        }
        if (!searchConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, MSG_NO_SEARCH_KEY);
        }
        TenantContext context = requireCaller();
        ResearchRunPlan.Validated run = ResearchRunPlan.validate(request);

        UUID agentId = findOrCreateResearchAgent(context);

        usage.reserve(context.getTenantId(), context.getSubject());
        boolean submitted = false;
        try {
            // true: this run is already counted above; the generic submit gate must not count it
            // again.
            AgentJobResponse response =
                    jobService.submit(
                            agentId,
                            new AgentJobStartRequest(
                                    ResearchRunPlan.goal(run),
                                    properties.getTimeoutSeconds(),
                                    ResearchRunPlan.RESULT_SCHEMA,
                                    null),
                            true);
            submitted = true;
            return response;
        } finally {
            // Any failure (including an Error) before a job exists gives the counted slot back.
            if (!submitted) {
                usage.release(context.getTenantId(), context.getSubject());
            }
        }
    }

    /** The caller's research runs, newest first. Empty (not an error) before their first run. */
    public List<AgentJobSummaryResponse> list(int limit, int offset) {
        return listPage(limit, offset).items();
    }

    /**
     * The caller's research runs across ALL of their research agents (more than one can exist),
     * newest first, and only runs started as research (a job given straight to the agent with some
     * other goal is not research history). {@code hasMore} is exact: one extra row is fetched.
     */
    public AgentJobService.SummarySlice listPage(int limit, int offset) {
        requireRunsEnabled();
        List<UUID> agentIds = findResearchAgents(requireCaller());
        if (agentIds.isEmpty()) {
            return new AgentJobService.SummarySlice(List.of(), false);
        }
        return jobService.listForAgentsWithGoalPrefix(
                agentIds, ResearchRunPlan.GOAL_PREFIX, limit, offset);
    }

    public AgentJobResponse get(UUID jobId) {
        requireRunsEnabled();
        AgentJobResponse job = jobService.get(requireResearchAgent(jobId), jobId);
        // The model wrote the sources list: never hand the page a non-http(s) link.
        if (job != null) {
            ResearchResultSanitizer.sanitize(job.resultJson(), null);
        }
        return job;
    }

    public AgentJobResponse cancel(UUID jobId) {
        requireRunsEnabled();
        return jobService.cancel(requireResearchAgent(jobId), jobId);
    }

    // ------------------------------------------------------------- internals

    private void requireRunsEnabled() {
        if (!properties.isRunsEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, MSG_RUNS_OFF);
        }
    }

    private static TenantContext requireContext() {
        return TenantContext.current()
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.UNAUTHORIZED, "Sign in to use research."));
    }

    /**
     * The agent that owns {@code jobId} among the caller's research agents; a caller with none, or
     * whose agents have no such job, gets the same 404 a wrong job id gets.
     */
    private UUID requireResearchAgent(UUID jobId) {
        List<UUID> agents = findResearchAgents(requireCaller());
        if (agents.isEmpty()) {
            throw new AgentJobNotFoundException(jobId);
        }
        if (agents.size() == 1) {
            return agents.get(0);
        }
        for (UUID agentId : agents) {
            try {
                jobService.get(agentId, jobId);
                return agentId;
            } catch (AgentJobNotFoundException notThisAgent) {
                // try the next research agent
            }
        }
        throw new AgentJobNotFoundException(jobId);
    }

    /**
     * A run belongs to a signed-in user. Without a subject there is nobody to own the research
     * agent: every start would create another ownerless (tenant-visible) one and all such callers
     * would share one usage counter - so refuse up front.
     */
    private static TenantContext requireCaller() {
        TenantContext context = requireContext();
        if (context.getSubject() == null || context.getSubject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to use research.");
        }
        return context;
    }

    // Multi-provider web search (app.web-search.*), optional and setter-injected so the constructor
    // stays
    // untouched. Absent or inactive (the shipped default) = the original Brave-key check.
    private ObjectProvider<WebSearchProviderRegistry> webSearchRegistry;

    @Autowired(required = false)
    void setWebSearchRegistry(ObjectProvider<WebSearchProviderRegistry> webSearchRegistry) {
        this.webSearchRegistry = webSearchRegistry;
    }

    private boolean searchConfigured() {
        try {
            ObjectProvider<WebSearchProviderRegistry> provider = webSearchRegistry;
            WebSearchProviderRegistry registry =
                    provider == null ? null : provider.getIfAvailable();
            return registry == null
                    ? braveKeyPresent()
                    : registry.searchAvailable(this::braveKeyPresent);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean braveKeyPresent() {
        PlatformToolCredentialService credentials = platformToolCredentialService.getIfAvailable();
        return credentials != null
                && credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH).isPresent();
    }

    /**
     * The caller's own research agent: theirs by {@code createdBy}, named, and capability-marked.
     * Oldest wins.
     */
    Optional<UUID> findResearchAgent(TenantContext context) {
        return findResearchAgents(context).stream().findFirst();
    }

    /** Every research agent of the caller (see {@link #findResearchAgent}), oldest first. */
    List<UUID> findResearchAgents(TenantContext context) {
        String subject = context.getSubject();
        if (subject == null || subject.isBlank()) {
            return List.of();
        }
        return agentRepository.findByCreatedBy(subject).stream()
                .filter(agent -> ResearchRunPlan.AGENT_NAME.equals(agent.getName()))
                .filter(
                        agent ->
                                capabilityStates
                                        .findById(
                                                new AgentCapabilityStateId(
                                                        agent.getId(), DeepResearchCapability.CAPABILITY_KEY))
                                        .map(AgentCapabilityState::isEnabled)
                                        .orElse(false))
                .sorted(
                        Comparator.comparing(
                                Agent::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(Agent::getId)
                .toList();
    }

    UUID findOrCreateResearchAgent(TenantContext context) {
        Optional<UUID> existing = findResearchAgent(context);
        if (existing.isPresent()) {
            return existing.get();
        }
        String lockKey = context.getTenantId() + ":" + context.getSubject();
        synchronized (createLocks[Math.floorMod(lockKey.hashCode(), createLocks.length)]) {
            Optional<UUID> raced = findResearchAgent(context);
            if (raced.isPresent()) {
                return raced.get();
            }
            Optional<UUID> unfinished = findUnfinishedResearchAgent(context);
            if (unfinished.isPresent()) {
                finishSetup(unfinished.get());
                return unfinished.get();
            }
            return createResearchAgent(context);
        }
    }

    /**
     * A research agent of the caller's that is not (or no longer) capability-marked: one whose
     * setup failed half-way, or one whose owner switched the capability off. Recognised by owner,
     * name AND the exact research system prompt, so a user's own agent that merely happens to be
     * called "Deep Research" is never taken over. Oldest wins.
     */
    Optional<UUID> findUnfinishedResearchAgent(TenantContext context) {
        return agentRepository.findByCreatedBy(context.getSubject()).stream()
                .filter(agent -> ResearchRunPlan.AGENT_NAME.equals(agent.getName()))
                .filter(agent -> ResearchRunPlan.SYSTEM_PROMPT.equals(agent.getSystemPrompt()))
                .min(
                        Comparator.comparing(
                                Agent::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(Agent::getId);
    }

    /**
     * Idempotent. The capability row goes last: it is the marker, so a half-built agent is never
     * "found".
     */
    private void finishSetup(UUID agentId) {
        agentService.setAllowedToolNames(agentId, ResearchRunPlan.AGENT_TOOLS);
        agentService.setCapabilityEnabled(agentId, DeepResearchCapability.CAPABILITY_KEY, true);
    }

    private UUID createResearchAgent(TenantContext context) {
        requireFreeAgentSlot(context);
        // ADVANCED on purpose: Simple mode would switch the knowledge base on and refuse a tool
        // allow-list.
        AgentResponse created =
                agentService.create(
                        new AgentCreateRequest(
                                ResearchRunPlan.AGENT_NAME,
                                ResearchRunPlan.SYSTEM_PROMPT,
                                resolveProviderName(context),
                                null,
                                ResearchRunPlan.AGENT_MAX_TOKENS,
                                null,
                                null,
                                Boolean.FALSE,
                                ExperienceMode.ADVANCED,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
        UUID id = created.id();
        try {
            finishSetup(id);
        } catch (RuntimeException e) {
            // Not deleted: a FREE workspace cannot delete agents, so a delete here would fail and
            // every
            // retry would add another agent until the plan ceiling. The next run adopts this one
            // instead.
            log.warn(
                    "Research agent {} was created but its setup did not finish; the next run completes it",
                    id,
                    e);
            throw e;
        }
        return id;
    }

    /**
     * The same ceiling {@link AgentService#create} enforces, checked first so the refusal can speak
     * about research: a FREE workspace cannot delete agents, so it must not be told to.
     */
    private void requireFreeAgentSlot(TenantContext context) {
        String tenantId = context.getTenantId();
        if (tenantId == null) {
            return;
        }
        Optional<Integer> maxAgents = tenantLimits.maxAgents(tenantId);
        if (maxAgents == null || maxAgents.isEmpty() || agentRepository.count() < maxAgents.get()) {
            return;
        }
        String start =
                "Research needs one agent slot for your own Deep Research agent, and this workspace "
                        + "already has all "
                        + maxAgents.get()
                        + " agents its plan allows. ";
        throw new TenantLimitExceededException(
                start
                        + (tenantLimits.plan(tenantId) == TenantPlan.FREE
                                ? MSG_SLOT_FREE_PLAN
                                : MSG_SLOT_PAID_PLAN));
    }

    /**
     * Same rule as agent generation: FREE runs on the hosted pool; a paid tenant uses its own
     * active key if it has one.
     */
    private ProviderName resolveProviderName(TenantContext context) {
        String tenantId = context.getTenantId();
        if (tenantId == null || tenantLimits.plan(tenantId) == TenantPlan.FREE) {
            return ProviderName.PLATFORM_HOSTED;
        }
        try {
            ProviderCredentialResolver resolver = providerCredentialResolver.getIfAvailable();
            return resolver == null
                    ? ProviderName.PLATFORM_HOSTED
                    : resolver.resolveAnyActiveKey().providerName();
        } catch (RuntimeException e) {
            return ProviderName.PLATFORM_HOSTED;
        }
    }
}
