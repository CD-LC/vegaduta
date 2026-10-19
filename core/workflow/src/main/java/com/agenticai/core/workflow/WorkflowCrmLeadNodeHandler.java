package com.agenticai.core.workflow;

import com.agenticai.core.omnichannel.crm.Lead;
import com.agenticai.core.omnichannel.crm.LeadActivityType;
import com.agenticai.core.omnichannel.crm.LeadService;
import com.agenticai.core.omnichannel.crm.LeadStage;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * "CRM Lead" node ({@code crm_lead}) - upsert a lead, move it to a stage, or
 * add a timeline activity in the built-in Leads CRM (V337).
 *
 * <p>Config: {@code action} = {@code upsert} (default) | {@code stage} |
 * {@code activity}. Every text field supports {@code {{input}}} (the raw
 * upstream text) and {@code {{key}}} (a top-level field of the upstream
 * output when it is a JSON object). {@code stage}/{@code activity} locate the
 * lead by {@code leadId} or, failing that, {@code email}. Output is a small
 * JSON object describing the lead so downstream nodes can reference
 * {@code {{leadId}}} / {@code {{stage}}}.
 */
@Component
class WorkflowCrmLeadNodeHandler {

    private final ObjectProvider<LeadService> leadService;
    private final ObjectMapper objectMapper;
    private final WorkflowExpressionResolver expressions;

    WorkflowCrmLeadNodeHandler(ObjectProvider<LeadService> leadService, ObjectMapper objectMapper) {
        this.leadService = leadService;
        this.objectMapper = objectMapper;
        this.expressions = WorkflowExpressionResolver.standalone(objectMapper);
    }

    /**
     * Standalone entry (direct callers and tests): resolves the node's placeholders with the shared
     * {@link WorkflowExpressionResolver}, then runs it. {@link WorkflowNodeExecutor} has already resolved
     * them and calls {@link #executeResolved} instead, so nothing is ever evaluated twice.
     */
    NodeExecutionResult execute(WorkflowNodeDto node, String input) {
        return executeResolved(expressions.resolve(node, input, null, null), input);
    }

    NodeExecutionResult executeResolved(WorkflowNodeDto node, String input) {
        LeadService service = leadService.getIfAvailable();
        if (service == null) {
            return NodeExecutionResult.skipped(input, "CRM lead node unavailable in this deployment - input passed through unchanged.");
        }
        Map<String, Object> config = node.config();
        JsonNode inputJson = parseJson(input);
        String action = str(config, "action");
        action = action == null ? "upsert" : action.toLowerCase(java.util.Locale.ROOT);
        try {
            return switch (action) {
                case "upsert" -> upsert(service, config, input, inputJson);
                case "stage" -> changeStage(service, config, input, inputJson);
                case "activity" -> addActivity(service, config, input, inputJson);
                default -> NodeExecutionResult.skipped(input, "Unknown CRM action '" + action + "' - use upsert, stage or activity.");
            };
        } catch (ResponseStatusException e) {
            return NodeExecutionResult.skipped(input, "CRM " + action + " failed: " + e.getReason());
        } catch (IllegalArgumentException e) {
            return NodeExecutionResult.skipped(input, "CRM " + action + " failed: " + e.getMessage());
        }
    }

    private NodeExecutionResult upsert(LeadService service, Map<String, Object> c, String input, JsonNode json) {
        String scoreRaw = field(c, "score", input, json);
        String valueRaw = field(c, "value", input, json);
        String tagsRaw = field(c, "tags", input, json);
        LeadService.LeadInput in = new LeadService.LeadInput(
            field(c, "name", input, json), field(c, "email", input, json), field(c, "phone", input, json),
            field(c, "company", input, json), field(c, "title", input, json), field(c, "source", input, json),
            LeadStage.parse(field(c, "stage", input, json)),
            scoreRaw == null ? null : Integer.valueOf(scoreRaw.trim()),
            field(c, "owner", input, json),
            valueRaw == null ? null : new BigDecimal(valueRaw.replaceAll("[,$\\s]", "")),
            tagsRaw == null ? null : List.of(tagsRaw.split("[,;|]")),
            field(c, "notes", input, json)
        );
        Lead lead = service.upsert(in);
        return NodeExecutionResult.ok(describe(lead), "Lead saved: " + label(lead) + " (" + lead.getStage() + ")", null);
    }

    private NodeExecutionResult changeStage(LeadService service, Map<String, Object> c, String input, JsonNode json) {
        LeadStage stage = LeadStage.parse(field(c, "stage", input, json));
        if (stage == null) {
            return NodeExecutionResult.skipped(input, "CRM stage action needs a 'stage'.");
        }
        Optional<UUID> id = resolveLeadId(service, c, input, json);
        if (id.isEmpty()) {
            return NodeExecutionResult.skipped(input, "No lead found for the given leadId/email - nothing moved.");
        }
        Lead lead = service.changeStage(id.get(), stage, field(c, "note", input, json));
        return NodeExecutionResult.ok(describe(lead), "Lead " + label(lead) + " moved to " + stage, null);
    }

    private NodeExecutionResult addActivity(LeadService service, Map<String, Object> c, String input, JsonNode json) {
        String typeRaw = field(c, "activityType", input, json);
        LeadActivityType type = typeRaw == null ? LeadActivityType.NOTE : LeadActivityType.parse(typeRaw);
        Optional<UUID> id = resolveLeadId(service, c, input, json);
        if (id.isEmpty()) {
            return NodeExecutionResult.skipped(input, "No lead found for the given leadId/email - activity not recorded.");
        }
        String body = field(c, "activityBody", input, json);
        service.addActivity(id.get(), type, body == null ? input : body, Map.of("source", "workflow"));
        Lead lead = service.get(id.get());
        return NodeExecutionResult.ok(describe(lead), "Recorded " + type + " on " + label(lead), null);
    }

    private Optional<UUID> resolveLeadId(LeadService service, Map<String, Object> c, String input, JsonNode json) {
        String leadId = field(c, "leadId", input, json);
        if (leadId != null) {
            try {
                return Optional.of(UUID.fromString(leadId.trim()));
            } catch (IllegalArgumentException ignored) {
                // fall through to email lookup
            }
        }
        String email = field(c, "email", input, json);
        if (email == null) {
            return Optional.empty();
        }
        return service.findByEmail(email).map(Lead::getId);
    }

    private String describe(Lead lead) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("leadId", String.valueOf(lead.getId()));
        out.put("email", lead.getEmail());
        out.put("name", lead.getName());
        out.put("stage", lead.getStage().name());
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String label(Lead lead) {
        return lead.getName() != null ? lead.getName() : String.valueOf(lead.getEmail());
    }

    private static String str(Map<String, Object> c, String key) {
        Object raw = c.get(key);
        return raw == null || raw.toString().isBlank() ? null : raw.toString();
    }

    private String field(Map<String, Object> c, String key, String input, JsonNode json) {
        // Already resolved by WorkflowExpressionResolver (missing/null keys render as empty text for this node).
        String rendered = str(c, key);
        return rendered == null || rendered.isBlank() ? null : rendered.trim();
    }

    private JsonNode parseJson(String input) {
        if (input == null || !input.trim().startsWith("{")) {
            return null;
        }
        try {
            return objectMapper.readTree(input);
        } catch (Exception e) {
            return null;
        }
    }
}
