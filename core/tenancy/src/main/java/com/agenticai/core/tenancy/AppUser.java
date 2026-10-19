package com.agenticai.core.tenancy;

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
 * A user belonging to a {@link Tenant} - Phase 0 Stage 2.
 *
 * <p>Deliberately has no password/credential field of any kind - Keycloak (Phase 0 Stage 1) owns
 * authentication entirely. {@code keycloakUserId} is the foreign reference into Keycloak's own user
 * store; this row exists so the app has a fast local join target (e.g., "list this tenant's users")
 * without a Keycloak Admin API round-trip on every request.
 */
@Entity
@Table(
        name = "app_user",
        schema = "control_plane",
        uniqueConstraints = {
            @UniqueConstraint(columnNames = "keycloak_user_id"),
            @UniqueConstraint(columnNames = {"tenant_id", "email"})
        })
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "keycloak_user_id", nullable = false, length = 255)
    private String keycloakUserId;

    @Column(nullable = false, length = 255)
    private String email;

    // Simple mode's Quick Task feature ("type what you want, get the result
    // on WhatsApp/SMS/a call") delivers to THIS number - the logged-in
    // person's own phone, captured once via AccountController rather than
    // typed per task. Deliberately separate from anything in the
    // omnichannel module: every other phone number in this codebase belongs
    // to a channel SESSION (a customer texting in), not a platform USER.
    // Nullable - a user hasn't necessarily set this yet; Quick Task's
    // frontend prompts for it inline the first time it's missing.
    @Column(name = "phone_number", length = 32)
    private String phoneNumber;

    // Profile-picture upload (AppShell's avatar next to Sign out) - the
    // MinIO/S3 object key the picture was uploaded under (e.g.
    // "avatars/<id>.png"), NOT the raw bytes and NOT a public URL - see
    // AccountController#getAvatar, which resolves this key through
    // FileStorageService on every request, same "persist the key, stream the
    // bytes back through our own endpoint" pattern as GeneratedImage/
    // SarvamGeneratedAudio. Null until the user uploads a picture; AppShell
    // falls back to initials in that case, unchanged from before this column
    // existed.
    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // V475 - when this person last made an authenticated request (throttled
    // to one write per 5 minutes, see AppUserLastSeenRecorder). Read-only to
    // JPA on purpose: it is written ONLY by that recorder's own conditional
    // UPDATE, and a normal entity save (a phone-number or avatar change that
    // loaded this row minutes earlier) must never overwrite a newer value
    // with the stale one it happened to load.
    @Column(name = "last_seen_at", insertable = false, updatable = false)
    private Instant lastSeenAt;

    protected AppUser() {
        // JPA
    }

    public AppUser(UUID tenantId, String keycloakUserId, String email, UserRole role) {
        this.tenantId = tenantId;
        this.keycloakUserId = keycloakUserId;
        this.email = email;
        this.role = role;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void setRole(UserRole role) {
        this.role = role;
        this.updatedAt = Instant.now();
    }

    /**
     * Points a stale row at the current Keycloak account for the same tenant + email (B10 repair).
     */
    public void relinkKeycloakUser(String keycloakUserId) {
        this.keycloakUserId = keycloakUserId;
        this.updatedAt = Instant.now();
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
        this.updatedAt = Instant.now();
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
        this.updatedAt = Instant.now();
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getKeycloakUserId() {
        return keycloakUserId;
    }

    public String getEmail() {
        return email;
    }

    public UserRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Null until the user's first authenticated request after V475 shipped. */
    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
