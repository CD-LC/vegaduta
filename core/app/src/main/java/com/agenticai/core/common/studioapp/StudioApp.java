package com.agenticai.core.common.studioapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A Phone-App-Studio app its owner published - the server-side anchor that
 * turns a single-file generated app into a product: a stable public slug on
 * {@code /api/public/apps/{slug}}, its own end-user accounts ({@link
 * StudioAppUser}), wired agent/workflow connections ({@link
 * StudioAppConnection}) and an optional hosted-AI block.
 *
 * <p>Tenant-schema table. The anonymous public surface resolves the slug to
 * this schema through {@code channel_account_link}
 * ({@code ExternalAccountProvider.STUDIO_APP}) exactly like the agent/workflow
 * share surfaces resolve their share ids.
 */
@Entity
@Table(name = "studio_app")
public class StudioApp {

    public enum Status { LIVE, DISABLED }

    /**
     * NONE - no end-user auth anywhere in this app; {@code vd.auth.*} calls
     * fail fast. OPTIONAL - the app decides where sign-in happens
     * ({@code vd.auth.require()} on whatever screen/button the builder wired,
     * or a per-connection {@code requires_auth} flag). REQUIRED - the runner
     * shows the sign-in gate before the app even loads.
     */
    public enum AuthMode { NONE, OPTIONAL, REQUIRED }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id = UUID.randomUUID();

    /**
     * Optimistic lock (JAVA-007, migration tenant/V365.2): every UPDATE is
     * {@code WHERE id = ? AND version = ?}, so a concurrent writer's save fails
     * with {@code ObjectOptimisticLockingFailureException} (HTTP 409) instead
     * of silently overwriting the other writer's columns. A wrapper type on
     * purpose: {@code null} marks a never-persisted entity for Spring Data.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Public, unguessable id in {@code /api/public/apps/{slug}} and {@code run-app.html#a=<slug>} links. */
    @Column(name = "slug", nullable = false, length = 24)
    private String slug;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** The whole single-file app, as the runner serves it verbatim. */
    @Column(name = "html", nullable = false, columnDefinition = "TEXT")
    private String html;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.LIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_mode", nullable = false, length = 16)
    private AuthMode authMode = AuthMode.NONE;

    @Column(name = "ai_enabled", nullable = false)
    private boolean aiEnabled;

    /** System prompt the app's {@code vd.ai.chat()} turns run under - the owner shapes the persona, end users only supply the user turn. */
    @Column(name = "ai_system_prompt", columnDefinition = "TEXT")
    private String aiSystemPrompt;

    @Column(name = "ai_max_tokens", nullable = false)
    private int aiMaxTokens = 700;

    /** HS256 secret for end-user session tokens - per app, generated at publish. See V359's comment for why it is not the global encryption key. */
    @Column(name = "token_secret", nullable = false, length = 64)
    private String tokenSecret;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** Bumped on every publish over an existing slug - lets the runner cache-bust. */
    @Column(name = "published_version", nullable = false)
    private int publishedVersion = 1;

    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHtml() { return html; }
    public void setHtml(String html) { this.html = html; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public AuthMode getAuthMode() { return authMode; }
    public void setAuthMode(AuthMode authMode) { this.authMode = authMode; }
    public boolean isAiEnabled() { return aiEnabled; }
    public void setAiEnabled(boolean aiEnabled) { this.aiEnabled = aiEnabled; }
    public String getAiSystemPrompt() { return aiSystemPrompt; }
    public void setAiSystemPrompt(String aiSystemPrompt) { this.aiSystemPrompt = aiSystemPrompt; }
    public int getAiMaxTokens() { return aiMaxTokens; }
    public void setAiMaxTokens(int aiMaxTokens) { this.aiMaxTokens = aiMaxTokens; }
    public String getTokenSecret() { return tokenSecret; }
    public void setTokenSecret(String tokenSecret) { this.tokenSecret = tokenSecret; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public int getPublishedVersion() { return publishedVersion; }
    public void setPublishedVersion(int publishedVersion) { this.publishedVersion = publishedVersion; }

    /** Optimistic-lock version; {@code null} until first persisted. */
    public Long getVersion() {
        return version;
    }
}
