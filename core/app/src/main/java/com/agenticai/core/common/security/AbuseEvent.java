package com.agenticai.core.common.security;

import jakarta.persistence.*;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;

/**
 * Fraud detection audit log: tracks all abuse detection decisions (brute force, rate limit, VPN detection, etc.).
 * Each entry represents a security event and the action taken by the abuse detection system.
 *
 * Tenant-schema entity (lives in the calling tenant's Postgres schema).
 * Persisted via migration V356 ({@code abuse_events}, {@code db/migration/tenant}).
 */
@Entity
@Table(name = "abuse_events", indexes = {
    @Index(name = "idx_abuse_events_tenant_ip_created", columnList = "tenant_id,client_ip,created_at DESC"),
    @Index(name = "idx_abuse_events_tenant_user_created", columnList = "tenant_id,user_id,created_at DESC"),
    @Index(name = "idx_abuse_events_event_type", columnList = "event_type"),
    @Index(name = "idx_abuse_events_severity", columnList = "severity")
})
public class AbuseEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID tenantId;

    /**
     * Event type: AUTH_BRUTE_FORCE, VPN_AUTH_ABUSE, API_RATE_LIMIT_EXCEEDED,
     * BILLING_VELOCITY_SPIKE, BILLING_SPEND_SURGE, SUSPICIOUS_PATTERN, etc.
     */
    @Column(nullable = false, length = 50)
    private String eventType;

    /**
     * Severity: LOW, MEDIUM, HIGH, CRITICAL
     */
    @Column(nullable = false, length = 20)
    private String severity;

    /**
     * Client IP address (INET type in Postgres)
     */
    @Column(nullable = false)
    private InetAddress clientIp;

    /**
     * User ID if the request was authenticated, null for unauthenticated requests
     */
    @Column
    private UUID userId;

    /**
     * User-Agent header from the request
     */
    @Column(columnDefinition = "TEXT")
    private String userAgent;

    /**
     * TLS JA3 fingerprint (TLS client fingerprinting for bot detection)
     */
    // Explicit name: the default snake_case strategy would render
    // tlsJa3Fingerprint as tls_ja3fingerprint, but V356 created
    // tls_ja3_fingerprint — validate fails without it.
    @Column(name = "tls_ja3_fingerprint", length = 100)
    private String tlsJa3Fingerprint;

    /**
     * Human-readable reason for the abuse detection decision
     */
    @Column(columnDefinition = "TEXT")
    private String reason;

    /**
     * Decision: ALLOW, CHALLENGE, BLOCK
     */
    @Column(nullable = false, length = 20)
    private String decision;

    /**
     * Challenge nonce if decision is CHALLENGE (reference to challenge_nonces table)
     */
    @Column(length = 255)
    private String challengeNonce;

    @Column(nullable = false)
    private Instant createdAt;

    // Constructors
    public AbuseEvent() {
        this.createdAt = Instant.now();
    }

    public AbuseEvent(UUID tenantId, String eventType, String severity,
                      InetAddress clientIp, String reason, String decision) {
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.severity = severity;
        this.clientIp = clientIp;
        this.reason = reason;
        this.decision = decision;
        this.createdAt = Instant.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public InetAddress getClientIp() { return clientIp; }
    public void setClientIp(InetAddress clientIp) { this.clientIp = clientIp; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getTlsJa3Fingerprint() { return tlsJa3Fingerprint; }
    public void setTlsJa3Fingerprint(String tlsJa3Fingerprint) { this.tlsJa3Fingerprint = tlsJa3Fingerprint; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }

    public String getChallengeNonce() { return challengeNonce; }
    public void setChallengeNonce(String challengeNonce) { this.challengeNonce = challengeNonce; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "AbuseEvent{" +
                "id=" + id +
                ", tenantId=" + tenantId +
                ", eventType='" + eventType + '\'' +
                ", severity='" + severity + '\'' +
                ", clientIp=" + clientIp +
                ", userId=" + userId +
                ", decision='" + decision + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
