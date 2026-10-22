package com.agenticai.core.agents.toolpolicy;

import com.agenticai.core.agents.toolpolicy.ToolArgPolicyDtos.PoliciesResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-agent tool ARGUMENT policies: {@code GET} reads them, {@code PUT} replaces them (an empty
 * {@code policies} list clears). Thin on purpose - access, validation, the feature flag and status
 * codes are decided in {@link ToolArgPolicyService}. The body is a JSON object with a {@code
 * policies} array, each entry naming a tool (or glob) and its {@code rules}; see {@code
 * ToolArgPolicySet} for the full grammar.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/tool-policies")
public class ToolArgPolicyController {

    private final ToolArgPolicyService service;

    public ToolArgPolicyController(ToolArgPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public PoliciesResponse get(@PathVariable UUID agentId) {
        return service.get(agentId);
    }

    @PutMapping
    public PoliciesResponse put(@PathVariable UUID agentId, @RequestBody JsonNode body) {
        return service.put(agentId, body);
    }
}
