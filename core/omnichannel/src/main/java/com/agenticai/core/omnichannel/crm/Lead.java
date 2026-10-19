package com.agenticai.core.omnichannel.crm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One sales lead (migration V337). Email is stored lower-cased and is unique
 * per tenant when present (partial unique index on lower(email)). Tags are a
 * comma-joined string in one column - a lead has a handful, never queried by.
 */
@Entity
@Table(name = "lead")
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "phone", length = 64)
    private String phone;

    @Column(name = "company")
    private String company;

    @Column(name = "title")
    private String title;

    @Column(name = "source", length = 64)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 16)
    private LeadStage stage = LeadStage.NEW;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "owner")
    private String owner;

    @Column(name = "value", precision = 14, scale = 2)
    private BigDecimal value;

    @Column(name = "tags", columnDefinition = "text")
    private String tags;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "last_contacted_at")
    private Instant lastContactedAt;

    @Column(name = "last_replied_at")
    private Instant lastRepliedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Lead() {
        // JPA
    }

    public Lead(String name, String email) {
        this.name = name;
        this.email = normalizeEmail(email);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public static String joinTags(List<String> tags) {
        if (tags == null) {
            return null;
        }
        List<String> clean = new ArrayList<>();
        for (String t : tags) {
            if (t == null || t.isBlank()) {
                continue;
            }
            String c = t.trim().replace(",", " ");
            if (!clean.contains(c)) {
                clean.add(c);
            }
        }
        return clean.isEmpty() ? null : String.join(",", clean);
    }

    public List<String> tagList() {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = normalizeEmail(email); }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LeadStage getStage() { return stage; }
    public void setStage(LeadStage stage) { this.stage = stage; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = Math.max(0, Math.min(100, score)); }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getLastContactedAt() { return lastContactedAt; }
    public void setLastContactedAt(Instant lastContactedAt) { this.lastContactedAt = lastContactedAt; }
    public Instant getLastRepliedAt() { return lastRepliedAt; }
    public void setLastRepliedAt(Instant lastRepliedAt) { this.lastRepliedAt = lastRepliedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
