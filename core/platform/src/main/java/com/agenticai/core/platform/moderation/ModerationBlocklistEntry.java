package com.agenticai.core.platform.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One blocked identifier - a user, phone number, domain, or region code. */
@Entity
@Table(name = "moderation_blocklist_entry", schema = "control_plane")
public class ModerationBlocklistEntry {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 16)
    private ModerationBlocklistType entryType;

    /** Stored normalised by {@code ModerationBlocklistService} so a lookup is a plain equality match. */
    @Column(name = "value", nullable = false, length = 512)
    private String value;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ModerationBlocklistEntry() {
    }

    ModerationBlocklistEntry(ModerationBlocklistType entryType, String value,
                             String reason, String createdBy) {
        this.id = UUID.randomUUID();
        this.entryType = entryType;
        this.value = value;
        this.reason = reason;
        this.active = true;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public ModerationBlocklistType getEntryType() {
        return entryType;
    }

    public String getValue() {
        return value;
    }

    public String getReason() {
        return reason;
    }

    public boolean isActive() {
        return active;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    void apply(boolean nowActive, String newReason, String by) {
        this.active = nowActive;
        if (newReason != null && !newReason.isBlank()) {
            this.reason = newReason;
        }
        this.createdBy = by;
    }
}
