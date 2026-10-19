package com.agenticai.core.agents.chatport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A conversation imported from another assistant (migration V452): a read-only, linear transcript owned by the
 * importing user's subject. There is no agent, session, memory or embedding behind it - live chat sessions are
 * Redis lists with a TTL, so this is deliberately its own small record. {@code messages} is
 * {@code [{role, content, ts, attachments[]}]}; attachment binaries are never stored.
 */
@Entity
@Table(name = "imported_chat")
public class ImportedChat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_subject", nullable = false, length = 255)
    private String ownerSubject;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "source_id", length = 128)
    private String sourceId;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "source_created_at")
    private Instant sourceCreatedAt;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @Column(name = "imported_at", nullable = false, updatable = false)
    private Instant importedAt;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "truncated", nullable = false)
    private boolean truncated;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "messages", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> messages = new ArrayList<>();

    protected ImportedChat() {
        // JPA
    }

    public ImportedChat(
        String ownerSubject,
        String source,
        String sourceId,
        String title,
        Instant sourceCreatedAt,
        Instant sourceUpdatedAt,
        int messageCount,
        boolean truncated,
        List<Map<String, Object>> messages
    ) {
        this.ownerSubject = ownerSubject;
        this.source = source;
        this.sourceId = sourceId;
        this.title = title;
        this.sourceCreatedAt = sourceCreatedAt;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.importedAt = Instant.now();
        this.messageCount = messageCount;
        this.truncated = truncated;
        this.messages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
    }

    public UUID getId() { return id; }
    public String getOwnerSubject() { return ownerSubject; }
    public String getSource() { return source; }
    public String getSourceId() { return sourceId; }
    public String getTitle() { return title; }
    public Instant getSourceCreatedAt() { return sourceCreatedAt; }
    public Instant getSourceUpdatedAt() { return sourceUpdatedAt; }
    public Instant getImportedAt() { return importedAt; }
    public int getMessageCount() { return messageCount; }
    public boolean isTruncated() { return truncated; }

    /** The stored transcript, oldest first. Never null. */
    public List<Map<String, Object>> getMessages() {
        return messages == null ? List.of() : messages;
    }
}
