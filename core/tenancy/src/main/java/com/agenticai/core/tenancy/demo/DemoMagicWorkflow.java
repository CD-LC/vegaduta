package com.agenticai.core.tenancy.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One "Magic Studio" workflow built by an anonymous sandbox visitor
 * (migration V132) - the persisted result of the landing page's
 * plan-a-workflow-from-a-sentence flow, and (once published) the backing row
 * of the public {@code /w/{shareId}} run page.
 *
 * <p>Control-plane, keyed to {@link DemoSession} with cascade delete - a
 * magic workflow is an artifact OF a sandbox and dies with it. See the V132
 * migration header for every design call (clear-text share id, TEXT JSON
 * payloads, the durable run_cap/runs_used half of the public run limits).
 */
@Entity
@Table(name = "demo_magic_workflow", schema = "control_plane")
public class DemoMagicWorkflow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    /** Public run-page handle - NULL until the owner publishes. Clear text on purpose (V131 precedent). */
    @Column(name = "share_id", length = 64)
    private String shareId;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    /** The classified intent wire-name (IMAGE, VIDEO, RESEARCH, WRITE, GENERAL). */
    @Column(name = "intent", nullable = false, length = 24)
    private String intent;

    /** The dynamically created workflow agent in the demo tenant's schema - nullable, best-effort. */
    @Column(name = "agent_id")
    private UUID agentId;

    /** The full behind-the-scenes plan payload (mermaid, agent prompt, model, tools) - opaque JSON. */
    @Column(name = "plan_json", nullable = false, columnDefinition = "text")
    private String planJson;

    /** The creator-facing input form the public run page renders - opaque JSON. */
    @Column(name = "form_spec_json", nullable = false, columnDefinition = "text")
    private String formSpecJson;

    @Column(name = "run_cap", nullable = false)
    private int runCap = 3;

    @Column(name = "runs_used", nullable = false)
    private int runsUsed;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected DemoMagicWorkflow() {
    }

    public DemoMagicWorkflow(
        UUID sessionId, String title, String intent, UUID agentId,
        String planJson, String formSpecJson, int runCap, Instant expiresAt
    ) {
        this.sessionId = sessionId;
        this.title = title;
        this.intent = intent;
        this.agentId = agentId;
        this.planJson = planJson;
        this.formSpecJson = formSpecJson;
        this.runCap = runCap;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getShareId() {
        return shareId;
    }

    public void setShareId(String shareId) {
        this.shareId = shareId;
    }

    public String getTitle() {
        return title;
    }

    public String getIntent() {
        return intent;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getPlanJson() {
        return planJson;
    }

    public String getFormSpecJson() {
        return formSpecJson;
    }

    public int getRunCap() {
        return runCap;
    }

    public int getRunsUsed() {
        return runsUsed;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
