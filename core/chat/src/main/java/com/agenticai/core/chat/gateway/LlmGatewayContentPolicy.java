package com.agenticai.core.chat.gateway;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Per-tenant on/off configuration for {@link LlmGatewayContentPolicyAdvisor}
 * (V300 migration, {@code control_plane.llm_gateway_content_policy}) - one
 * row per tenant that has explicitly saved a policy. Rows are only ever
 * written by {@link LlmGatewayContentPolicyService#upsert}, whose PUT body
 * requires every field, so a stored row is always a deliberate choice.
 *
 * <p><b>Defaults for a tenant with no row</b> ({@link #unconfiguredDefault},
 * owner decision 2026-09-28): prompt-injection screening is <b>ON</b> in
 * {@link LlmGatewayContentPolicyAction#FLAG} mode - a suspected injection is
 * audit-logged and the call proceeds unchanged, so nothing is blocked or
 * rewritten for such a tenant; the only new side effect is the audit row.
 * PII redaction stays <b>OFF</b> (opt-in): it rewrites what the model sees,
 * which is a per-tenant product decision, not a safe global default. A
 * tenant that saved a row with screening off keeps it off - stored rows are
 * never reinterpreted, which is why no data migration accompanies this
 * default (the V300 column {@code DEFAULT FALSE} is never exercised: upsert
 * always writes the field explicitly).
 *
 * <p>Two independent master switches, each with its own sub-toggles:
 * <ul>
 *   <li>{@code piiRedactionEnabled} - outbound prompt PII redaction, gated
 *       further by four per-category booleans (email/phone/government-id/
 *       credit-card), see {@link LlmGatewayPiiCategory}.</li>
 *   <li>{@code injectionScreeningEnabled} - inbound prompt-injection
 *       screening, with {@code injectionScreeningAction} choosing between
 *       {@link LlmGatewayContentPolicyAction#FLAG} (audit-log only, default)
 *       and {@link LlmGatewayContentPolicyAction#BLOCK} (reject the call).</li>
 * </ul>
 */
@Entity
@Table(name = "llm_gateway_content_policy", schema = "control_plane")
public class LlmGatewayContentPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, unique = true, length = 64)
    private String tenantId;

    @Column(name = "pii_redaction_enabled", nullable = false)
    private boolean piiRedactionEnabled;

    @Column(name = "pii_redact_email", nullable = false)
    private boolean piiRedactEmail = true;

    @Column(name = "pii_redact_phone", nullable = false)
    private boolean piiRedactPhone = true;

    @Column(name = "pii_redact_government_id", nullable = false)
    private boolean piiRedactGovernmentId = true;

    @Column(name = "pii_redact_credit_card", nullable = false)
    private boolean piiRedactCreditCard = true;

    // Java-side default matches unconfiguredDefault (ON) so a freshly
    // constructed row never silently reads as "screening off".
    @Column(name = "injection_screening_enabled", nullable = false)
    private boolean injectionScreeningEnabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "injection_screening_action", nullable = false, length = 16)
    private LlmGatewayContentPolicyAction injectionScreeningAction = LlmGatewayContentPolicyAction.FLAG;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LlmGatewayContentPolicy() {
        // JPA
    }

    public LlmGatewayContentPolicy(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * The in-memory, never-persisted row {@link LlmGatewayContentPolicyService#resolve}
     * returns for a tenant with no stored row and as the fallback on a lookup
     * error: injection screening ON in {@link LlmGatewayContentPolicyAction#FLAG}
     * mode, PII redaction OFF. FLAG never blocks or alters the request, so a
     * tenant on this default sees the same model input and output as before;
     * the only difference is an audit row when a message matches.
     */
    static LlmGatewayContentPolicy unconfiguredDefault(String tenantId) {
        LlmGatewayContentPolicy defaults = new LlmGatewayContentPolicy(tenantId);
        defaults.piiRedactionEnabled = false;
        defaults.injectionScreeningEnabled = true;
        defaults.injectionScreeningAction = LlmGatewayContentPolicyAction.FLAG;
        return defaults;
    }

    /**
     * Both checks OFF - what {@link LlmGatewayContentPolicyService#resolve}
     * returns for a {@code null} tenant (an internal/system call with no
     * tenant context, where there is no tenant to audit against). A real
     * tenant only reaches this posture by explicitly saving a row with both
     * master switches off.
     */
    static LlmGatewayContentPolicy disabledDefault(String tenantId) {
        LlmGatewayContentPolicy defaults = new LlmGatewayContentPolicy(tenantId);
        defaults.piiRedactionEnabled = false;
        defaults.injectionScreeningEnabled = false;
        return defaults;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public boolean isPiiRedactionEnabled() {
        return piiRedactionEnabled;
    }

    public void setPiiRedactionEnabled(boolean piiRedactionEnabled) {
        this.piiRedactionEnabled = piiRedactionEnabled;
    }

    public boolean isInjectionScreeningEnabled() {
        return injectionScreeningEnabled;
    }

    public void setInjectionScreeningEnabled(boolean injectionScreeningEnabled) {
        this.injectionScreeningEnabled = injectionScreeningEnabled;
    }

    public LlmGatewayContentPolicyAction getInjectionScreeningAction() {
        return injectionScreeningAction;
    }

    public void setInjectionScreeningAction(LlmGatewayContentPolicyAction injectionScreeningAction) {
        this.injectionScreeningAction = injectionScreeningAction;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isPiiRedactEmail() {
        return piiRedactEmail;
    }

    public boolean isPiiRedactPhone() {
        return piiRedactPhone;
    }

    public boolean isPiiRedactGovernmentId() {
        return piiRedactGovernmentId;
    }

    public boolean isPiiRedactCreditCard() {
        return piiRedactCreditCard;
    }

    public void setPiiRedactEmail(boolean piiRedactEmail) {
        this.piiRedactEmail = piiRedactEmail;
    }

    public void setPiiRedactPhone(boolean piiRedactPhone) {
        this.piiRedactPhone = piiRedactPhone;
    }

    public void setPiiRedactGovernmentId(boolean piiRedactGovernmentId) {
        this.piiRedactGovernmentId = piiRedactGovernmentId;
    }

    public void setPiiRedactCreditCard(boolean piiRedactCreditCard) {
        this.piiRedactCreditCard = piiRedactCreditCard;
    }

    /**
     * The categories {@link LlmGatewayPiiRedactor} should actually redact for
     * this tenant - empty when {@link #piiRedactionEnabled} is off, so a
     * caller never needs to check the master switch separately.
     */
    public Set<LlmGatewayPiiCategory> enabledPiiCategories() {
        if (!piiRedactionEnabled) {
            return Set.of();
        }
        java.util.EnumSet<LlmGatewayPiiCategory> categories = java.util.EnumSet.noneOf(LlmGatewayPiiCategory.class);
        if (piiRedactEmail) {
            categories.add(LlmGatewayPiiCategory.EMAIL);
        }
        if (piiRedactPhone) {
            categories.add(LlmGatewayPiiCategory.PHONE);
        }
        if (piiRedactGovernmentId) {
            categories.add(LlmGatewayPiiCategory.GOVERNMENT_ID);
        }
        if (piiRedactCreditCard) {
            categories.add(LlmGatewayPiiCategory.CREDIT_CARD);
        }
        return categories;
    }
}
