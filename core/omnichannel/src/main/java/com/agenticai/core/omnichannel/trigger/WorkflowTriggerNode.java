package com.agenticai.core.omnichannel.trigger;

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
 * Which workflow, and which specific Trigger NODE within that workflow's
 * graph (by id only - this module deliberately doesn't know what a
 * "workflow" or a "node" is beyond a UUID/string, see {@link
 * WorkflowTriggerChannel}'s javadoc for why), handles inbound messages for a
 * given channel, for the currently-active tenant. Replaces {@code
 * ChannelWorkflowTrigger} (one row per channel, pointing only at a workflow
 * id) now that a trigger binding is per NODE rather than per workflow - each
 * Trigger node in a workflow's graph can be independently bound to its own
 * inbound channel, rather than a workflow having a single, whole-workflow
 * trigger channel.
 *
 * <p>Also absorbs what used to live directly on {@code WorkflowDefinition}
 * ({@code webhookTokenEncrypted}/{@code webhookTokenHash}/{@code
 * webhookEnabled}/{@code webhookAuthMode}) - a WEBHOOK-channel trigger node's
 * token is a property of THAT node, not of the workflow as a whole, for the
 * same per-node reasoning. See {@code V175__per_node_workflow_triggers.sql}
 * for the full migration/backfill story.
 */
@Entity
@Table(name = "workflow_trigger_node")
public class WorkflowTriggerNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "node_id", nullable = false, unique = true, length = 64)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WorkflowTriggerChannel channel;

    @Column(name = "webhook_token_encrypted")
    private String webhookTokenEncrypted;

    @Column(name = "webhook_token_hash", unique = true)
    private String webhookTokenHash;

    @Column(name = "webhook_enabled", nullable = false)
    private boolean webhookEnabled;

    @Column(name = "webhook_auth_mode", nullable = false, length = 16)
    private String webhookAuthMode = "SHARED_SECRET";

    /**
     * Optional second factor on the shared-secret webhook URL (V482): when not
     * {@code "NONE"}, the sender must also prove it holds the configured
     * signing secret by presenting the scheme's HMAC over the RAW request
     * body - {@code WorkflowWebhookController} verifies before the run
     * starts. A plain {@code String} for the same module-direction reason
     * {@link #webhookAuthMode} is one (the enum lives in {@code
     * core-workflow}, which this module cannot see).
     */
    @Column(name = "webhook_signature_mode", nullable = false, length = 16)
    private String webhookSignatureMode = "NONE";

    /**
     * AES-256-GCM ciphertext of the HMAC key, written/read only through
     * {@code EncryptionService} by the {@code core-workflow} side - same
     * contract as {@link #webhookTokenEncrypted}. The plaintext secret is
     * never returned by any API surface.
     */
    @Column(name = "webhook_signature_secret_encrypted")
    private String webhookSignatureSecretEncrypted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkflowTriggerNode() {
        // JPA
    }

    public WorkflowTriggerNode(UUID workflowId, String nodeId, WorkflowTriggerChannel channel) {
        this.workflowId = workflowId;
        this.nodeId = nodeId;
        this.channel = channel;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void rebind(UUID workflowId, WorkflowTriggerChannel channel) {
        this.workflowId = workflowId;
        this.channel = channel;
        this.updatedAt = Instant.now();
    }

    public void enableWebhook(String webhookTokenEncrypted, String webhookTokenHash) {
        this.channel = WorkflowTriggerChannel.WEBHOOK;
        this.webhookTokenEncrypted = webhookTokenEncrypted;
        this.webhookTokenHash = webhookTokenHash;
        this.webhookEnabled = true;
        this.updatedAt = Instant.now();
    }

    public void disableWebhook() {
        this.webhookEnabled = false;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public WorkflowTriggerChannel getChannel() {
        return channel;
    }

    public String getWebhookTokenEncrypted() {
        return webhookTokenEncrypted;
    }

    public String getWebhookTokenHash() {
        return webhookTokenHash;
    }

    public boolean isWebhookEnabled() {
        return webhookEnabled;
    }

    public String getWebhookAuthMode() {
        return webhookAuthMode;
    }

    public void setWebhookAuthMode(String webhookAuthMode) {
        this.webhookAuthMode = webhookAuthMode;
        this.updatedAt = Instant.now();
    }

    public String getWebhookSignatureMode() {
        return webhookSignatureMode;
    }

    public String getWebhookSignatureSecretEncrypted() {
        return webhookSignatureSecretEncrypted;
    }

    /**
     * Sets the signature mode and (re-)writes the secret ciphertext. Passing
     * {@code "NONE"} clears both - verification is off and the old secret is
     * dropped rather than kept around armed-but-unused.
     */
    public void setWebhookSignature(String mode, String secretEncrypted) {
        this.webhookSignatureMode = mode;
        this.webhookSignatureSecretEncrypted = secretEncrypted;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
