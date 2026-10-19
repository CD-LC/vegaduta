package com.agenticai.core.agents.runbudget;

import com.agenticai.core.agents.collab.RunBudget;
import com.agenticai.core.agents.collab.RunBudgetCaps;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentRunBudgetResponse;
import com.agenticai.core.sharing.PermissionLevel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read/update API behind {@link AgentRunBudgetController}. The runtime side (which cap a turn
 * actually gets) lives in {@code AgentToolProvider}; both use {@link RunBudgetCaps} so the
 * number shown here is the number enforced there.
 */
@Service
public class AgentRunBudgetService {

    static final String SCOPE = "delegation tools only";

    private final AgentService agentService;
    private final BigDecimal globalCapUsd;
    private final BigDecimal inputUsdPerM;
    private final BigDecimal outputUsdPerM;
    private final long avgBranchTokens;
    private final boolean overrideEnabled;
    private final BigDecimal maxPerAgentUsd;

    public AgentRunBudgetService(
        AgentService agentService,
        @Value("${app.guardrails.run-budget.usd:0}") BigDecimal globalCapUsd,
        @Value("${app.guardrails.run-budget.input-usd-per-m:3.00}") BigDecimal inputUsdPerM,
        @Value("${app.guardrails.run-budget.output-usd-per-m:15.00}") BigDecimal outputUsdPerM,
        @Value("${app.guardrails.run-budget.avg-branch-tokens:8000}") long avgBranchTokens,
        @Value("${app.guardrails.run-budget.per-agent-override-enabled:true}") boolean overrideEnabled,
        @Value("${app.guardrails.run-budget.max-per-agent-usd:10}") BigDecimal maxPerAgentUsd
    ) {
        this.agentService = agentService;
        this.globalCapUsd = globalCapUsd == null || globalCapUsd.signum() < 0 ? BigDecimal.ZERO : globalCapUsd;
        this.inputUsdPerM = inputUsdPerM;
        this.outputUsdPerM = outputUsdPerM;
        this.avgBranchTokens = avgBranchTokens;
        this.overrideEnabled = overrideEnabled;
        this.maxPerAgentUsd = maxPerAgentUsd == null ? BigDecimal.ZERO : maxPerAgentUsd;
    }

    /** Anyone who can view the agent can read its cap (404 otherwise, via requireAccess). */
    public AgentRunBudgetResponse get(UUID agentId) {
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return describe(agent.getRunBudgetUsd());
    }

    /**
     * Stores or clears (null) the agent's cap. 409 when the per-agent override is switched off,
     * 400 when the value is not positive, too precise, or above the platform ceiling; access and
     * the edit-lock are enforced by {@link AgentService#setRunBudgetUsd}.
     */
    public AgentRunBudgetResponse update(UUID agentId, BigDecimal requestedUsd) {
        // 404 first: an agent the caller cannot see (or that does not exist) never gets a 409 or
        // a 400 naming the platform cap. EDITOR is still enforced by setRunBudgetUsd below.
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        if (!overrideEnabled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Per-agent run caps are switched off on this deployment");
        }
        BigDecimal toStore = RunBudgetCaps.validateForStorage(requestedUsd, globalCapUsd, maxPerAgentUsd);
        agentService.setRunBudgetUsd(agentId, toStore);
        return describe(toStore);
    }

    AgentRunBudgetResponse describe(BigDecimal agentCapUsd) {
        BigDecimal effective = RunBudgetCaps.effectiveCap(globalCapUsd, agentCapUsd, overrideEnabled, maxPerAgentUsd);
        BigDecimal perBranch = new RunBudget(effective, inputUsdPerM, outputUsdPerM, avgBranchTokens)
            .estimateUsd(1).setScale(4, RoundingMode.HALF_UP);
        return new AgentRunBudgetResponse(
            overrideEnabled,
            globalCapUsd,
            agentCapUsd,
            effective,
            RunBudgetCaps.ceiling(globalCapUsd, maxPerAgentUsd),
            perBranch,
            SCOPE,
            true);
    }
}
