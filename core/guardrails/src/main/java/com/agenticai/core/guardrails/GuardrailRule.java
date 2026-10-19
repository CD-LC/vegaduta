package com.agenticai.core.guardrails;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A pattern-based guardrail rule visible to a tenant: either a platform-seeded
 * {@link GuardrailRuleType#DEFAULT_TEMPLATE} (read-only safety-net preset,
 * every tenant gets these regardless of plan) or a tenant-authored {@link
 * GuardrailRuleType#CUSTOM} rule (authoring is a PREMIUM/UNLIMITED feature -
 * see {@link GuardrailRuleService}).
 *
 * <p><b>Tenant-schema entity</b> (no {@code schema=} on {@code @Table}, same
 * convention as {@code ModerationReviewItem} and every other per-tenant
 * table): it lives in the calling tenant's own Postgres schema, resolved
 * automatically by the platform's existing Hibernate multi-tenancy resolver.
 * Because of this, {@code DEFAULT_TEMPLATE} rows don't need to be "shared"
 * across tenants at the query level - the Flyway seed data in {@code
 * V145__guardrail_rule.sql} inserts the same preset rows into every tenant
 * schema, so a plain "all rows in this schema" query already returns that
 * tenant's defaults plus its own custom rules with no extra filtering.
 *
 * <p>{@code tenantId} is therefore redundant with the schema for isolation
 * purposes - kept as an explicit column purely for cross-schema operator
 * tooling and audit correlation, exactly the same rationale {@code
 * ModerationReviewItem#tenantId}'s javadoc documents. It is {@code null} on
 * {@code DEFAULT_TEMPLATE} rows (nobody "authored" a preset) and always set
 * to the authoring tenant on {@code CUSTOM} rows.
 *
 * <p>Persisted via migration V145 ({@code guardrail_rule}, {@code
 * db/migration/tenant}).
 */
@Entity
@Table(name = "guardrail_rule")
public class GuardrailRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", length = 255)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 32)
    private GuardrailRuleType ruleType;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Regex or keyword pattern checked against tool/content text - same shape as {@code PatternBasedGuardrailService}'s compiled patterns. */
    @Column(name = "pattern", nullable = false, columnDefinition = "TEXT")
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private GuardrailRuleAction action;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GuardrailRule() {
        // JPA
    }

    /** Constructs a new tenant-authored {@link GuardrailRuleType#CUSTOM} rule - see {@link GuardrailRuleService#createCustom}. */
    public GuardrailRule(
        String tenantId,
        GuardrailRuleType ruleType,
        String name,
        String description,
        String pattern,
        GuardrailRuleAction action,
        boolean enabled
    ) {
        this.tenantId = tenantId;
        this.ruleType = ruleType;
        this.name = name;
        this.description = description;
        this.pattern = pattern;
        this.action = action;
        this.enabled = enabled;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Applies an editor's changes - callers must have already verified this rule is {@link GuardrailRuleType#CUSTOM} and the tenant is plan-eligible. */
    public void applyEdit(String name, String description, String pattern, GuardrailRuleAction action, Boolean enabled) {
        this.name = name;
        this.description = description;
        this.pattern = pattern;
        this.action = action;
        if (enabled != null) {
            this.enabled = enabled;
        }
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public GuardrailRuleType getRuleType() {
        return ruleType;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getPattern() {
        return pattern;
    }

    public GuardrailRuleAction getAction() {
        return action;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
