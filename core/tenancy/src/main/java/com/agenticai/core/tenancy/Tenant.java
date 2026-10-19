package com.agenticai.core.tenancy;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.DataResidencyRegion;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * A tenant (business or individual account) - Phase 0 Stage 2.
 *
 * <p>Lives in the {@code control_plane} schema, never in a per-tenant schema (chicken-and-egg
 * reasons: this table is how the platform knows which per-tenant schema to route a request to in
 * the first place - see Agentic-AI-Platform-Microservices-Architecture.md Section 4).
 *
 * <p>{@code schemaName} is the Postgres schema this tenant's actual data (agents, providers,
 * knowledge, etc.) lives in - set at provisioning time (Stage 5) and never changed after.
 *
 * <p><b>{@code keycloakRealm} is deliberately NOT unique</b>, unlike the Stage 1 draft of this
 * entity assumed. The architecture doc's "Open design question" resolved toward a single shared
 * {@code agentic-ai} realm (Stage 4's follow-up work - tenant identity comes from a per-user {@code
 * tenant_id} attribute/claim, not a separate realm per tenant), so every {@code Tenant} row now
 * stores the same value here. The column is kept (rather than dropped) so a future Enterprise-plan
 * tenant with its own dedicated realm remains representable without a schema change - see migration
 * V8 for the constraint removal and why dropping the column entirely would have been the wrong
 * call.
 *
 * <p><b>{@code name} is unique</b> (migration V9, added alongside Stage 5's signup flow) - not just
 * an application-level {@code existsByName} check beforehand. Two concurrent signups racing for the
 * identical org name is exactly the kind of TOCTOU gap an app-level pre-check alone cannot close;
 * with this constraint, the losing concurrent insert fails fast, before either request has done
 * anything to Keycloak or created a Postgres schema - critically, before {@link
 * TenantProvisioningService}'s compensating-rollback path could ever be tempted to drop a schema
 * the *other*, successful request just created.
 */
@Entity
@EntityListeners(TenantPlanEvictionListener.class)
@Table(
        name = "tenant",
        schema = "control_plane",
        uniqueConstraints = {
            @UniqueConstraint(columnNames = "schema_name"),
            @UniqueConstraint(columnNames = "name"),
            @UniqueConstraint(columnNames = "referral_code")
        })
public class Tenant extends TenantPlanState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Optimistic lock (JAVA-007, migration controlplane/V365.1): every UPDATE is {@code WHERE id =
     * ? AND version = ?}, so a concurrent writer's save fails with {@code
     * ObjectOptimisticLockingFailureException} (HTTP 409) instead of silently overwriting the other
     * writer's columns. A wrapper type on purpose: {@code null} marks a never-persisted entity for
     * Spring Data.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(nullable = false, length = 255)
    private String name;


    @Column(name = "schema_name", nullable = false, length = 63)
    private String schemaName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TenantStatus status;

    @Column(name = "keycloak_realm", nullable = false, length = 255)
    private String keycloakRealm;

    // B2B/B2C/Family differentiation (migration V13) - asked explicitly at
    // signup, never inferred, and never changed after (a business that
    // outgrows B2C or a solo user misrouted into B2B contacts support for a
    // plan change rather than self-serve toggling it - the same "changed
    // deliberately, not casually" posture as plan upgrades elsewhere in this
    // app). See TenantLimits for what each value actually enforces.
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 16)
    private AccountType accountType;

    // Tenant-wide default for "does this tenant want to configure MCP/tools/
    // knowledge by hand, or let the automatic-selection engine handle it?"
    // (migration V13) - every new agent inherits this unless explicitly
    // overridden per agent at creation time (Agent.experienceMode). Changeable
    // after signup via TenantSettingsController, unlike accountType above.
    @Enumerated(EnumType.STRING)
    @Column(name = "experience_mode", nullable = false, length = 16)
    private ExperienceMode experienceMode;

    // Direct feedback (issue report): "every non free tenant should get an
    // option to select use platform llm" - migration V97. A PREMIUM/UNLIMITED
    // tenant's own opt-in to route chat through the platform-hosted pool
    // (same pool FREE tenants always use, see AgentChatService
    // #buildChatClientFor) instead of their BYOK credential. Defaults false
    // (unset) - BYOK stays the default for a paying tenant, same "nothing
    // changes unless the tenant deliberately asks for it" posture
    // experienceMode above uses for its own settings-page toggle. Read via
    // TenantLimits#usePlatformLlm, written via TenantSettingsController.
    @Column(name = "use_platform_llm", nullable = false)
    private boolean usePlatformLlm = false;

    // V236: true once the tenant admin has explicitly picked a source on the
    // settings toggle. Without it, usePlatformLlm=false cannot tell "never
    // chose" from "chose BYOK" - see TenantLimitsServiceImpl#usesPlatformPool.
    @Column(name = "llm_source_explicit", nullable = false)
    private boolean llmSourceExplicit = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;


    // #815 Governance follow-up (Fail-safe: platform + per-tenant kill
    // switch) - a platform-admin incident-response action, deliberately
    // separate from TenantStatus (that enum's values - PROVISIONING/ACTIVE/
    // SUSPENDED/PROVISIONING_FAILED - describe the provisioning LIFECYCLE
    // and nothing today ever actually sets SUSPENDED; conflating "billing
    // suspended" with "platform-admin hit the emergency stop button" would
    // make both harder to reason about later). Enforced by {@code
    // TenantStatusGuardImpl#isUsable} (same chokepoint TenantStatus already
    // uses) - see that class's javadoc for why this one check protects both
    // the JWT-authenticated web console/API path AND, via {@code
    // AgentChatService#chat}'s own explicit call, every omnichannel-webhook
    // path that never goes through the JWT filter at all.
    @Column(name = "emergency_stopped", nullable = false)
    private boolean emergencyStopped;

    @Column(name = "emergency_stop_reason", length = 500)
    private String emergencyStopReason;

    @Column(name = "emergency_stopped_at")
    private Instant emergencyStoppedAt;

    // Master Roadmap 2026 item #22 (GDPR/data-residency basics, Â§G) -
    // migration V96. See DataResidencyRegion's javadoc (core/security) for
    // why this is declared-intent/admin-labeling only, not an enforced
    // routing mechanism, and why it defaults to null (UNSPECIFIED) for
    // every tenant rather than this migration backfilling a claim about
    // actual hosting location on anyone's behalf.
    @Enumerated(EnumType.STRING)
    @Column(name = "data_residency_region", length = 32)
    private DataResidencyRegion dataResidencyRegion;

    // Master Roadmap 2026 item #22 - set once by TenantDataErasureService
    // and never again; see TenantStatus.ERASED's javadoc for why these two
    // columns (not the normal per-tenant AuditLogService) are the durable
    // record that an erasure happened.
    @Column(name = "erased_at")
    private Instant erasedAt;

    @Column(name = "erased_by", length = 255)
    private String erasedBy;


    // White-label minimum-real build (migration V162, PRICING-MONETIZATION-
    // AUDIT-2026-07-31.md item C2) - the tenant's own brand identity for
    // public share surfaces. Written via TenantSettingsController, which
    // gates the WRITE on the plan's white_label entitlement; the public READ
    // goes through TenantLimits#brandDisplayName/#brandLogoUrl, which only
    // report a value while white_label is still true - so a downgrade
    // automatically restores VegaDuta branding with these columns untouched
    // (and an UNLIMITED re-upgrade gets them back). Both null means "not
    // set": platform branding renders as before.
    @Column(name = "brand_display_name", length = 64)
    private String brandDisplayName;

    @Column(name = "brand_logo_url", length = 512)
    private String brandLogoUrl;

    // Signup consent (migration V123) - the OPTIONAL "Send me product
    // updates & news about VegaDuta" checkbox on the signup wizard's
    // DetailsForm, captured once at signup time and never surfaced by any
    // settings-page toggle today. Defaults false (opt-out), matching the
    // checkbox's own default-unchecked state. The paired REQUIRED
    // Terms-of-Service/Privacy-Policy agreement on that same screen is
    // UI-gating only (blocks submission client-side) and is deliberately
    // NOT persisted anywhere, including here.
    @Column(name = "news_opt_in", nullable = false)
    private boolean newsOptIn = false;

    // "AI Model Control" settings page backend (migration V185) - a generic
    // bag of per-feature model overrides, opaque TEXT (JSON) here exactly
    // like brandDisplayName/brandLogoUrl above are plain columns rather than
    // a key-value store, except this one field covers an open-ended set of
    // features instead of a fixed pair - see V185's header comment for why.
    // NEVER read/written directly outside {@code TenantAiModelPreferenceService}
    // (core/tenancy), which owns parsing/validating/serializing the
    // {featureKey: {providerName, model}} shape; this class only stores the
    // raw string, the same "entity doesn't know its own column is JSON"
    // posture {@code PlatformSetting#value} has for platform-wide settings.
    // Null (every tenant until this feature is used) means no overrides.
    @Column(name = "ai_model_preferences", columnDefinition = "TEXT")
    private String aiModelPreferences;

    protected Tenant() {
        // JPA
    }

    public Tenant(
            String name,
            TenantPlan plan,
            String schemaName,
            String keycloakRealm,
            AccountType accountType,
            ExperienceMode experienceMode) {
        super(plan);
        this.name = name;
        this.schemaName = schemaName;
        this.keycloakRealm = keycloakRealm;
        this.accountType = accountType;
        this.experienceMode = experienceMode;
        this.status = TenantStatus.PROVISIONING;
        this.createdAt = Instant.now();
    }

    public void markActive() {
        this.status = TenantStatus.ACTIVE;
    }


    /** Settings-page change, not a signup-time decision - see {@code TenantSettingsController}. */
    public void setExperienceMode(ExperienceMode experienceMode) {
        this.experienceMode = experienceMode;
    }

    /** Settings-page opt-in - see this field's own javadoc and {@code TenantSettingsController}. */
    public void setUsePlatformLlm(boolean usePlatformLlm) {
        this.usePlatformLlm = usePlatformLlm;
        this.llmSourceExplicit = true;
    }

    /**
     * Signup-time only - see this field's own javadoc; set once by {@code
     * TenantProvisioningRecorder#createProvisioningTenant}.
     */
    public void setNewsOptIn(boolean newsOptIn) {
        this.newsOptIn = newsOptIn;
    }

    /**
     * White-label branding (V162) - written only by {@code TenantSettingsController}, which gates
     * the write on the plan's {@code white_label} entitlement and validates both values (name
     * length, https-only logo URL) before this is ever called. {@code null} clears a value back to
     * platform branding.
     */
    public void updateBranding(String brandDisplayName, String brandLogoUrl) {
        this.brandDisplayName = brandDisplayName;
        this.brandLogoUrl = brandLogoUrl;
    }

    /**
     * "AI Model Control" settings page (V185) - overwrites the WHOLE stored JSON bag. Written only
     * by {@code TenantAiModelPreferenceService}, which reads the current value, merges/clears the
     * one feature key a given API call targets, re-serializes, and calls this with the result - so
     * from this class's perspective every write is a full replace, same as {@link #updateBranding}.
     * {@code null} clears every override back to "resolve as if nothing were configured."
     */
    public void updateAiModelPreferences(String aiModelPreferencesJson) {
        this.aiModelPreferences = aiModelPreferencesJson;
    }

    public String getAiModelPreferences() {
        return aiModelPreferences;
    }

    public void markProvisioningFailed() {
        this.status = TenantStatus.PROVISIONING_FAILED;
    }


    /**
     * #815 Governance follow-up - platform-admin incident-response action. See this class's
     * field-level comment above.
     */
    public void emergencyStop(String reason) {
        this.emergencyStopped = true;
        this.emergencyStopReason = reason;
        this.emergencyStoppedAt = Instant.now();
    }

    public void emergencyResume() {
        this.emergencyStopped = false;
        this.emergencyStopReason = null;
        this.emergencyStoppedAt = null;
    }

    public boolean isEmergencyStopped() {
        return emergencyStopped;
    }

    public String getEmergencyStopReason() {
        return emergencyStopReason;
    }

    public Instant getEmergencyStoppedAt() {
        return emergencyStoppedAt;
    }

    /**
     * Master Roadmap 2026 item #22 - a platform admin declaring (or clearing, via {@code null})
     * this tenant's intended data-residency jurisdiction. See {@link DataResidencyRegion}'s javadoc
     * for what this does and - just as important - does not do.
     */
    public void declareDataResidencyRegion(DataResidencyRegion dataResidencyRegion) {
        this.dataResidencyRegion = dataResidencyRegion;
    }

    public DataResidencyRegion getDataResidencyRegion() {
        return dataResidencyRegion;
    }

    /**
     * Master Roadmap 2026 item #22 - called only from {@code TenantDataErasureService}, once every
     * Keycloak user for this tenant has been deleted and its Postgres schema dropped. See {@link
     * TenantStatus#ERASED}'s javadoc for why this row is kept (not deleted) and why {@code
     * erasedAt}/{@code erasedBy} - not the normal per-tenant audit log - are this action's durable
     * record.
     */
    public void markErased(String erasedBy) {
        this.status = TenantStatus.ERASED;
        this.erasedAt = Instant.now();
        this.erasedBy = erasedBy;
    }

    /**
     * Erasure completeness (2026-10-09) - called alongside {@link #markErased}. Clears the
     * free-text and preference columns that describe the organisation or the people in it beyond
     * what the terminal record needs: brand name/logo, referral code, marketing opt-in, the
     * operator's comp-grant and emergency-stop notes (both can quote a person), and the per-feature
     * model preferences.
     *
     * <p>Deliberately KEPT: {@code name} (the deletion log and any issued {@code billing_invoice}
     * rows reference it, and it is the only thing an operator can recognise an erased row by),
     * plan/status, and every gateway id/subscription field - financial-record retention is its own
     * legal basis, separate from the erasure request (see this class's {@link TenantStatus#ERASED}
     * reasoning). Billing name/address/GSTIN are not on this row at all; they live on {@code
     * billing_invoice}, which is retained for the same reason. Only nullable columns (and the
     * {@code news_opt_in} boolean) are touched, so no NOT NULL is violated.
     */
    public void scrubPersonalData() {
        this.brandDisplayName = null;
        this.brandLogoUrl = null;
        scrubPlanPersonalData();
        this.newsOptIn = false;
        this.emergencyStopReason = null;
        this.aiModelPreferences = null;
    }

    public Instant getErasedAt() {
        return erasedAt;
    }

    public String getErasedBy() {
        return erasedBy;
    }


    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }


    public String getSchemaName() {
        return schemaName;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public String getKeycloakRealm() {
        return keycloakRealm;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public ExperienceMode getExperienceMode() {
        return experienceMode;
    }

    public boolean isUsePlatformLlm() {
        return usePlatformLlm;
    }

    public boolean isLlmSourceExplicit() {
        return llmSourceExplicit;
    }

    public boolean isNewsOptIn() {
        return newsOptIn;
    }

    public String getBrandDisplayName() {
        return brandDisplayName;
    }

    public String getBrandLogoUrl() {
        return brandLogoUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }


    /** Optimistic-lock version; {@code null} until first persisted. */
    public Long getVersion() {
        return version;
    }
}
