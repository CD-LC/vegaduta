package com.agenticai.core.tenancy.demo;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only class in the demo-sandbox path allowed to hold a database
 * transaction open - the same split, for the same reason,
 * {@code TenantProvisioningRecorder} documents for signup: the slow,
 * failure-prone part of provisioning (running ~90 Flyway migrations against
 * a brand-new schema) must not happen while a transaction is held.
 *
 * <p><b>Why this exists at all instead of calling
 * {@code TenantProvisioningRecorder}:</b> that class and its methods are
 * package-private in {@code com.agenticai.core.tenancy}, and widening them
 * would mean editing a file that is under active concurrent edit by another
 * workstream (see CLAUDE.md). More importantly the two flows genuinely
 * differ at the point that matters: {@code TenantProvisioningRecorder
 * #activate} creates an {@code app_user} row bound to a Keycloak user id,
 * and a demo sandbox has neither. The genuinely shared, expensive,
 * error-prone part - schema creation and migration - IS reused verbatim via
 * {@code TenantSchemaMigrator}, which is the public component built for
 * exactly that.
 *
 * <p><b>{@code is_demo} is written with {@link JdbcTemplate}, not JPA.</b>
 * Migration V126 adds {@code control_plane.tenant.is_demo}, but {@code
 * Tenant.java} deliberately gains no mapped field for it in this slice -
 * that file is shared, hot, and concurrently edited, and Hibernate is
 * perfectly happy to ignore a column it does not know about (the column is
 * {@code NOT NULL DEFAULT FALSE}, so JPA inserts still work untouched). This
 * is the same call {@code NewsletterService} already made and documented for
 * {@code news_opt_in}. Follow-up, stated honestly: once {@code Tenant.java}
 * is quiet, map it properly and delete this one raw UPDATE.
 */
@Component
class DemoSessionRecorder {

    private static final String MARK_DEMO_SQL =
        "UPDATE control_plane.tenant SET is_demo = TRUE WHERE id = ?";

    private final TenantRepository tenantRepository;
    private final DemoSessionRepository demoSessionRepository;
    private final DemoGeneratedImageRepository demoGeneratedImageRepository;
    private final DemoGeneratedVideoRepository demoGeneratedVideoRepository;
    private final DemoGeneratedMusicRepository demoGeneratedMusicRepository;
    private final JdbcTemplate jdbcTemplate;

    DemoSessionRecorder(
        TenantRepository tenantRepository,
        DemoSessionRepository demoSessionRepository,
        DemoGeneratedImageRepository demoGeneratedImageRepository,
        DemoGeneratedVideoRepository demoGeneratedVideoRepository,
        DemoGeneratedMusicRepository demoGeneratedMusicRepository,
        JdbcTemplate jdbcTemplate
    ) {
        this.tenantRepository = tenantRepository;
        this.demoSessionRepository = demoSessionRepository;
        this.demoGeneratedImageRepository = demoGeneratedImageRepository;
        this.demoGeneratedVideoRepository = demoGeneratedVideoRepository;
        this.demoGeneratedMusicRepository = demoGeneratedMusicRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Step 1 of provisioning: the {@code PROVISIONING} tenant row, marked
     * {@code is_demo}. Always FREE plan and B2C/SIMPLE - a demo visitor is by
     * definition an unqualified individual poking at the product, and FREE is
     * also what routes their chat through the platform-hosted pool rather
     * than a BYOK credential they do not have.
     */
    @Transactional
    Tenant createProvisioningDemoTenant(String name, String schemaName, String keycloakRealm) {
        Tenant tenant = tenantRepository.saveAndFlush(
            new Tenant(name, TenantPlan.FREE, schemaName, keycloakRealm, AccountType.B2C, ExperienceMode.SIMPLE)
        );
        jdbcTemplate.update(MARK_DEMO_SQL, tenant.getId());
        return tenant;
    }

    /**
     * Step 3 of FIRST-EVER-VISITOR bootstrap only (after the shared demo
     * schema exists and has been migrated): flip the shared demo tenant
     * ACTIVE - which is what makes {@code TenantStatusGuard#isUsable} start
     * returning true for it. Idempotent ({@code markActive} is a plain
     * status set), so a rare double-bootstrap race (see {@code
     * DemoSessionService#provisionSharedDemoTenant}) is harmless.
     */
    @Transactional
    Tenant activateSharedDemoTenant(UUID tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new IllegalStateException("Shared demo tenant " + tenantId + " vanished mid-provisioning"));
        tenant.markActive();
        return tenant;
    }

    /**
     * Writes one visitor's {@link DemoSession} row against the (already
     * ACTIVE, already migrated) SHARED demo tenant/schema - see {@code
     * DemoSessionService#create}. Unlike the old per-visitor flow this no
     * longer touches the tenant row at all: the shared tenant is provisioned
     * and activated exactly once, by whichever request happens to be first.
     */
    @Transactional
    DemoSession recordSession(
        UUID tenantId, String tokenHash, String schemaName, String email, String createdIp,
        String persona, Instant createdAt, Instant expiresAt
    ) {
        return demoSessionRepository.saveAndFlush(
            new DemoSession(tokenHash, tenantId, schemaName, email, createdIp, persona, createdAt, expiresAt)
        );
    }

    @Transactional
    void markFailed(UUID tenantId) {
        tenantRepository.findById(tenantId).ifPresent(Tenant::markProvisioningFailed);
    }

    @Transactional
    void recordSeededAgent(UUID sessionId, UUID agentId) {
        demoSessionRepository.findById(sessionId).ifPresent(s -> s.recordSeededAgent(agentId));
    }

    /**
     * @param newExpiresAt the caller's already-computed {@code now +
     *     demo.lifetime-days} - see {@link DemoSession#touchActivity}. Passed
     *     in rather than resolved here because this class deliberately has no
     *     {@code PlatformSettingsService} dependency (it is pure persistence,
     *     the service layer owns settings resolution).
     */
    @Transactional
    void recordMessage(UUID sessionId, Instant now, Instant newExpiresAt) {
        demoSessionRepository.findById(sessionId).ifPresent(s -> {
            s.recordMessage();
            s.touchActivity(now, newExpiresAt);
        });
    }

    /**
     * Spends one of the sandbox's image allowance (migration V127's
     * {@code image_count}) BEFORE the upstream call goes out - the deliberate
     * pre-charge, mirroring {@code DemoChatController}'s "counted before the
     * call" note for exactly the same reason, only more so: if a failed
     * generation were free, "make the generation fail" becomes the cheapest
     * way to farm an unauthenticated endpoint that costs real money per call.
     *
     * <p>The consequence is stated plainly rather than hidden: a platform-side
     * failure does burn one of the visitor's two images. With an allowance
     * that small that is a real cost, and it is still the right trade - the
     * alternative charges nothing for the one failure mode an abuser can
     * induce on demand.
     */
    @Transactional
    void recordImageAttempt(UUID sessionId, Instant now, Instant newExpiresAt) {
        demoSessionRepository.findById(sessionId).ifPresent(s -> {
            s.recordImage();
            s.touchActivity(now, newExpiresAt);
        });
    }

    /**
     * Persists the pointer to a successfully stored image. Separate from
     * {@link #recordImageAttempt} because the counter is spent up-front and
     * this row only exists for generations that actually produced bytes -
     * counting here instead would double-charge.
     *
     * @return the new row's id, which is also the capability credential in the
     *     URL handed back to the browser - see {@link DemoGeneratedImage}.
     */
    @Transactional
    UUID recordImageRow(
        UUID sessionId, String objectKey, String contentType, String prompt, String model, String createdIp
    ) {
        return demoGeneratedImageRepository.saveAndFlush(
            new DemoGeneratedImage(sessionId, objectKey, contentType, prompt, model, createdIp)
        ).getId();
    }

    /** Video counterpart of {@link #recordImageAttempt} - spends the sandbox's (effectively single) video allowance up-front, before any provider call. */
    @Transactional
    void recordVideoAttempt(UUID sessionId, Instant now, Instant newExpiresAt) {
        demoSessionRepository.findById(sessionId).ifPresent(s -> {
            s.recordVideo();
            s.touchActivity(now, newExpiresAt);
        });
    }

    /** Video counterpart of {@link #recordImageRow}. */
    @Transactional
    UUID recordVideoRow(
        UUID sessionId, String objectKey, String contentType, String prompt, String model, int durationSeconds, String createdIp
    ) {
        return demoGeneratedVideoRepository.saveAndFlush(
            new DemoGeneratedVideo(sessionId, objectKey, contentType, prompt, model, durationSeconds, createdIp)
        ).getId();
    }

    /** Music counterpart of {@link #recordImageAttempt} - spends the sandbox's music allowance up-front, before any provider call. */
    @Transactional
    void recordMusicAttempt(UUID sessionId, Instant now, Instant newExpiresAt) {
        demoSessionRepository.findById(sessionId).ifPresent(s -> {
            s.recordMusic();
            s.touchActivity(now, newExpiresAt);
        });
    }

    /** Music counterpart of {@link #recordImageRow}. */
    @Transactional
    UUID recordMusicRow(
        UUID sessionId, String objectKey, String contentType, String prompt, String model, int durationSeconds, String createdIp
    ) {
        return demoGeneratedMusicRepository.saveAndFlush(
            new DemoGeneratedMusic(sessionId, objectKey, contentType, prompt, model, durationSeconds, createdIp)
        ).getId();
    }

    /**
     * Retirement, run by {@code DemoSessionExpiryJob}. Stamps {@code
     * expired_at} on this ONE session only. What used to happen here -
     * suspending the demo tenant - is gone now that every visitor shares one
     * demo tenant/schema (see {@code DemoSessionService}'s class javadoc):
     * suspending it would stop every OTHER still-live sandbox too, not just
     * this session. The actual per-session gate is {@code
     * DemoSession#isExpired}, which {@code DemoChatController} (and the
     * image/video/channel demo controllers) already check up front on every
     * request - that check is what stops a lapsed session answering, and it
     * was always per-session, never per-tenant.
     */
    @Transactional
    void retire(UUID sessionId, Instant at) {
        demoSessionRepository.findById(sessionId).ifPresent(s -> s.markExpired(at));
    }

    @Transactional
    void markReminderSent(UUID sessionId, Instant at) {
        demoSessionRepository.findById(sessionId).ifPresent(s -> s.markReminderSent(at));
    }

    /**
     * The transcript-email soft capture: stores the address the visitor typed
     * into "email me this conversation", which ALSO makes them eligible for
     * the existing pre-expiry reminder (same column the reminder job reads).
     * Recorded before - and independently of - whether the transcript mail
     * itself goes out, because the captured address is the durable value
     * here; the email send is best-effort on top.
     */
    @Transactional
    void recordTranscriptEmailCapture(UUID sessionId, String email) {
        demoSessionRepository.findById(sessionId).ifPresent(s -> s.updateEmail(email));
    }

    /** Spends one of the session's transcript-email sends (V130's counter) - only called after a successful send. */
    @Transactional
    void recordTranscriptEmailSent(UUID sessionId) {
        demoSessionRepository.findById(sessionId).ifPresent(DemoSession::recordTranscriptEmailSent);
    }

    @Transactional
    void recordConversion(UUID sessionId, UUID realTenantId, Instant at) {
        demoSessionRepository.findById(sessionId).ifPresent(s -> s.recordConversion(realTenantId, at));
    }

    // ------------------------------------------------------------------
    // Shareable-sandbox bookkeeping (migration V131).
    // ------------------------------------------------------------------

    /**
     * Assigns a share id exactly once per session, atomically. The guard is a
     * raw conditional UPDATE ({@code AND share_id IS NULL}) rather than a
     * read-then-set through JPA because two concurrent "share" clicks (a
     * double-tap, React StrictMode) would both read null and the second
     * commit would silently replace the first visitor-facing URL - the one
     * thing an idempotent share endpoint must never do.
     *
     * @return the share id actually on the row afterwards (the candidate if
     *     this call won the race, the earlier winner's otherwise), or null if
     *     the session vanished.
     */
    @Transactional
    String assignShareIdIfAbsent(UUID sessionId, String candidateShareId, Instant at) {
        jdbcTemplate.update(
            "UPDATE control_plane.demo_session SET share_id = ?, share_created_at = ? "
                + "WHERE id = ? AND share_id IS NULL",
            candidateShareId, at.atOffset(java.time.ZoneOffset.UTC), sessionId
        );
        return jdbcTemplate.query(
            "SELECT share_id FROM control_plane.demo_session WHERE id = ?",
            rs -> rs.next() ? rs.getString(1) : null,
            sessionId
        );
    }

    /** One public GET of the share meta - feeds the funnel's shareViews. */
    @Transactional
    void recordShareView(UUID sessionId) {
        demoSessionRepository.findById(sessionId).ifPresent(DemoSession::recordShareView);
    }

    /** One NEW distinct viewer IP admitted - feeds the funnel's shareViewers. */
    @Transactional
    void recordShareViewer(UUID sessionId) {
        demoSessionRepository.findById(sessionId).ifPresent(DemoSession::recordShareViewer);
    }

    /**
     * One abuse report; auto-revokes at {@code revokeThreshold} (see {@link
     * DemoSession#recordShareReport}).
     *
     * @return true when the share is (now) revoked.
     */
    @Transactional
    boolean recordShareReport(UUID sessionId, int revokeThreshold) {
        return demoSessionRepository.findById(sessionId)
            .map(s -> s.recordShareReport(revokeThreshold))
            .orElse(false);
    }

    /** Idempotent lab-funnel event - see {@link DemoSession#markLabStarted}. */
    @Transactional
    void recordLabStarted(UUID sessionId, Instant at) {
        demoSessionRepository.findById(sessionId).ifPresent(s -> s.markLabStarted(at));
    }

    /** Idempotent lab-funnel event - see {@link DemoSession#markLabCompleted}. */
    @Transactional
    void recordLabCompleted(UUID sessionId, Instant at) {
        demoSessionRepository.findById(sessionId).ifPresent(s -> s.markLabCompleted(at));
    }
}
