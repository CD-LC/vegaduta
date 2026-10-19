package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantPlan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of {@code control_plane.plan_limits} (migration
 * {@code V101__plan_limits.sql}) - the data-driven replacement for the
 * hardcoded FREE_MAX_* / PREMIUM_MAX_* constants that used to live directly
 * in {@link TenantLimitsServiceImpl}. Exactly one row per {@link
 * TenantPlan} value, never per-tenant - see {@link PlanLimitsService},
 * which reads (and Redis-caches) these rows.
 *
 * <p>{@code -1} in any {@code max*} column means "no cap" - see {@link
 * PlanLimitsService.ResolvedPlanLimits} for where that sentinel is
 * translated into the {@code Integer.MAX_VALUE}/{@code Optional.empty()}
 * shape {@code com.agenticai.core.security.TenantLimits} actually exposes.
 *
 * <p>Read-only from the application's own runtime path in this PR (A2-PR1
 * tasks 1-3, 5); the platform-admin CRUD editor over this table
 * (A2-PR1 task 4) is {@code web-admin}-scoped and out of {@code
 * core-business}'s own scope per this repo's {@code CLAUDE.md} - flagged
 * in {@code AGENT-COORDINATION-2026-07-18.md} for whoever picks up {@code
 * web-admin} next, not built here. The package-private all-args
 * constructor below exists for that future editor's create/update path
 * (mirrors {@code PlatformHostedLlmProviderService#create}'s
 * {@code new PlatformHostedLlmProvider(...)} convention) and for this
 * class's own unit tests to build rows without a Hibernate session.
 */
@Entity
@Table(name = "plan_limits", schema = "control_plane")
public class PlanLimits {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "plan", length = 32)
    private TenantPlan plan;

    @Column(name = "max_users", nullable = false)
    private int maxUsers;

    @Column(name = "max_agents", nullable = false)
    private int maxAgents;

    @Column(name = "max_workflows", nullable = false)
    private int maxWorkflows;

    @Column(name = "max_kb_collections", nullable = false)
    private int maxKbCollections;

    @Column(name = "byok_allowed", nullable = false)
    private boolean byokAllowed;

    @Column(name = "whatsapp_allowed", nullable = false)
    private boolean whatsappAllowed;

    @Column(name = "white_label", nullable = false)
    private boolean whiteLabel;

    // Live Vision - V180. Gates the continuous WATCH/LIVE camera/
    // screen session modes only (VisionSessionService in core/agents);
    // SNAPSHOT frames ride the per-user frame quota, not this flag. FREE
    // seeds FALSE, every paid plan TRUE.
    @Column(name = "live_vision_allowed", nullable = false)
    private boolean liveVisionAllowed;

    // Freemium billing hardening - V470. external_channels gates the paid
    // third-party channels (Slack/Discord/Teams/X/Twilio/Meta social);
    // developer_api gates minting MCP access-grant keys (which authenticate
    // /api/dev/v1 + /mcp/**); sso gates BYO-IdP config; scim gates the
    // SCIM-lite provisioning surface; workflow_export gates portable-DSL
    // download. FREE seeds all FALSE; PREMIUM gets the first three; SCIM
    // and workflow export are UNLIMITED-only (see the migration).
    @Column(name = "external_channels_allowed", nullable = false)
    private boolean externalChannelsAllowed;

    @Column(name = "developer_api_allowed", nullable = false)
    private boolean developerApiAllowed;

    @Column(name = "sso_allowed", nullable = false)
    private boolean ssoAllowed;

    @Column(name = "scim_allowed", nullable = false)
    private boolean scimAllowed;

    @Column(name = "workflow_export_allowed", nullable = false)
    private boolean workflowExportAllowed;

    @Column(name = "automation_allowed", nullable = false)
    private boolean automationAllowed;

    protected PlanLimits() {
        // JPA
    }

    PlanLimits(
        TenantPlan plan,
        int maxUsers,
        int maxAgents,
        int maxWorkflows,
        int maxKbCollections,
        boolean byokAllowed,
        boolean whatsappAllowed,
        boolean whiteLabel,
        boolean liveVisionAllowed,
        boolean externalChannelsAllowed,
        boolean developerApiAllowed,
        boolean ssoAllowed,
        boolean scimAllowed,
        boolean workflowExportAllowed,
        boolean automationAllowed
    ) {
        this.plan = plan;
        this.maxUsers = maxUsers;
        this.maxAgents = maxAgents;
        this.maxWorkflows = maxWorkflows;
        this.maxKbCollections = maxKbCollections;
        this.byokAllowed = byokAllowed;
        this.whatsappAllowed = whatsappAllowed;
        this.whiteLabel = whiteLabel;
        this.liveVisionAllowed = liveVisionAllowed;
        this.externalChannelsAllowed = externalChannelsAllowed;
        this.developerApiAllowed = developerApiAllowed;
        this.ssoAllowed = ssoAllowed;
        this.scimAllowed = scimAllowed;
        this.workflowExportAllowed = workflowExportAllowed;
        this.automationAllowed = automationAllowed;
    }

    public TenantPlan getPlan() {
        return plan;
    }

    public int getMaxUsers() {
        return maxUsers;
    }

    public int getMaxAgents() {
        return maxAgents;
    }

    public int getMaxWorkflows() {
        return maxWorkflows;
    }

    public int getMaxKbCollections() {
        return maxKbCollections;
    }

    public boolean isByokAllowed() {
        return byokAllowed;
    }

    public boolean isWhatsappAllowed() {
        return whatsappAllowed;
    }

    public boolean isWhiteLabel() {
        return whiteLabel;
    }

    public boolean isLiveVisionAllowed() {
        return liveVisionAllowed;
    }

    public boolean isExternalChannelsAllowed() {
        return externalChannelsAllowed;
    }

    public boolean isDeveloperApiAllowed() {
        return developerApiAllowed;
    }

    public boolean isSsoAllowed() {
        return ssoAllowed;
    }

    public boolean isScimAllowed() {
        return scimAllowed;
    }

    public boolean isWorkflowExportAllowed() {
        return workflowExportAllowed;
    }

    public boolean isAutomationAllowed() {
        return automationAllowed;
    }
}
