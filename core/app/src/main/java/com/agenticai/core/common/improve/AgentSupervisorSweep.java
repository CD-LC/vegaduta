package com.agenticai.core.common.improve;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly meta-agent supervisor sweep - the proactive half of the
 * auto-improvement loop (Intercom "Fin Operator"-style: an agent that
 * watches a live, customer-facing agent's recent behaviour and surfaces
 * tuning on its own, rather than waiting for an admin to click "scan").
 *
 * <p>This is deliberately NOT a second suggestion system. Per tenant, per
 * agent, it does exactly two things every tick, both of which already
 * existed and are only being invoked on a schedule now instead of only from
 * {@code POST /api/agents/{agentId}/improvements/generate}:
 * <ol>
 *   <li>{@link AgentImprovementService#generateForAgent} - distils any new
 *       recurring OPEN {@code PlatformIssue} clusters into PENDING
 *       {@link AgentImprovementSuggestion}s (bounded, deduped; see that
 *       class's javadoc for the full safety posture - nothing here ever
 *       auto-applies a change).</li>
 *   <li>{@link AgentHealthScoreService#recompute} - refreshes the agent's
 *       live health/confidence snapshot from the same OPEN-issue evidence
 *       plus the current PENDING-suggestion queue depth, so the console can
 *       show "how is this agent doing" without a human first asking for a
 *       scan.</li>
 * </ol>
 *
 * <p>Same operational shape as {@code ChannelSessionResolutionSweep}
 * ({@code core/app/common/resolution}, the other Tier-4/gap-radar hourly
 * sweep in this module): {@link TenantSchemaIterator} for per-tenant
 * {@link TenantContext} (the ONLY correct way for a {@code @Scheduled} job
 * to reach tenant tables - see that class's javadoc on the cross-tenant
 * bugs it exists to prevent), {@link PgAdvisoryJobLock} so exactly one
 * replica sweeps per tick, per-tenant AND per-agent failure isolation (one
 * bad LLM call or one bad tenant must never blank the rest of a scan), and
 * an off-switch ({@code agent-supervisor.sweep.enabled} / env
 * {@code AGENT_SUPERVISOR_SWEEP_ENABLED}, default true) matching this
 * codebase's opt-out posture for background jobs.
 *
 * <p>Unlike the resolution sweep, every read/write here is already a plain
 * JPA repository call ({@code AgentRepository}, {@code PlatformIssueRepository}
 * via {@code AgentImprovementService}/{@code AgentHealthScoreService}) - no
 * raw JDBC needed, because {@code core/app} already depends on
 * {@code core/agents} and {@code core/audit} for the existing manual
 * "generate" endpoint this sweep now also drives.
 */
@Component
public class AgentSupervisorSweep {

    private static final Logger log = LoggerFactory.getLogger(AgentSupervisorSweep.class);

    private static final String JOB_LOCK_NAME = "agent-supervisor-sweep";

    private final TenantSchemaIterator tenantSchemaIterator;
    private final PgAdvisoryJobLock jobLock;
    private final AgentRepository agentRepository;
    private final AgentImprovementService improvementService;
    private final AgentHealthScoreService healthScoreService;
    private final boolean enabled;

    public AgentSupervisorSweep(
        TenantSchemaIterator tenantSchemaIterator,
        PgAdvisoryJobLock jobLock,
        AgentRepository agentRepository,
        AgentImprovementService improvementService,
        AgentHealthScoreService healthScoreService,
        @Value("${agent-supervisor.sweep.enabled:true}") boolean enabled
    ) {
        this.tenantSchemaIterator = tenantSchemaIterator;
        this.jobLock = jobLock;
        this.agentRepository = agentRepository;
        this.improvementService = improvementService;
        this.healthScoreService = healthScoreService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "0 23 * * * *", zone = "UTC")
    public void sweep() {
        if (!enabled) {
            return;
        }
        jobLock.tryRunExclusive(JOB_LOCK_NAME, this::sweepExclusively);
    }

    private void sweepExclusively() {
        tenantSchemaIterator.forEachActiveTenantSchema(schema -> {
            try {
                int swept = sweepTenant();
                if (swept > 0) {
                    log.info("Agent supervisor sweep processed {} agent(s) in tenant schema '{}'", swept, schema);
                }
            } catch (RuntimeException e) {
                log.error("Agent supervisor sweep failed in tenant schema '{}'", schema, e);
            }
        });
    }

    int sweepTenant() {
        List<Agent> agents = agentRepository.findAll();
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        int swept = 0;
        // Once this workspace's provider has refused us, no other agent's distillation would fare better: skip
        // the LLM half for the rest of the tenant (health scores are plain SQL and still refresh).
        boolean[] providerPaused = {false};
        for (Agent agent : agents) {
            if (sweepAgent(agent, tenantId, providerPaused)) {
                swept++;
            }
        }
        return swept;
    }

    private static final com.agenticai.core.resilience.RateLimitedLog PAUSED_LOG =
        new com.agenticai.core.resilience.RateLimitedLog(log, java.time.Duration.ofHours(1));

    /**
     * One agent's tick. A failure here (e.g. the LLM distillation call
     * inside {@code generateForAgent}) is logged and skipped - it must
     * never stop the rest of this tenant's agents from being swept, the
     * same posture {@code AgentImprovementService#generateForAgent} already
     * takes per issue cluster.
     */
    private boolean sweepAgent(Agent agent, String tenantId, boolean[] providerPaused) {
        try {
            if (!providerPaused[0]) {
                try {
                    improvementService.generateForAgent(agent.getId(), true);
                } catch (com.agenticai.core.providers.ProviderCoolingDownException cooling) {
                    providerPaused[0] = true;
                    // One line per hour per tenant, not a stack trace per agent per tick.
                    PAUSED_LOG.warn("supervisor-provider-paused:" + tenantId,
                        "Agent supervisor sweep: " + cooling.getMessage());
                }
            }
            healthScoreService.recompute(agent.getId(), tenantId);
            return true;
        } catch (RuntimeException e) {
            log.warn("Agent supervisor sweep failed for agent {} - skipping", agent.getId(), e);
            return false;
        }
    }
}
