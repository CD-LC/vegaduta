package com.agenticai.core.agents.runbudget;

import com.agenticai.core.agents.dto.AgentRunBudgetResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-agent run cap: read the effective cap + estimate, and let whoever may edit the agent lower
 * it. Thin on purpose - access, validation and status codes are decided in {@link
 * AgentRunBudgetService}.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/run-budget")
public class AgentRunBudgetController {

    static final String FIELD = "runBudgetUsd";

    private final AgentRunBudgetService runBudgetService;

    public AgentRunBudgetController(AgentRunBudgetService runBudgetService) {
        this.runBudgetService = runBudgetService;
    }

    @GetMapping
    public AgentRunBudgetResponse get(@PathVariable UUID agentId) {
        return runBudgetService.get(agentId);
    }

    /**
     * Body: {@code {"runBudgetUsd": 1.5}} to set, {@code {"runBudgetUsd": null}} to clear. The
     * field must be PRESENT: a missing body, an empty object or a misspelled field is a 400, so a
     * client typo can never silently remove someone's spending cap.
     */
    @PatchMapping
    public AgentRunBudgetResponse update(@PathVariable UUID agentId, @RequestBody JsonNode body) {
        return runBudgetService.update(agentId, parseRunBudgetUsd(body));
    }

    /**
     * Pure: null = clear. @throws IllegalArgumentException (400) when the field is absent or not a
     * number.
     */
    static BigDecimal parseRunBudgetUsd(JsonNode body) {
        if (body == null || !body.isObject() || !body.has(FIELD)) {
            throw new IllegalArgumentException(
                    "Request body must contain " + FIELD + " (a number, or null to clear the cap)");
        }
        JsonNode value = body.get(FIELD);
        if (value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        if (value.isTextual()) {
            try {
                return new BigDecimal(value.asText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        FIELD + " must be a number, or null to clear the cap");
            }
        }
        throw new IllegalArgumentException(FIELD + " must be a number, or null to clear the cap");
    }
}
