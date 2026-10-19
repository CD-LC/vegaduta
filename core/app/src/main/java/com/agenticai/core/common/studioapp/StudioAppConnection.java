package com.agenticai.core.common.studioapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One wired capability inside a published app: "when the app calls
 * {@code vd.call(alias, input)}, run THIS agent/workflow". The builder
 * controls the contract - {@code requestTemplate} shapes what the user's
 * input becomes (the {@code {{input}}} and {@code {{user.*}}} placeholders),
 * {@code responsePath} picks which part of the result the app sees.
 *
 * <p>{@code shareId} deliberately mirrors the share the Connect panel minted:
 * the public call is authorised by that share still being live, so revoking
 * the share (or its owner deleting it) kills the app feature too - one
 * revocation story, not two.
 */
@Entity
@Table(name = "studio_app_connection")
public class StudioAppConnection {

    public enum Kind { AGENT, WORKFLOW }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "app_id", nullable = false)
    private UUID appId;

    /** The name the app calls it by in {@code vd.call(alias, ...)} - stable across re-publishes. */
    @Column(name = "alias", nullable = false, length = 40)
    private String alias;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    /** The agent_share / workflow_share share id backing this connection - null is tolerated on old rows but publish always sets it. */
    @Column(name = "share_id", length = 64)
    private String shareId;

    @Column(name = "display_name", length = 120)
    private String displayName;

    /** Message template rendered per call - {@code {{input}}}, {@code {{user.name}}}, {@code {{user.email}}}, {@code {{data.<key>}}} placeholders; null = the input verbatim. */
    @Column(name = "request_template", columnDefinition = "TEXT")
    private String requestTemplate;

    /** Dotted path into the JSON response (e.g. {@code reply}, {@code resultText}, {@code data.items}); null = platform default per kind. */
    @Column(name = "response_path", length = 160)
    private String responsePath;

    /** When true (or the app's authMode is REQUIRED) the public call 401s without a valid end-user token. */
    @Column(name = "requires_auth", nullable = false)
    private boolean requiresAuth;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public UUID getId() { return id; }
    public UUID getAppId() { return appId; }
    public void setAppId(UUID appId) { this.appId = appId; }
    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public UUID getTargetId() { return targetId; }
    public void setTargetId(UUID targetId) { this.targetId = targetId; }
    public String getShareId() { return shareId; }
    public void setShareId(String shareId) { this.shareId = shareId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getRequestTemplate() { return requestTemplate; }
    public void setRequestTemplate(String requestTemplate) { this.requestTemplate = requestTemplate; }
    public String getResponsePath() { return responsePath; }
    public void setResponsePath(String responsePath) { this.responsePath = responsePath; }
    public boolean isRequiresAuth() { return requiresAuth; }
    public void setRequiresAuth(boolean requiresAuth) { this.requiresAuth = requiresAuth; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
