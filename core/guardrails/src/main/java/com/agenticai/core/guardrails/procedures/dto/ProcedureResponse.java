package com.agenticai.core.guardrails.procedures.dto;

import com.agenticai.core.guardrails.procedures.Procedure;
import com.agenticai.core.guardrails.procedures.ProcedureGuardKind;
import com.agenticai.core.guardrails.procedures.ProcedureRule;
import com.agenticai.core.guardrails.procedures.ProcedureStatus;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API view of a procedure with its rules fully resolved, in order. */
public record ProcedureResponse(
        UUID id,
        String name,
        String description,
        ProcedureStatus status,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        List<RuleView> rules) {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    public record RuleView(
            UUID id,
            int ruleOrder,
            String instruction,
            ProcedureGuardKind guardKind,
            String guardTool,
            JsonNode guardConfig) {}

    public static ProcedureResponse from(Procedure procedure, List<ProcedureRule> rules) {
        return new ProcedureResponse(
                procedure.getId(),
                procedure.getName(),
                procedure.getDescription(),
                procedure.getStatus(),
                procedure.getCreatedBy(),
                procedure.getCreatedAt(),
                procedure.getUpdatedAt(),
                rules.stream().map(ProcedureResponse::ruleView).toList());
    }

    private static RuleView ruleView(ProcedureRule rule) {
        JsonNode config = null;
        if (rule.getGuardConfig() != null) {
            try {
                config = MAPPER.readTree(rule.getGuardConfig());
            } catch (Exception e) {
                config = null;
            }
        }
        return new RuleView(
                rule.getId(),
                rule.getRuleOrder(),
                rule.getInstruction(),
                rule.getGuardKind(),
                rule.getGuardTool(),
                config);
    }
}
