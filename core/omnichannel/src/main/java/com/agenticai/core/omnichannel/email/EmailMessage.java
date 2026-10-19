package com.agenticai.core.omnichannel.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One outbound email sent by the {@code bulk_email} workflow node (V335). The
 * {@link #getMessageId()} is the RFC 5322 Message-ID that went on the wire
 * (stored without angle brackets) so {@link EmailReplyTracker} can match an
 * inbound {@code In-Reply-To}/{@code References} header back to the run, node
 * and recipient that produced it. Tenant-scoped by schema-per-tenant.
 */
@Entity
@Table(name = "email_message")
public class EmailMessage {

    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "node_id", length = 64)
    private String nodeId;

    @Column(nullable = false, length = 320)
    private String recipient;

    @Column(length = 998)
    private String subject;

    @Column(name = "message_id", length = 512)
    private String messageId;

    @Column(name = "thread_key", length = 128)
    private String threadKey;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 1000)
    private String error;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "replied_at")
    private Instant repliedAt;

    @Column(name = "reply_kind", length = 16)
    private String replyKind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected EmailMessage() {
        // JPA
    }

    public EmailMessage(
        String runId, String nodeId, String recipient, String subject, String messageId,
        String threadKey, String status, String error, Instant sentAt
    ) {
        this.runId = runId;
        this.nodeId = nodeId;
        this.recipient = recipient;
        this.subject = subject == null || subject.length() <= 998 ? subject : subject.substring(0, 998);
        this.messageId = messageId;
        this.threadKey = threadKey;
        this.status = status;
        this.error = error == null || error.length() <= 1000 ? error : error.substring(0, 1000);
        this.sentAt = sentAt;
    }

    public void markReplied(Instant at, String kind) {
        this.repliedAt = at;
        this.replyKind = kind;
    }

    public UUID getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSubject() {
        return subject;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getThreadKey() {
        return threadKey;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getRepliedAt() {
        return repliedAt;
    }

    public String getReplyKind() {
        return replyKind;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
