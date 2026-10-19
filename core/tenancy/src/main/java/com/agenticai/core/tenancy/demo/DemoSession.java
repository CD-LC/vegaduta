package com.agenticai.core.tenancy.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * One unregistered visitor's throwaway sandbox - the "try it without
 * registering" flow's control-plane record (migration V126).
 *
 * <p>Lives in {@code control_plane}, never in a per-tenant schema, for
 * exactly the same chicken-and-egg reason {@code Tenant} does: this row is
 * how the platform maps an opaque demo token to a Postgres schema in the
 * first place, so it cannot itself live inside one.
 *
 * <p><b>What a demo session actually is:</b> a REAL tenant (real
 * {@code control_plane.tenant} row, real migrated Postgres schema, real
 * {@code agent} row inside it) that happens to be marked
 * {@code tenant.is_demo = TRUE} and carries an expiry. Nothing about the
 * sandbox is a mock - the visitor's agent is the same entity the paid
 * product uses, which is the entire point of the exercise. What it does NOT
 * have is a Keycloak user, an {@code app_user} row, or any way to log in:
 * possession of {@link #tokenHash}'s pre-image is the only credential.
 *
 * <p><b>{@code tokenHash}, not the token.</b> The raw token is a bearer
 * credential handed to an anonymous browser; it is generated once, returned
 * once, and only its SHA-256 hex is persisted - the same posture
 * {@code WorkflowDefinition#webhookTokenHash} and the inbound MCP API keys
 * already take. There is deliberately no way to recover a lost token: the
 * visitor starts a new sandbox instead.
 */
@Entity
@Table(
    name = "demo_session",
    schema = "control_plane",
    uniqueConstraints = @UniqueConstraint(columnNames = "token_hash")
)
public class DemoSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "tenant_schema", nullable = false, length = 63, updatable = false)
    private String tenantSchema;

    /** The pre-seeded sandbox agent. Nullable - seeding is best-effort, see {@link DemoSessionService}. */
    @Column(name = "agent_id")
    private UUID agentId;

    /**
     * Optional and visitor-supplied - at creation OR later via the
     * transcript-email endpoint (the soft capture, see
     * {@link DemoTranscriptEmailService}). Used for the pre-expiry reminder
     * and for sending the transcript, nothing else; the sandbox itself works
     * fine without it, and nothing in this flow asks for it as a
     * precondition. Null for the default "just let me try it" path.
     */
    @Column(name = "email", length = 320)
    private String email;

    /**
     * Which one-click persona preset seeded this sandbox's agent (migration
     * V130) - the lowercase {@link DemoPersona#wireName()}, or null for the
     * generic agent. Persisted, not just passed through, for two reasons: the
     * late/repair seed ({@code DemoSessionService#ensureSeededAgent}) must
     * build the SAME agent the visitor asked for, and the admin analytics
     * want conversion rate segmentable by persona. Stored as a string rather
     * than an {@code @Enumerated} on purpose - an unknown value (a removed
     * persona, a hand-edited row) must degrade to "no persona", never to a
     * Hibernate mapping exception on read.
     */
    @Column(name = "persona", length = 32, updatable = false)
    private String persona;

    @Column(name = "created_ip", length = 45, updatable = false)
    private String createdIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Activity-based expiry (migration V134). A brand-new sandbox is given
     * only the short {@code demo.inactivity-timeout-hours} grace window
     * (default 24h) at creation - see {@link DemoSessionService#create} - so
     * one that's never touched again quietly retires quickly rather than
     * squatting on a Postgres schema for the full {@code demo.lifetime-days}.
     * Real activity (a chat turn, an image) calls {@link #touchActivity},
     * which bumps this AND pushes {@link #expiresAt} out to the longer,
     * admin-configurable lifetime - so an actively-used sandbox keeps
     * sliding its own deadline forward, while an abandoned one is not kept
     * alive by a timer nobody is looking at.
     */
    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    /** Conversion seam - see {@link DemoConversionService}. Null for every session that never converted. */
    @Column(name = "converted_tenant_id")
    private UUID convertedTenantId;

    @Column(name = "converted_at")
    private Instant convertedAt;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    /**
     * How many AI images this sandbox has generated (migration V127). Its own
     * counter rather than sharing {@link #messageCount}, because the two caps
     * are an order of magnitude apart for a reason: a chat turn on the FREE
     * pool is fractions of a paisa, an image generation is real money, so the
     * image allowance is 2 against chat's 20 and the two must not be able to
     * consume each other.
     */
    @Column(name = "image_count", nullable = false)
    private int imageCount;

    /**
     * How many AI videos this sandbox has generated (migration V142). Own
     * counter for the same reason {@link #imageCount} has one, an order of
     * magnitude sharper here: Veo-class video generation is billed per
     * second of output, not per call, so the allowance is capped far
     * tighter still (15s clips, effectively 1 per session by default) and
     * must not be consumable by, or consume, chat/image allowances.
     */
    @Column(name = "video_count", nullable = false)
    private int videoCount;

    /**
     * How many music clips this sandbox has generated (migration V263). Own
     * counter for the same reason {@link #imageCount} has one: music is billed
     * per generated minute on a platform-paid key, so its allowance must not
     * be consumable by, or consume, the chat/image/video allowances.
     */
    @Column(name = "music_count", nullable = false)
    private int musicCount;

    /**
     * How many transcript emails have actually been SENT for this session
     * (migration V130) - the hard per-session cap behind
     * {@code POST /api/demo/transcript-email}, enforced in
     * {@link DemoTranscriptEmailService}. Same its-own-counter reasoning as
     * {@link #imageCount}: an email send costs SMTP reputation and is a spam
     * vector from an unauthenticated endpoint, so its allowance must not be
     * consumable by (or consume) the chat or image caps.
     */
    @Column(name = "transcript_emails_sent", nullable = false)
    private int transcriptEmailsSent;

    /**
     * The share-link capability handle (migration V131) - null until the
     * owner explicitly shares. Stored in the clear, unlike {@link #tokenHash},
     * because a share URL is meant to be handed around: it is an unguessable
     * public handle (144 bits of {@code SecureRandom}), not a secret whose
     * disclosure the database must prevent. See {@link DemoShareService}.
     */
    @Column(name = "share_id", length = 64)
    private String shareId;

    @Column(name = "share_created_at")
    private Instant shareCreatedAt;

    /**
     * Flipped TRUE automatically when {@link #shareReportedCount} reaches the
     * report threshold (see {@link DemoShareService#report}); never flipped
     * back by code - un-revoking a reported share is an operator decision.
     */
    @Column(name = "share_revoked", nullable = false)
    private boolean shareRevoked;

    @Column(name = "share_reported_count", nullable = false)
    private int shareReportedCount;

    /** Public GETs of the share meta - the admin funnel's "shareViews". */
    @Column(name = "share_view_count", nullable = false)
    private int shareViewCount;

    /**
     * Distinct viewer IPs admitted to this share's Redis viewer set - the
     * durable floor behind the funnel's "shareViewers" (the authoritative cap
     * itself lives in the Redis SET, see {@link DemoShareService#admitViewer}).
     */
    @Column(name = "share_viewer_count", nullable = false)
    private int shareViewerCount;

    /** Academy-lab funnel events (V131) - set once, never overwritten. */
    @Column(name = "lab_started_at")
    private Instant labStartedAt;

    @Column(name = "lab_completed_at")
    private Instant labCompletedAt;

    protected DemoSession() {
        // JPA
    }

    public DemoSession(
        String tokenHash, UUID tenantId, String tenantSchema, String email, String createdIp,
        String persona, Instant createdAt, Instant expiresAt
    ) {
        this.tokenHash = tokenHash;
        this.tenantId = tenantId;
        this.tenantSchema = tenantSchema;
        this.email = email;
        this.createdIp = createdIp;
        this.persona = persona;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.lastActivityAt = createdAt;
        this.messageCount = 0;
        this.imageCount = 0;
        this.videoCount = 0;
        this.transcriptEmailsSent = 0;
    }

    public boolean isExpired(Instant now) {
        return expiredAt != null || !now.isBefore(expiresAt);
    }

    /** Whole days left, floored at 0 - what the {@code /try} countdown renders. */
    public long daysRemaining(Instant now) {
        long seconds = expiresAt.getEpochSecond() - now.getEpochSecond();
        return seconds <= 0 ? 0 : seconds / 86_400L;
    }

    public void recordSeededAgent(UUID agentId) {
        this.agentId = agentId;
    }

    /**
     * Real activity happened: slide the deadline out to {@code newExpiresAt}
     * (the caller computes {@code now + demo.lifetime-days} - this entity has
     * no settings access) and stamp {@code now} as the last-activity mark.
     * Never moves {@link #expiresAt} BACKWARDS - an out-of-order call (e.g. a
     * retried request) must not shorten a deadline a previous call already
     * extended.
     */
    public void touchActivity(Instant now, Instant newExpiresAt) {
        this.lastActivityAt = now;
        if (newExpiresAt.isAfter(this.expiresAt)) {
            this.expiresAt = newExpiresAt;
        }
    }

    public void recordMessage() {
        this.messageCount++;
    }

    public void recordImage() {
        this.imageCount++;
    }

    public void recordVideo() {
        this.videoCount++;
    }

    public void recordMusic() {
        this.musicCount++;
    }

    /**
     * The transcript-email soft capture: stores (or refreshes) the visitor's
     * address. Deliberately last-write-wins rather than only-if-null - a
     * visitor correcting a typo'd address should not be stuck with the typo,
     * and this column's consumers (the expiry reminder, the transcript email
     * itself) all want the most recent address the visitor asserted.
     */
    public void updateEmail(String email) {
        this.email = email;
    }

    /** Spends one of the per-session transcript-email sends - see {@link #transcriptEmailsSent}. */
    public void recordTranscriptEmailSent() {
        this.transcriptEmailsSent++;
    }

    public void markReminderSent(Instant at) {
        this.reminderSentAt = at;
    }

    public void markExpired(Instant at) {
        this.expiredAt = at;
    }

    /** See {@link DemoConversionService#linkConversion} - bookkeeping only, no artifacts move here. */
    public void recordConversion(UUID convertedTenantId, Instant at) {
        this.convertedTenantId = convertedTenantId;
        this.convertedAt = at;
    }

    /** First-write-wins - see {@code DemoSessionRecorder#assignShareIdIfAbsent} for the atomic guard. */
    public void assignShare(String shareId, Instant at) {
        if (this.shareId == null) {
            this.shareId = shareId;
            this.shareCreatedAt = at;
        }
    }

    public void recordShareView() {
        this.shareViewCount++;
    }

    public void recordShareViewer() {
        this.shareViewerCount++;
    }

    /**
     * One abuse report against this share. Auto-revokes at {@code
     * revokeThreshold} and stays revoked - see {@link #shareRevoked}.
     *
     * @return true when this share is (now) revoked.
     */
    public boolean recordShareReport(int revokeThreshold) {
        this.shareReportedCount++;
        if (this.shareReportedCount >= revokeThreshold) {
            this.shareRevoked = true;
        }
        return this.shareRevoked;
    }

    /** Idempotent - the lab funnel wants first-start, not latest-start. */
    public void markLabStarted(Instant at) {
        if (this.labStartedAt == null) {
            this.labStartedAt = at;
        }
    }

    /** Idempotent, same reasoning as {@link #markLabStarted}. */
    public void markLabCompleted(Instant at) {
        if (this.labCompletedAt == null) {
            this.labCompletedAt = at;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getTenantSchema() {
        return tenantSchema;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getEmail() {
        return email;
    }

    public String getCreatedIp() {
        return createdIp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    public Instant getReminderSentAt() {
        return reminderSentAt;
    }

    public Instant getExpiredAt() {
        return expiredAt;
    }

    public UUID getConvertedTenantId() {
        return convertedTenantId;
    }

    public Instant getConvertedAt() {
        return convertedAt;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public int getImageCount() {
        return imageCount;
    }

    public int getVideoCount() {
        return videoCount;
    }

    public int getMusicCount() {
        return musicCount;
    }

    public String getPersona() {
        return persona;
    }

    public int getTranscriptEmailsSent() {
        return transcriptEmailsSent;
    }

    public String getShareId() {
        return shareId;
    }

    public Instant getShareCreatedAt() {
        return shareCreatedAt;
    }

    public boolean isShareRevoked() {
        return shareRevoked;
    }

    public int getShareReportedCount() {
        return shareReportedCount;
    }

    public int getShareViewCount() {
        return shareViewCount;
    }

    public int getShareViewerCount() {
        return shareViewerCount;
    }

    public Instant getLabStartedAt() {
        return labStartedAt;
    }

    public Instant getLabCompletedAt() {
        return labCompletedAt;
    }
}
