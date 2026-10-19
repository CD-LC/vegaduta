package com.agenticai.core.guardrails.procedures;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One ordered step of a {@link Procedure}: a natural-language instruction
 * ("always verify the customer's identity before issuing a refund") plus an
 * OPTIONAL machine-checkable guard clause ({@link ProcedureGuardKind}) that
 * makes the rule deterministically enforced rather than advisory.
 *
 * <p>{@code guardTool} is the tool name the guard applies to for all three
 * kinds; {@code guardConfig} carries kind-specific extras (today only
 * {@code REQUIRE_TOOL_BEFORE}'s {@code "prior"} - the prerequisite tool
 * name). A rule with {@code guardKind == null} is prompt-only: injected,
 * not enforced - a legitimate choice for steps no deterministic check can
 * express, kept honest by the prompt block rendering those without an
 * "enforced" marker.
 */
@Entity
@Table(name = "procedure_rule")
public class ProcedureRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Plain FK value, not a {@code @ManyToOne} - rules are only ever loaded per-procedure via the repository. */
    @Column(name = "procedure_id", nullable = false)
    private UUID procedureId;

    @Column(name = "rule_order", nullable = false)
    private int ruleOrder;

    @Column(name = "instruction", nullable = false, columnDefinition = "TEXT")
    private String instruction;

    @Enumerated(EnumType.STRING)
    @Column(name = "guard_kind", length = 40)
    private ProcedureGuardKind guardKind;

    @Column(name = "guard_tool", length = 255)
    private String guardTool;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "guard_config", columnDefinition = "jsonb")
    private String guardConfig;

    protected ProcedureRule() {
        // JPA
    }

    public ProcedureRule(
        UUID procedureId, int ruleOrder, String instruction,
        ProcedureGuardKind guardKind, String guardTool, String guardConfig
    ) {
        this.procedureId = procedureId;
        this.ruleOrder = ruleOrder;
        this.instruction = instruction;
        this.guardKind = guardKind;
        this.guardTool = guardTool;
        this.guardConfig = guardConfig;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProcedureId() {
        return procedureId;
    }

    public int getRuleOrder() {
        return ruleOrder;
    }

    public String getInstruction() {
        return instruction;
    }

    public ProcedureGuardKind getGuardKind() {
        return guardKind;
    }

    public String getGuardTool() {
        return guardTool;
    }

    public String getGuardConfig() {
        return guardConfig;
    }
}
