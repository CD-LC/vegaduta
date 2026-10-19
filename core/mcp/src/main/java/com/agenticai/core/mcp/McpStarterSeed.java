package com.agenticai.core.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * "This starter connector was offered to this tenant once" (migration V659). The row is the whole
 * point: a starter the owner later deletes or edits must not come back or be overwritten on the next
 * start, and a registration row alone cannot say "was here and was removed".
 */
@Entity
@Table(name = "mcp_starter_seed")
public class McpStarterSeed {

    @Id
    @Column(name = "starter_key", nullable = false, length = 64)
    private String starterKey;

    @Column(name = "seeded_at", nullable = false)
    private Instant seededAt;

    protected McpStarterSeed() {}

    McpStarterSeed(String starterKey, Instant seededAt) {
        this.starterKey = starterKey;
        this.seededAt = seededAt;
    }

    public String getStarterKey() {
        return starterKey;
    }

    public Instant getSeededAt() {
        return seededAt;
    }
}
