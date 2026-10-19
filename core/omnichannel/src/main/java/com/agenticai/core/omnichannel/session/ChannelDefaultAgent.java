package com.agenticai.core.omnichannel.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Which agent handles inbound messages for a given {@link ChannelType},
 * for the currently-active tenant. Deliberately a plain entity with no
 * schema override - the same mechanism that makes {@code Agent} and {@code
 * TwilioCredential} automatically per-tenant (Phase 0 Stage 4) applies here
 * too, so "every tenant configures their own default WhatsApp agent
 * independently" falls out of schema-per-tenant multi-tenancy for free,
 * the same way it already did for BYOK credentials.
 */
@Entity
@Table(name = "channel_default_agent", uniqueConstraints = @UniqueConstraint(columnNames = "channel"))
public class ChannelDefaultAgent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChannelType channel;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /**
     * Tenant's own "does every inbound request need manual mapping" switch
     * (migration V238, /admin/contacts). FALSE (default) = today's behaviour:
     * a never-seen sender lands in the pending-pairing queue until an admin
     * approves. TRUE = a new sender on this channel starts paired straight
     * away on this default agent. Known contacts (channel_contact rows)
     * always auto-pair regardless of this flag - see {@code
     * ChannelSessionService#resolveOrCreate}.
     */
    @Column(name = "auto_approve_new_senders", nullable = false)
    private boolean autoApproveNewSenders;

    /**
     * Inbound safety-scan mode for this channel (migration V242): what
     * {@code InboundSafetyScanService} does when the jailbreak/injection
     * detector flags an inbound message on this channel. {@code OFF} skips
     * the scan; {@code FLAG} (default) lets the agent answer but escalates
     * the session into the operator inbox; {@code HOLD} suppresses the
     * agent entirely (via a {@code system:safety-scan} handoff hold) until
     * a human releases the conversation.
     */
    @Column(name = "inbound_safety_mode", nullable = false, length = 16)
    private String inboundSafetyMode = "FLAG";

    /** WhatsApp only: the task the tenant declared for the bound agent (V492). */
    @Column(name = "declared_task", length = 32)
    private String declaredTask;

    @Column(name = "declared_task_at")
    private Instant declaredTaskAt;

    @Column(name = "declared_task_by")
    private String declaredTaskBy;

    /** The tenant proceeded past the open-ended-agent warning (V492). */
    @Column(name = "open_ended_override", nullable = false)
    private boolean openEndedOverride;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChannelDefaultAgent() {
        // JPA
    }

    public ChannelDefaultAgent(ChannelType channel, UUID agentId) {
        this.channel = channel;
        this.agentId = agentId;
        this.autoApproveNewSenders = false;
        this.updatedAt = Instant.now();
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
        this.updatedAt = Instant.now();
    }

    public void setAutoApproveNewSenders(boolean autoApproveNewSenders) {
        this.autoApproveNewSenders = autoApproveNewSenders;
        this.updatedAt = Instant.now();
    }

    public void declareTask(String task, String by, boolean openEndedOverride) {
        this.declaredTask = task;
        this.declaredTaskBy = by;
        this.declaredTaskAt = Instant.now();
        this.openEndedOverride = openEndedOverride;
        this.updatedAt = Instant.now();
    }

    public String getDeclaredTask() {
        return declaredTask;
    }

    public Instant getDeclaredTaskAt() {
        return declaredTaskAt;
    }

    public String getDeclaredTaskBy() {
        return declaredTaskBy;
    }

    public boolean isOpenEndedOverride() {
        return openEndedOverride;
    }

    public void setInboundSafetyMode(String inboundSafetyMode) {
        this.inboundSafetyMode = InboundSafetyMode.parse(inboundSafetyMode);
        this.updatedAt = Instant.now();
    }

    public String getInboundSafetyMode() {
        return inboundSafetyMode;
    }

    public UUID getId() {
        return id;
    }

    public ChannelType getChannel() {
        return channel;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public boolean isAutoApproveNewSenders() {
        return autoApproveNewSenders;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
