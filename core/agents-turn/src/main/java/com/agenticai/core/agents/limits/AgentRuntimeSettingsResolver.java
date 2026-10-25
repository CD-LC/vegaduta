package com.agenticai.core.agents.limits;

import com.agenticai.core.agents.runtime.AgentRuntime;
import com.agenticai.core.agents.runtime.RuntimeLayer;
import com.agenticai.core.agents.runtime.RuntimeSetting;
import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.agents.runtime.AgentRuntimeSettings;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.chat.ToolLoopProperties;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The ONE place the agent-runtime limits are decided. Every path that enforces one (the tool guard, the
 * tool loop, the stream and buffered turns, continuation, the knowledge-search tool, agent jobs and the turn
 * after an approval) reads {@link AgentRuntimeSettings} from here, so a limit set on an agent is the limit
 * on all of them.
 *
 * <p><b>Layers, lowest first.</b> {@link RuntimeLayer#PLATFORM} (the operator's {@code application*.yml}; the
 * property classes the modules already had are read as they are, see {@link AgentRuntimeProperties}), then
 * {@link RuntimeLayer#TENANT} (the tenant admin's defaults), then {@link RuntimeLayer#AGENT} (the agent's own
 * overrides), then {@link RuntimeLayer#REQUEST} (a cap one run carries; it can only tighten). A layer that does
 * not set a value leaves it to the layer below.
 *
 * <p><b>Tool steps and the autonomy tier.</b> The platform and tenant values are the BASE ceiling; the agent's
 * autonomy tier scales it ({@code app.guardrails.step-limit.*}: SUPERVISED fewer, FULLY_AUTONOMOUS more). An
 * agent's own {@code maxToolSteps} is an explicit ceiling and is used exactly as set: the tier does not scale
 * it again.
 *
 * <p><b>Bounds.</b> Values from the tenant, agent and request layers are validated on write
 * ({@link #validate}) and clamped into {@code app.agent-runtime.bounds.*} when read.
 *
 * <p>Public API: {@link #forAgent(UUID)} / {@link #forAgent(UUID, RuntimeLimits)} for a turn,
 * {@link #resolve} (pure, for tests and previews), {@link #platform(AgentAutonomyTier)},
 * {@link #toolOutput}, {@link #toolLoopRounds}, {@link #validate}, {@link #bounds}.
 */
public class AgentRuntimeSettingsResolver implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeSettingsResolver.class);

    /** The platform values that live under keys other modules own, passed in rather than re-read. */
    public record PlatformKeys(int maxToolCallsPerTurn, Duration turnTimeout, int continuationPasses) {}

    private final AgentRuntimeProperties properties;
    private final PlatformKeys platformKeys;
    private final ToolStepLimitProperties stepLimits;
    private final ToolOutputProperties toolOutput;
    private final ToolLoopProperties toolLoop;
    private final TenantRuntimeLimitsService tenantLimits;
    private final AgentService agents;

    public AgentRuntimeSettingsResolver(
            AgentRuntimeProperties properties,
            PlatformKeys platformKeys,
            ToolStepLimitProperties stepLimits,
            ToolOutputProperties toolOutput,
            ToolLoopProperties toolLoop,
            TenantRuntimeLimitsService tenantLimits,
            AgentService agents) {
        this.properties = properties;
        this.platformKeys = platformKeys;
        this.stepLimits = stepLimits;
        this.toolOutput = toolOutput;
        this.toolLoop = toolLoop;
        this.tenantLimits = tenantLimits;
        this.agents = agents;
    }

    // --- resolution -----------------------------------------------------------------------------

    /** The settings a turn of {@code agentId} runs under. */
    @Override
    public AgentRuntimeSettings forAgent(UUID agentId) {
        return forAgent(agentId, null);
    }

    /**
     * As {@link #forAgent(UUID)}, with the request-layer {@code caps} of one run (null = none), which can only
     * tighten what the other layers allow.
     */
    @Override
    public AgentRuntimeSettings forAgent(UUID agentId, RuntimeLimits caps) {
        return resolve(
                agents.getAutonomyTier(agentId), tenantLayer(), agents.getRuntimeLimits(agentId), caps, stepLimitsFor(agentId));
    }

    // --- the step-limit knobs (agent.steps.*) ------------------------------------------------------

    private volatile Supplier<SettingsResolver> settings = () -> null;

    /**
     * The settings catalog, so a tenant's or an agent's {@code agent.steps.*} replace {@code
     * app.guardrails.step-limit.*} for that agent's turns (how the autonomy tiers scale the ceiling, and how many
     * calls past it are answered before the turn is stopped). Absent: the platform values. Asked lazily because the
     * catalog itself reads this resolver for the platform values of the runtime limits.
     */
    public void useSettings(Supplier<SettingsResolver> resolver) {
        this.settings = resolver == null ? () -> null : resolver;
    }

    /**
     * The step-limit numbers for the calling thread's tenant and {@code agentId}: the platform object itself when
     * nothing differs (no allocation on the common path), else a copy. A read that fails answers the platform values.
     */
    ToolStepLimitProperties stepLimitsFor(UUID agentId) {
        try {
            SettingsResolver catalog = settings.get();
            if (catalog == null) {
                return stepLimits;
            }
            SettingContext ctx = SettingContext.forAgent(agentId);
            int grace = catalog.getInt(StepLimitSettings.GRACE_CALLS, ctx);
            int divisor = catalog.getInt(StepLimitSettings.SUPERVISED_DIVISOR, ctx);
            int minSteps = catalog.getInt(StepLimitSettings.SUPERVISED_MIN_STEPS, ctx);
            int multiplier = catalog.getInt(StepLimitSettings.AUTONOMOUS_MULTIPLIER, ctx);
            if (grace == stepLimits.getGraceCalls()
                    && divisor == stepLimits.getSupervisedDivisor()
                    && minSteps == stepLimits.getSupervisedMinSteps()
                    && multiplier == stepLimits.getAutonomousMultiplier()) {
                return stepLimits;
            }
            return stepLimits.withNumbers(grace, divisor, minSteps, multiplier);
        } catch (RuntimeException e) {
            log.warn("Could not read the agent.steps.* settings; using app.guardrails.step-limit.*: {}", e.toString());
            return stepLimits;
        }
    }

    /** The platform layer alone, for an agent of {@code tier}: what an agent with no override gets. */
    public AgentRuntimeSettings platform(AgentAutonomyTier tier) {
        return resolve(tier, null, null, null);
    }

    /** The tenant defaults of the current tenant (empty when it set none). */
    public RuntimeLimits tenantLayer() {
        try {
            return tenantLimits.current();
        } catch (RuntimeException e) {
            // A read failure must not stop a turn: the platform defaults are the safe baseline.
            log.warn("Could not read the tenant's runtime limits; using the platform defaults: {}", e.toString());
            return RuntimeLimits.none();
        }
    }

    /** Layers the four sources into the settings of one turn. Pure: no I/O. */
    public AgentRuntimeSettings resolve(
            AgentAutonomyTier tier, RuntimeLimits tenant, RuntimeLimits agent, RuntimeLimits request) {
        return resolve(tier, tenant, agent, request, stepLimits);
    }

    /** As above under {@code stepNumbers}, the step-limit numbers of this turn (see {@link #stepLimitsFor}). */
    AgentRuntimeSettings resolve(
            AgentAutonomyTier tier,
            RuntimeLimits tenant,
            RuntimeLimits agent,
            RuntimeLimits request,
            ToolStepLimitProperties stepNumbers) {
        RuntimeLimits tenantLayer = tenant == null ? RuntimeLimits.none() : tenant;
        RuntimeLimits agentLayer = agent == null ? RuntimeLimits.none() : agent;
        RuntimeLimits requestLayer = request == null ? RuntimeLimits.none() : request;
        AgentRuntimeProperties.Defaults defaults = properties.getDefaults();
        Map<RuntimeSetting, RuntimeLayer> layers = new EnumMap<>(RuntimeSetting.class);

        // Tool steps: platform/tenant give the base the tier scales; the agent's value is the ceiling itself.
        int base = platformKeys.maxToolCallsPerTurn();
        RuntimeLayer stepsLayer = RuntimeLayer.PLATFORM;
        Integer tenantSteps = clamped(RuntimeSetting.MAX_TOOL_STEPS, tenantLayer.getMaxToolSteps());
        if (tenantSteps != null) {
            base = tenantSteps;
            stepsLayer = RuntimeLayer.TENANT;
        }
        int steps = scaleByTier(tier, base, stepNumbers);
        Integer agentSteps = clamped(RuntimeSetting.MAX_TOOL_STEPS, agentLayer.getMaxToolSteps());
        if (agentSteps != null) {
            steps = agentSteps;
            stepsLayer = RuntimeLayer.AGENT;
        }
        Integer capSteps = clamped(RuntimeSetting.MAX_TOOL_STEPS, requestLayer.getMaxToolSteps());
        if (capSteps != null && capSteps < steps) {
            steps = capSteps;
            stepsLayer = RuntimeLayer.REQUEST;
        }
        layers.put(RuntimeSetting.MAX_TOOL_STEPS, stepsLayer);

        // The platform's turn timeout keeps its precision (an operator may set 600ms in a test profile); the
        // layers above it are whole seconds.
        Duration turnTimeout = platformKeys.turnTimeout();
        RuntimeLayer turnTimeoutLayer = RuntimeLayer.PLATFORM;
        Integer tenantTurn = clamped(RuntimeSetting.TURN_TIMEOUT_SECONDS, tenantLayer.getTurnTimeoutSeconds());
        if (tenantTurn != null) {
            turnTimeout = Duration.ofSeconds(tenantTurn);
            turnTimeoutLayer = RuntimeLayer.TENANT;
        }
        Integer agentTurn = clamped(RuntimeSetting.TURN_TIMEOUT_SECONDS, agentLayer.getTurnTimeoutSeconds());
        if (agentTurn != null) {
            turnTimeout = Duration.ofSeconds(agentTurn);
            turnTimeoutLayer = RuntimeLayer.AGENT;
        }
        Integer capTurn = clamped(RuntimeSetting.TURN_TIMEOUT_SECONDS, requestLayer.getTurnTimeoutSeconds());
        if (capTurn != null && Duration.ofSeconds(capTurn).compareTo(turnTimeout) < 0) {
            turnTimeout = Duration.ofSeconds(capTurn);
            turnTimeoutLayer = RuntimeLayer.REQUEST;
        }
        layers.put(RuntimeSetting.TURN_TIMEOUT_SECONDS, turnTimeoutLayer);

        Chosen toolTimeout = choose(RuntimeSetting.TOOL_TIMEOUT_SECONDS, defaults.getToolTimeoutSeconds(),
                tenantLayer.getToolTimeoutSeconds(), agentLayer.getToolTimeoutSeconds(),
                requestLayer.getToolTimeoutSeconds(), true);
        layers.put(RuntimeSetting.TOOL_TIMEOUT_SECONDS, toolTimeout.layer());

        Chosen output = choose(RuntimeSetting.TOOL_OUTPUT_MAX_CHARS, toolOutput.getMaxChars(),
                tenantLayer.getToolOutputMaxChars(), agentLayer.getToolOutputMaxChars(),
                requestLayer.getToolOutputMaxChars(), false);
        layers.put(RuntimeSetting.TOOL_OUTPUT_MAX_CHARS, output.layer());

        Chosen topK = choose(RuntimeSetting.RETRIEVAL_TOP_K, defaults.getRetrievalTopK(),
                tenantLayer.getRetrievalTopK(), agentLayer.getRetrievalTopK(),
                requestLayer.getRetrievalTopK(), false);
        layers.put(RuntimeSetting.RETRIEVAL_TOP_K, topK.layer());

        Chosen passes = choose(RuntimeSetting.CONTINUATION_PASSES, platformKeys.continuationPasses(),
                tenantLayer.getContinuationPasses(), agentLayer.getContinuationPasses(),
                requestLayer.getContinuationPasses(), false);
        layers.put(RuntimeSetting.CONTINUATION_PASSES, passes.layer());

        double minScore = defaults.getRetrievalMinScore();
        RuntimeLayer minScoreLayer = RuntimeLayer.PLATFORM;
        Double tenantScore = clamped(tenantLayer.getRetrievalMinScore());
        if (tenantScore != null) {
            minScore = tenantScore;
            minScoreLayer = RuntimeLayer.TENANT;
        }
        Double agentScore = clamped(agentLayer.getRetrievalMinScore());
        if (agentScore != null) {
            minScore = agentScore;
            minScoreLayer = RuntimeLayer.AGENT;
        }
        Double capScore = clamped(requestLayer.getRetrievalMinScore());
        if (capScore != null && capScore > minScore) {
            minScore = capScore;
            minScoreLayer = RuntimeLayer.REQUEST;
        }
        layers.put(RuntimeSetting.RETRIEVAL_MIN_SCORE, minScoreLayer);

        return new AgentRuntimeSettings(
                steps,
                turnTimeout,
                Duration.ofSeconds(toolTimeout.value()),
                output.value(),
                topK.value(),
                minScore,
                passes.value(),
                layers,
                stepNumbers == stepLimits ? -1 : stepNumbers.getGraceCalls());
    }

    private record Chosen(int value, RuntimeLayer layer) {}

    /**
     * Platform, then tenant, then agent; the request layer only tightens. {@code zeroMeansNone}: a value of 0 is
     * "no limit" (a per-call timeout), so any positive request cap tightens it.
     */
    private Chosen choose(
            RuntimeSetting setting, int platform, Integer tenant, Integer agent, Integer request, boolean zeroMeansNone) {
        int value = platform;
        RuntimeLayer layer = RuntimeLayer.PLATFORM;
        Integer t = clamped(setting, tenant);
        if (t != null) {
            value = t;
            layer = RuntimeLayer.TENANT;
        }
        Integer a = clamped(setting, agent);
        if (a != null) {
            value = a;
            layer = RuntimeLayer.AGENT;
        }
        Integer r = clamped(setting, request);
        if (r != null && ((value <= 0 && zeroMeansNone) ? r > 0 : r < value)) {
            value = r;
            layer = RuntimeLayer.REQUEST;
        }
        return new Chosen(value, layer);
    }

    private int scaleByTier(AgentAutonomyTier tier, int base, ToolStepLimitProperties numbers) {
        AgentAutonomyTier effective = tier == null ? AgentAutonomyTier.SEMI_AUTONOMOUS : tier;
        return switch (effective) {
            case SUPERVISED -> numbers.supervisedCeiling(base);
            case SEMI_AUTONOMOUS -> base;
            case FULLY_AUTONOMOUS -> numbers.autonomousCeiling(base);
        };
    }

    /** {@code value} clamped into the setting's bounds; null stays null. */
    private Integer clamped(RuntimeSetting setting, Integer value) {
        if (value == null) {
            return null;
        }
        AgentRuntimeProperties.Range range = properties.getBounds().of(setting);
        long min = (long) Math.ceil(range.getMin());
        long max = (long) Math.floor(range.getMax());
        return (int) Math.max(min, Math.min(max, value));
    }

    private Double clamped(Double value) {
        if (value == null || value.isNaN()) {
            return null;
        }
        AgentRuntimeProperties.Range range = properties.getBounds().of(RuntimeSetting.RETRIEVAL_MIN_SCORE);
        return Math.max(range.getMin(), Math.min(range.getMax(), value));
    }

    // --- derived views ---------------------------------------------------------------------------

    /**
     * The tool-output configuration for a turn: the operator's marker and per-tool caps, with the turn's cap as
     * the default every other tool gets.
     */
    public ToolOutputProperties toolOutput(AgentRuntimeSettings settings) {
        return settings == null ? toolOutput : toolOutput.withMaxChars(settings.toolOutputMaxChars());
    }

    /**
     * The tool loop's per-invocation round cap for a turn. The cap is a backstop for loops that make no
     * countable call (parked, refused, withheld, unknown - the guard answers them without spending the
     * step budget), so it follows the ceiling this turn ACTUALLY enforces: an explicit {@code
     * app.chat.tools.max-rounds-per-invocation} wins; then the cap derived from the platform ceiling is
     * used only when there is no turn-specific settings to follow; otherwise the cap is derived from
     * this turn's own (autonomy-scaled) ceiling plus its grace - not the platform's largest one, which
     * let a parked SUPERVISED turn keep looping for dozens of rounds past what its ceiling needed.
     */
    @Override
    public int toolLoopRounds(AgentRuntimeSettings settings) {
        int platformGrace = stepLimits.getGraceCalls();
        if (settings == null || toolLoop.getMaxRoundsPerInvocation() > 0) {
            return toolLoop.effectiveMaxRounds(
                    platformKeys.maxToolCallsPerTurn(), stepLimits.getAutonomousMultiplier(), platformGrace);
        }
        // The grace this turn's guard applies (a tenant or an agent may have raised it): the backstop must outlast it.
        int grace = settings.hasGraceCalls() ? settings.graceCalls() : platformGrace;
        return toolLoop.effectiveMaxRounds(settings.maxToolSteps(), 1, grace);
    }

    /** What the model is told when a tool call outlives the tool timeout ({@code app.agent-runtime.tool-timeout-message}). */
    @Override
    public String toolTimeoutMessage() {
        return properties.getToolTimeoutMessage();
    }

    /** The tool-step grace, so an enforcement site can build its own derived numbers. */
    public ToolStepLimitProperties stepLimits() {
        return stepLimits;
    }

    // --- validation ------------------------------------------------------------------------------

    /**
     * Refuses a layer that sets a value outside {@code app.agent-runtime.bounds.*}. The 400 names every
     * offending setting and its range, so one round trip is enough to fix a request.
     */
    public void validate(RuntimeLimits limits) {
        if (limits == null) {
            return;
        }
        List<String> problems = new ArrayList<>();
        for (RuntimeSetting setting : RuntimeSetting.values()) {
            Number value = limits.get(setting);
            if (value == null) {
                continue;
            }
            AgentRuntimeProperties.Range range = properties.getBounds().of(setting);
            double v = value.doubleValue();
            if (Double.isNaN(v) || v < range.getMin() || v > range.getMax()) {
                problems.add(setting.apiName() + " must be between " + format(setting, range.getMin()) + " and "
                        + format(setting, range.getMax()) + " (got " + value + ")");
            }
        }
        if (!problems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid runtime limits: " + String.join("; ", problems) + ".");
        }
    }

    private static String format(RuntimeSetting setting, double bound) {
        return setting == RuntimeSetting.RETRIEVAL_MIN_SCORE ? String.valueOf(bound) : String.valueOf((long) bound);
    }

    /** The configured range of every setting, keyed by API name, for the API views. */
    public Map<String, AgentRuntimeProperties.Range> bounds() {
        Map<String, AgentRuntimeProperties.Range> out = new java.util.LinkedHashMap<>();
        for (RuntimeSetting setting : RuntimeSetting.values()) {
            AgentRuntimeProperties.Range range = properties.getBounds().of(setting);
            out.put(setting.apiName(), new AgentRuntimeProperties.Range(range.getMin(), range.getMax()));
        }
        return out;
    }
}
