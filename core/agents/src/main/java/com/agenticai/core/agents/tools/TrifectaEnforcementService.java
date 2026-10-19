package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.classification.LethalTrifectaDetector;
import com.agenticai.core.agents.tools.classification.TrifectaEnforcementMode;
import com.agenticai.core.agents.tools.classification.TrifectaEnforcementPolicy;
import com.agenticai.core.agents.tools.classification.TrifectaStatusResponse;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns the lethal-trifecta analysis ({@link LethalTrifectaDetector}) from an advisory into
 * something the platform acts on. Three seams use it:
 *
 * <ol>
 *   <li><b>Save time</b> - the user-facing saves ({@code AgentService#setAllowedToolNamesEnforced},
 *       the Auto-select switch and a version restore) call {@link #evaluateSave}: in {@code block}
 *       mode a save that makes the trifecta worse is rejected with an {@link
 *       IllegalArgumentException} (the existing 400), in {@code require-approval} mode the caller
 *       is told to force the agent onto the SUPERVISED autonomy tier ({@link #applyVerdict}).
 *       Server-side writers (template install, demo studio, service envoys, research runs) use the
 *       {@code enforce=false} form, which never throws: a block verdict degrades to the same
 *       SUPERVISED fallback, so an install cannot fail halfway or leave an agent without its
 *       allow-list.
 *   <li><b>Dispatch time</b> - {@code AgentToolGovernance} asks {@link #exemptionHolds} before
 *       letting a confirmation-gate-exempt tool through; an agent that currently holds all three
 *       legs loses the exemption. The verdict is cached per tenant + agent and dropped when the
 *       agent's tool config changes ({@link #invalidateAfterCommit}), with a short TTL as the bound
 *       for changes made on another node or to the tool classifications themselves. Fail SAFE: a
 *       verdict that cannot be computed revokes the exemption.
 *   <li><b>Read time</b> - {@link #status} backs the additive fields on {@code GET
 *       /api/agents/{id}/tool-access/trifecta}.
 * </ol>
 *
 * <p>Config: {@code app.tools.classification.trifecta-enforcement}
 * (off|warn|require-approval|block, default warn), {@code ...trifecta-overrides-exempt} (default
 * true) and {@code ...trifecta-analyse-auto-select} (default false). With the last one on, an agent
 * with an EMPTY allow-list ("Auto-select": every tool it could be offered) is analysed over the
 * tools {@code AgentToolProvider#listAvailableToolsFor} resolves for it instead of reporting no
 * legs. It stays opt-in because that resolution is the same live MCP discovery the tool picker
 * does, and because it widens the set of agents the exempt-list override applies to.
 *
 * <p>Collaborators are {@link ObjectProvider}s on purpose: this bean is reached from {@code
 * AgentService} and {@code AgentToolGovernance}, both of which {@code AgentToolProvider} already
 * depends on, so eager constructor injection of either would be a cycle.
 */
@Component
public class TrifectaEnforcementService {

    private static final Logger log = LoggerFactory.getLogger(TrifectaEnforcementService.class);

    /** Saves rejected in block mode. */
    public static final String BLOCKED_METRIC = "agent.config.lethal_trifecta.blocked";

    /** Agents actually moved to the SUPERVISED tier by require-approval mode. */
    public static final String APPROVAL_FORCED_METRIC =
            "agent.config.lethal_trifecta.approval_forced";

    /**
     * Dispatches where an exempt tool lost its exemption because the agent holds the trifecta (or
     * could not be judged).
     */
    public static final String EXEMPT_REVOKED_METRIC =
            "agent.config.lethal_trifecta.exempt_revoked";

    private static final long VERDICT_TTL_MILLIS = 60_000L;
    private static final long UNKNOWN_TTL_MILLIS = 5_000L;
    private static final int CACHE_MAX = 5_000;

    /**
     * Outcome of a save-time evaluation. {@code report} is the analysis of the tool mix being saved
     * (null for NONE).
     */
    public record SaveVerdict(
            TrifectaEnforcementPolicy.Action action, LethalTrifectaDetector.Report report) {

        public static final SaveVerdict NONE =
                new SaveVerdict(TrifectaEnforcementPolicy.Action.NONE, null);

        public boolean forcesApproval() {
            return action == TrifectaEnforcementPolicy.Action.FORCE_APPROVAL;
        }
    }

    private record Verdict(Boolean holds, long expiresAtMillis) {}

    private final ObjectProvider<LethalTrifectaDetector> detector;
    private final ObjectProvider<AgentService> agentService;
    private final ObjectProvider<AgentToolPort> toolProvider;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final TrifectaEnforcementMode mode;
    private final boolean overridesExempt;
    private final boolean analyseAutoSelect;
    private final boolean confirmationGate;

    private final ConcurrentHashMap<String, Verdict> cache = new ConcurrentHashMap<>();
    private volatile LongSupplier clock = System::currentTimeMillis;

    public TrifectaEnforcementService(
            ObjectProvider<LethalTrifectaDetector> detector,
            ObjectProvider<AgentService> agentService,
            ObjectProvider<AgentToolPort> toolProvider,
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${app.tools.classification.trifecta-enforcement:warn}") String mode,
            @Value("${app.tools.classification.trifecta-overrides-exempt:true}")
                    boolean overridesExempt,
            @Value("${app.tools.classification.trifecta-analyse-auto-select:false}")
                    boolean analyseAutoSelect,
            @Value("${app.tools.classification.confirmation-gate:true}") boolean confirmationGate) {
        this.detector = detector;
        this.agentService = agentService;
        this.toolProvider = toolProvider;
        this.meterRegistry = meterRegistry;
        if (!TrifectaEnforcementMode.isRecognised(mode)) {
            log.warn(
                    "Unknown app.tools.classification.trifecta-enforcement value '{}' - treating it as 'warn' "
                            + "(expected off, warn, require-approval or block)",
                    mode);
        }
        this.mode = TrifectaEnforcementMode.parse(mode);
        this.overridesExempt = overridesExempt;
        this.analyseAutoSelect = analyseAutoSelect;
        this.confirmationGate = confirmationGate;
    }

    public TrifectaEnforcementMode mode() {
        return mode;
    }

    // ---- save time --------------------------------------------------------

    /**
     * Evaluates the tool mix an agent save would leave behind. Throws {@link
     * IllegalArgumentException} (the existing 400) with {@link
     * TrifectaEnforcementPolicy#blockMessage} ONLY in block mode for a save that makes the trifecta
     * worse; every other outcome, including any internal failure, is returned.
     *
     * @param before the agent's allow-list before the save (empty = Auto-select)
     * @param after the allow-list the save would store (empty = Auto-select)
     */
    public SaveVerdict evaluateSave(
            UUID agentId, Collection<String> before, Collection<String> after) {
        return evaluateSave(agentId, before, after, true);
    }

    /**
     * @param enforce false for server-side writers that must never fail on policy: a verdict that
     *     would reject the save is returned as FORCE_APPROVAL (SUPERVISED tier) instead of thrown.
     */
    public SaveVerdict evaluateSave(
            UUID agentId, Collection<String> before, Collection<String> after, boolean enforce) {
        TrifectaEnforcementMode current = mode;
        if (current == TrifectaEnforcementMode.OFF) {
            return SaveVerdict.NONE;
        }
        LethalTrifectaDetector.Report afterReport;
        LethalTrifectaDetector.Report beforeReport = null;
        try {
            afterReport = reportFor(agentId, after);
            if (!TrifectaEnforcementPolicy.isLethal(afterReport)) {
                return SaveVerdict.NONE;
            }
            if (current == TrifectaEnforcementMode.BLOCK) {
                beforeReport = reportFor(agentId, before);
            }
        } catch (RuntimeException e) {
            // Config time is advisory-by-default: a lookup failure must not turn into a failed
            // save.
            log.debug("Lethal-trifecta evaluation failed for agent {}", agentId, e);
            return SaveVerdict.NONE;
        }
        TrifectaEnforcementPolicy.Action action =
                TrifectaEnforcementPolicy.decide(current, beforeReport, afterReport);
        LethalTrifectaDetector d = detector.getIfAvailable();
        if (d != null && action != TrifectaEnforcementPolicy.Action.NONE) {
            d.recordIfLethal(String.valueOf(agentId), afterReport);
        }
        if (action == TrifectaEnforcementPolicy.Action.BLOCK) {
            if (!enforce) {
                return new SaveVerdict(
                        TrifectaEnforcementPolicy.Action.FORCE_APPROVAL, afterReport);
            }
            count(BLOCKED_METRIC);
            throw new IllegalArgumentException(TrifectaEnforcementPolicy.blockMessage(afterReport));
        }
        return new SaveVerdict(action, afterReport);
    }

    /**
     * Applies a save verdict to the agent about to be persisted: drops its cached trifecta verdict
     * (whatever the verdict, its tool config just changed) and, for FORCE_APPROVAL, moves it to
     * SUPERVISED (the tier that maps to ALWAYS_GATE). Call it AFTER any other write that could set
     * the tier (a version restore copies the stored tier). Returns the version-history note suffix,
     * or "" when nothing changed.
     */
    public String applyVerdict(Agent agent, SaveVerdict verdict) {
        invalidateAfterCommit(agent.getId());
        if (verdict == null
                || !verdict.forcesApproval()
                || agent.getAutonomyTier() == AgentAutonomyTier.SUPERVISED) {
            return "";
        }
        agent.setAutonomyTier(AgentAutonomyTier.SUPERVISED);
        noteApprovalForced(agent.getId());
        return "; autonomy set to SUPERVISED because this tool mix is a lethal trifecta (enforcement: "
                + mode.wireName()
                + ")";
    }

    /** The caller moved the agent to SUPERVISED because of {@link SaveVerdict#forcesApproval()}. */
    public void noteApprovalForced(UUID agentId) {
        log.info(
                "Agent {} holds the lethal trifecta - autonomy tier forced to SUPERVISED (always gate)",
                agentId);
        count(APPROVAL_FORCED_METRIC);
    }

    // ---- read time --------------------------------------------------------

    /** Never throws. Under {@code off} the legs are empty so the console shows nothing. */
    public TrifectaStatusResponse status(
            UUID agentId, Collection<String> allowList, AgentAutonomyTier tier) {
        LethalTrifectaDetector.Report empty = LethalTrifectaDetector.analyseDecisions(Map.of());
        TrifectaEnforcementMode current = mode;
        if (current == TrifectaEnforcementMode.OFF) {
            return TrifectaStatusResponse.of(empty, current, false, false);
        }
        LethalTrifectaDetector.Report report = null;
        try {
            report = reportFor(agentId, allowList);
        } catch (RuntimeException e) {
            log.debug("Lethal-trifecta status failed for agent {}", agentId, e);
        }
        if (report == null) {
            report = empty;
        }
        boolean lethal = report.isLethalTrifecta();
        return TrifectaStatusResponse.of(
                report,
                current,
                TrifectaEnforcementPolicy.isEnforced(lethal, current),
                TrifectaEnforcementPolicy.isGated(
                        lethal, current, tier, overridesExempt, confirmationGate));
    }

    // ---- dispatch time ----------------------------------------------------

    /**
     * Whether a confirmation-gate-exempt tool keeps its exemption for this agent. Cheap on the hot
     * path: one map lookup per call, the analysis only runs on a cache miss.
     */
    public boolean exemptionHolds(UUID agentId) {
        // warn is "today's behaviour": the exempt list keeps applying until the operator opts into
        // enforcement.
        if (mode == TrifectaEnforcementMode.OFF
                || mode == TrifectaEnforcementMode.WARN
                || !overridesExempt) {
            return true;
        }
        boolean stays =
                TrifectaEnforcementPolicy.exemptionHolds(
                        mode, overridesExempt, holdsTrifecta(agentId));
        if (!stays) {
            count(EXEMPT_REVOKED_METRIC);
        }
        return stays;
    }

    /**
     * Cached per tenant + agent. {@code null} = could not be computed (cached only briefly). Never
     * throws.
     */
    Boolean holdsTrifecta(UUID agentId) {
        if (agentId == null) {
            return null;
        }
        String key = cacheKey(agentId);
        long now = clock.getAsLong();
        Verdict cached = cache.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.holds();
        }
        Boolean holds = computeHolds(agentId);
        if (cache.size() >= CACHE_MAX) {
            cache.clear();
        }
        cache.put(
                key,
                new Verdict(
                        holds, now + (holds == null ? UNKNOWN_TTL_MILLIS : VERDICT_TTL_MILLIS)));
        return holds;
    }

    private Boolean computeHolds(UUID agentId) {
        try {
            AgentService agents = agentService.getIfAvailable();
            if (agents == null) {
                return null;
            }
            LethalTrifectaDetector.Report report =
                    reportFor(agentId, new HashSet<>(agents.getAllowedToolNames(agentId)));
            return report == null ? null : report.isLethalTrifecta();
        } catch (RuntimeException e) {
            log.debug(
                    "Lethal-trifecta verdict for agent {} could not be computed - exemption revoked",
                    agentId,
                    e);
            return null;
        }
    }

    /**
     * Drops the cached verdict now and again once the surrounding transaction commits, so a reader
     * that repopulates the cache from the pre-commit state is corrected.
     */
    public void invalidateAfterCommit(UUID agentId) {
        if (agentId == null) {
            return;
        }
        String key = cacheKey(agentId);
        cache.remove(key);
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                cache.remove(key);
                            }
                        });
            }
        } catch (RuntimeException e) {
            log.debug(
                    "Could not register post-commit trifecta cache invalidation for agent {}",
                    agentId,
                    e);
        }
    }

    // ---- shared -----------------------------------------------------------

    /**
     * The analysis of one allow-list. {@code null} when it could not be judged (classification
     * lookup failed, no detector, or Auto-select tools could not be resolved). A non-empty
     * allow-list is analysed as given; an empty one only with {@code trifecta-analyse-auto-select}
     * on.
     */
    private LethalTrifectaDetector.Report reportFor(UUID agentId, Collection<String> allowList) {
        LethalTrifectaDetector d = detector.getIfAvailable();
        if (d == null) {
            return null;
        }
        Collection<String> names = allowList;
        if (names == null || names.isEmpty()) {
            if (!analyseAutoSelect) {
                return LethalTrifectaDetector.analyseDecisions(Map.of());
            }
            names = offeredToolNames(agentId);
            if (names == null) {
                return null;
            }
        }
        try {
            return d.analyseStrict(names);
        } catch (RuntimeException e) {
            log.debug(
                    "Lethal-trifecta analysis skipped for agent {}: classification lookup failed",
                    agentId,
                    e);
            return null;
        }
    }

    /**
     * The tool names the agent would actually be offered (Auto-select), or {@code null} when
     * unresolvable.
     */
    private List<String> offeredToolNames(UUID agentId) {
        AgentToolPort provider = toolProvider.getIfAvailable();
        if (provider == null) {
            return null;
        }
        try {
            return provider.listAvailableToolsFor(agentId).stream()
                    .map(ToolDescriptor::name)
                    .toList();
        } catch (RuntimeException e) {
            log.debug(
                    "Could not resolve the offered tools of agent {} for the trifecta analysis",
                    agentId,
                    e);
            return null;
        }
    }

    private static String cacheKey(UUID agentId) {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse("-");
        return tenant + "|" + agentId;
    }

    private void count(String name) {
        MeterRegistry meters = meterRegistry.getIfAvailable();
        if (meters == null) {
            return;
        }
        try {
            meters.counter(name).increment();
        } catch (RuntimeException e) {
            log.debug("Could not record metric {}", name, e);
        }
    }

    /** Test seam for the verdict cache's TTL. */
    void setClockForTest(LongSupplier clock) {
        this.clock = clock;
    }
}
