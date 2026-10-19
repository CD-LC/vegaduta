package com.agenticai.core.tenancy.demo;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PlatformSettingsService;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantSchemaMigrator;
import com.agenticai.core.tenancy.demo.dto.DemoSessionCreateRequest;
import com.agenticai.core.tenancy.demo.dto.DemoSessionResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * "Try it without registering" - provisioning and lifecycle for an
 * unregistered visitor's throwaway sandbox.
 *
 * <h2>One SHARED tenant, not one per visitor</h2>
 * Every demo visitor lands in the SAME {@code control_plane.tenant} row and
 * the SAME migrated Postgres schema ({@code app.demo.shared-schema}, default
 * {@code demo_shared}) - {@link DemoSharedTenantBootstrap#getOrCreate()} provisions it
 * once, the very first time any visitor ever hits {@code /api/demo/session}
 * on a fresh deployment, and every request after that reuses the existing
 * row. This is deliberate and is the whole point of this revision: tenant
 * creation is reserved for {@code TenantProvisioningService#signup} and
 * {@code #onboardExistingUser} - the two paths that follow a human actually
 * registering - and an anonymous, unauthenticated {@code /try} click must
 * never mint another real {@code control_plane.tenant} row or another
 * Postgres schema. (Earlier versions of this class provisioned a brand-new
 * tenant+schema per visitor; that is what let the abuse-posture removal in
 * commit {@code d489b60e} turn into unbounded schema creation - see the
 * git history on this file.)
 *
 * <h2>Isolation, since the schema is shared</h2>
 * The schema being shared does NOT mean visitors share each other's work.
 * {@link #inDemoTenantContext} binds {@code TenantContext}'s SUBJECT (not
 * just its schema) to {@code "demo:" + session.getId()} for every demo
 * request, and every write that lands in the shared schema is stamped with
 * that subject as {@code created_by} - the exact same no-FK, plain-column
 * convention {@code agent.created_by} already uses to isolate one tenant's
 * own users from each other (see {@code V151__agent_workflow_owner.sql} and
 * {@code ResourceAccessService}). Anything that reads demo-scoped data back
 * out - the chat/agent lookup path, and {@code DemoConversionAgentCopier} on
 * registration - filters by that same {@code created_by}, never by "every
 * row in the schema". The durable transcript/image/video tables
 * ({@code demo_chat_message}, {@code demo_generated_image/video}) were
 * already keyed by {@code session_id} in {@code control_plane} before this
 * change and needed no rework.
 *
 * <h2>What it deliberately does NOT create</h2>
 * No Keycloak user, no {@code app_user} row, no password, no email
 * verification, no gateway subscription. There is no way to log in to the
 * demo sandbox - possession of the opaque demo token is the only credential,
 * and that token is scoped to the tiny {@code /api/demo/**} surface, never to
 * the authenticated console API. This is why the flow does not (and cannot)
 * go through {@code TenantProvisioningService#signup}: that method's entire
 * middle section is Keycloak user creation and role assignment.
 *
 * <h2>Bootstrap ordering and compensation</h2>
 * Mirrors {@code TenantProvisioningService}'s posture, minus the Keycloak
 * leg, but ONLY for the one-time shared-tenant bootstrap: tenant row
 * ({@code PROVISIONING}) &rarr; schema + Flyway migration &rarr; tenant
 * flips {@code ACTIVE}. A bootstrap failure drops the half-made shared
 * schema and marks the tenant {@code PROVISIONING_FAILED} so the next
 * visitor's request retries the bootstrap cleanly. Every request after the
 * first successful bootstrap just writes a {@link DemoSession} row against
 * the already-ACTIVE shared tenant - no schema work, nothing to compensate.
 * Agent seeding happens AFTER the session row is written and is explicitly
 * best-effort: a seeding failure leaves a working, empty sandbox.
 *
 * <h2>Abuse posture, stated honestly</h2>
 * Because sandbox creation no longer provisions a tenant or a schema, the
 * blast radius of unauthenticated {@code /api/demo/session} calls is now
 * bounded to control-plane rows (one {@code demo_session}, one seeded
 * {@code agent}) rather than a whole Postgres schema each time - but it is
 * still an unauthenticated endpoint and the per-sandbox message cap (default
 * 20 turns) plus the shared tenant's FREE-plan LLM budget remain the actual
 * spend backstops. {@link DemoSessionRateLimiter} and {@code
 * DemoSessionRepository#countLiveForIp} exist in this package but were
 * disconnected from creation in commit {@code d489b60e} ("Remove per-IP
 * sandbox restrictions") and remain so here - re-wiring them is a separate,
 * still-open decision, not part of this change. {@code demo.enabled} is
 * still the platform-admin kill switch for the whole surface, no redeploy
 * needed.
 */
@Service
public class DemoSessionService {

    private static final Logger log = LoggerFactory.getLogger(DemoSessionService.class);

    /** Actor label written into {@code TenantContext} for the duration of a demo request. */
    static final String DEMO_ACTOR_PREFIX = "demo:";

    private final TenantRepository tenantRepository;
    private final DemoSessionRepository demoSessionRepository;
    private final DemoChatMessageRepository demoChatMessageRepository;
    private final DemoSessionRecorder recorder;
    private final PlatformSettingsService platformSettings;
    // Read-only here: this class only asks it for the image allowance so the
    // session response can report it (see DemoSessionResponse#imagesUsed).
    // The generation path itself never goes through DemoSessionService.
    private final DemoImageService demoImageService;
    private final DemoSharedTenantBootstrap sharedTenant;
    private final DemoAgentSeeding agentSeeding;
    private final int defaultLifetimeDays;
    private final int defaultMessageLimit;
    private final int defaultMaxLiveSessionsPerIp;
    private final int defaultInactivityTimeoutHours;
    private final int defaultEditableHours;

    public DemoSessionService(
        TenantRepository tenantRepository,
        DemoSessionRepository demoSessionRepository,
        DemoChatMessageRepository demoChatMessageRepository,
        DemoSessionRecorder recorder,
        TenantSchemaMigrator schemaMigrator,
        PlatformSettingsService platformSettings,
        DemoImageService demoImageService,
        Optional<DemoWorkspaceSeeder> seeder,
        DataSource dataSource,
        @Value("${app.keycloak.realm:agentic-ai}") String keycloakRealm,
        @Value("${app.demo.shared-schema:demo_shared}") String sharedDemoSchema,
        @Value("${app.demo.lifetime-days:5}") int defaultLifetimeDays,
        @Value("${app.demo.message-limit:20}") int defaultMessageLimit,
        @Value("${app.demo.max-live-sessions-per-ip:2}") int defaultMaxLiveSessionsPerIp,
        @Value("${app.demo.inactivity-timeout-hours:24}") int defaultInactivityTimeoutHours,
        @Value("${app.demo.editable-hours:24}") int defaultEditableHours
    ) {
        this.tenantRepository = tenantRepository;
        this.demoSessionRepository = demoSessionRepository;
        this.demoChatMessageRepository = demoChatMessageRepository;
        this.recorder = recorder;
        this.platformSettings = platformSettings;
        this.demoImageService = demoImageService;
        this.sharedTenant = new DemoSharedTenantBootstrap(
            tenantRepository, recorder, schemaMigrator, dataSource, keycloakRealm, sharedDemoSchema);
        this.agentSeeding = new DemoAgentSeeding(seeder, recorder);
        this.defaultLifetimeDays = defaultLifetimeDays;
        this.defaultMessageLimit = defaultMessageLimit;
        this.defaultMaxLiveSessionsPerIp = defaultMaxLiveSessionsPerIp;
        this.defaultInactivityTimeoutHours = defaultInactivityTimeoutHours;
        this.defaultEditableHours = defaultEditableHours;
    }

    /** Platform-admin kill switch for the whole try-before-you-register surface, no redeploy needed. */
    public boolean isEnabled() {
        return platformSettings.getBoolean("demo.enabled", true);
    }

    public int messageLimit() {
        return platformSettings.getInt("demo.message-limit", defaultMessageLimit);
    }

    public int lifetimeDays() {
        return platformSettings.getInt("demo.lifetime-days", defaultLifetimeDays);
    }

    /**
     * How long a sandbox with NO activity since creation stays alive before
     * {@link DemoSessionExpiryJob} retires it (default 24h) - see {@link
     * #create}. Deliberately separate from {@link #lifetimeDays()}: that
     * governs how far an ACTIVE sandbox's deadline keeps sliding forward
     * (see {@link DemoSession#touchActivity}), this governs the much
     * shorter grace period for one nobody ever came back to.
     */
    public int inactivityTimeoutHours() {
        return platformSettings.getInt("demo.inactivity-timeout-hours", defaultInactivityTimeoutHours);
    }

    /**
     * How long after creation a sandbox stays EDITABLE - chat turns, image and
     * video generation (default 24h). After this window the sandbox flips to
     * read-only: the visitor can still open /try and review everything they
     * built until {@code expiresAt} (activity keeps that at the full
     * {@link #lifetimeDays()}, default 5 days), but every mutating surface
     * refuses softly with a register-to-continue nudge. The point is a clean
     * two-phase funnel: one day of real hands-on use, then several days where
     * the work itself - still visible, no longer extendable - is the reason
     * to register.
     */
    public int editableHours() {
        return platformSettings.getInt("demo.editable-hours", defaultEditableHours);
    }

    /** The instant this sandbox's editable phase ends - {@code createdAt + editableHours}. */
    public Instant editableUntil(DemoSession session) {
        return session.getCreatedAt().plus(Duration.ofHours(editableHours()));
    }

    /**
     * True when the sandbox is past its editable window but not yet expired -
     * the view-only phase. Expiry is deliberately NOT read-only: an expired
     * sandbox has its own (existing) end-state messaging, and conflating the
     * two would show "still viewable" copy for a sandbox that is gone.
     */
    public boolean isReadOnly(DemoSession session, Instant now) {
        return !session.isExpired(now) && !now.isBefore(editableUntil(session));
    }

    /**
     * Appends one full turn (the visitor's message and what was shown back)
     * to the durable transcript the read-only phase renders from. Best-effort
     * by design: the transcript is a replay convenience, and failing a chat
     * turn because its bookkeeping INSERT failed would invert the priorities.
     */
    public void recordTranscriptTurn(DemoSession session, String userMessage, String reply, boolean simulated) {
        try {
            Instant now = Instant.now();
            demoChatMessageRepository.save(
                new DemoChatMessage(session.getId(), "IN", false, userMessage, now));
            demoChatMessageRepository.save(
                new DemoChatMessage(session.getId(), "OUT", simulated, reply, now.plusMillis(1)));
        } catch (RuntimeException e) {
            log.warn("Failed to persist demo transcript turn for sandbox {} - chat unaffected", session.getId(), e);
        }
    }

    /** This sandbox's durable transcript, oldest first - bounded by the message cap. */
    public java.util.List<DemoChatMessage> transcript(DemoSession session) {
        return demoChatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
    }

    /**
     * Provisions a brand-new sandbox. See the class javadoc for ordering and
     * compensation.
     *
     * @param clientIp used for both abuse checks and forensics; never shown
     *     to the visitor.
     * @throws DemoSandboxUnavailableException if the surface is switched off
     *     or this IP already holds too many live sandboxes - both are
     *     deliberately the same exception type, because the visitor-facing
     *     message should not confirm which control they tripped.
     */
    public DemoSessionResponse create(DemoSessionCreateRequest request, String clientIp) {
        if (!isEnabled()) {
            throw new DemoSandboxUnavailableException(
                "The no-signup sandbox is temporarily unavailable. You can still create a free account."
            );
        }
        // Per-IP creation-rate restrictions removed upstream (commit
        // d489b60e) - see the class javadoc's abuse-posture section. Not
        // re-added here: this change's job is "stop minting a tenant per
        // visitor", which it does unconditionally below regardless of IP.

        String businessName = sanitizeBusinessName(request == null ? null : request.businessName());
        // Unknown/absent persona both land on null - a typo'd persona value
        // must degrade to the generic agent, never fail sandbox creation.
        DemoPersona persona = DemoPersona.fromParam(request == null ? null : request.persona()).orElse(null);

        // Every visitor shares the same tenant/schema - see the class
        // javadoc. This resolves instantly except for the very first call
        // ever made against a fresh deployment.
        Tenant tenant = sharedTenant.getOrCreate();
        String schemaName = tenant.getSchemaName();

        String rawToken = DemoTokens.newToken();
        Instant now = Instant.now();
        // Short grace window, not the full lifetime - a sandbox earns the
        // longer demo.lifetime-days deadline only once real activity happens
        // (DemoSession#touchActivity, called from #recordMessage /
        // DemoImageService). One nobody ever returns to retires in
        // demo.inactivity-timeout-hours (default 24h).
        Instant expiresAt = now.plus(Duration.ofHours(inactivityTimeoutHours()));

        DemoSession session;
        try {
            session = recorder.recordSession(
                tenant.getId(), DemoTokens.hash(rawToken), schemaName,
                normalizeEmail(request == null ? null : request.email()), clientIp,
                persona == null ? null : persona.wireName(), now, expiresAt
            );
        } catch (RuntimeException e) {
            // Nothing to roll back here (no schema was touched this call) -
            // the shared tenant/schema is unaffected by one failed session INSERT.
            log.error("Demo session provisioning failed against shared schema {}", schemaName, e);
            throw new DemoSandboxUnavailableException(
                "We couldn't start a sandbox just now. Please try again in a moment."
            );
        }

        // Best-effort: an empty-but-working sandbox is a far better outcome
        // than failing the whole request because one seeding INSERT failed.
        // The chat endpoint re-attempts the seed on first use, so this
        // failing is self-healing rather than permanent. Stamped with this
        // session's own created_by (see DemoAgentSeeding / DemoTenantContextScope)
        // so it is invisible to every OTHER session sharing this schema.
        // A studio sandbox (request.blank) skips the seed: it builds from the
        // visitor's briefing, and a generic starter would only pile up as a
        // duplicate for everyone sharing this network address. Every legacy
        // consumer that does need an agent (chat, share link, Telegram) calls
        // ensureSeededAgent, which seeds on first use, so nothing depends on
        // this eager seed.
        boolean blank = request != null && request.wantsBlank();
        DemoWorkspaceSeeder.SeededAgent seeded =
            blank ? null : agentSeeding.seedAgentQuietly(schemaName, businessName, persona, session.getId());

        return toResponse(session, rawToken, tenant.getName(), seeded, now);
    }

    /**
     * Resolves a demo token to its live session. Returns empty for an
     * unknown, malformed or absent token - deliberately the same empty for
     * all three, so probing cannot distinguish "wrong token" from "expired
     * token" from "no such sandbox".
     *
     * <p>An EXPIRED session is still returned (not empty): the {@code /try}
     * page needs to render "your sandbox has ended, register to keep it"
     * rather than silently losing the visitor. Callers that must not act on
     * an expired session check {@link DemoSession#isExpired} themselves -
     * the chat path (and image/video/channel demo controllers) additionally
     * refuse to act on an expired session directly, since retirement no
     * longer suspends anything tenant-wide now that the demo tenant is
     * shared (see {@link DemoSessionRecorder#retire}).
     */
    public Optional<DemoSession> resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 512) {
            return Optional.empty();
        }
        return demoSessionRepository.findByTokenHash(DemoTokens.hash(rawToken.trim()));
    }

    /**
     * Resolves a sandbox by its own id rather than by token - the entry point
     * the messenger bridge needs, because an inbound Telegram update carries a
     * chat id, never the browser's demo token.
     *
     * <p>Deliberately NOT a widening of the trust model: the only way to
     * obtain a session id in the first place is to already hold a
     * {@link DemoChannelLink} row, and the only way to create one of those is
     * to redeem a single-use pairing code that the sandbox's own browser was
     * shown. See {@link DemoChannelLinkService}'s class javadoc.
     */
    public Optional<DemoSession> findById(UUID sessionId) {
        return sessionId == null ? Optional.empty() : demoSessionRepository.findById(sessionId);
    }

    public DemoSessionResponse status(DemoSession session) {
        Instant now = Instant.now();
        Tenant tenant = tenantRepository.findById(session.getTenantId()).orElse(null);
        return new DemoSessionResponse(
            null, // never re-issued, see DemoSessionResponse#demoToken
            session.getTenantId(),
            tenant != null ? tenant.getName() : "Demo workspace",
            session.getAgentId(),
            null,
            session.getCreatedAt(),
            session.getExpiresAt(),
            session.daysRemaining(now),
            session.getMessageCount(),
            messageLimit(),
            session.getImageCount(),
            demoImageService.perSessionLimit(),
            // canServe, not isEnabled: the flag alone hid the case where the
            // feature is switched on but the platform pool has no provider
            // row - the page would offer a button that can only decline.
            demoImageService.canServe(),
            session.isExpired(now),
            isReadOnly(session, now),
            editableUntil(session),
            session.getPersona()
        );
    }

    /**
     * Ends one visitor's sandbox - used by {@code /session/reset}. Deletes
     * ONLY this session's own row. It must NOT touch the tenant or drop the
     * schema any more: both are shared with every other live demo visitor
     * (see the class javadoc), so suspending or dropping either here would
     * end everyone else's sandbox too. Any {@code agent} row this session
     * seeded is best-effort deleted first (see {@link DemoAgentSeeding#deleteSeededAgentQuietly})
     * so the shared schema's agent count - which every plan's {@code
     * maxAgents} cap counts schema-wide - does not grow without bound as
     * visitors reset their sandboxes.
     */
    public void deleteSession(DemoSession session) {
        try {
            agentSeeding.deleteSeededAgentQuietly(session);
            demoSessionRepository.delete(session);
            log.info("Deleted demo session {} (shared sandbox schema {} retained)", session.getId(), session.getTenantSchema());
        } catch (RuntimeException e) {
            log.error("Failed to delete demo session {}", session.getId(), e);
            throw e;
        }
    }

    /**
     * Runs {@code action} with {@link TenantContext} bound to this sandbox's
     * schema, then always clears it. Every demo request that touches
     * tenant-scoped data MUST go through here - it is the demo equivalent of
     * what {@code TenantContextFilter} does for a JWT request and what
     * {@code WorkflowWebhookController}/{@code McpApiKeyAuthFilter} already
     * do by hand for their own no-JWT entry points. Without it, Hibernate's
     * {@code TenantSchemaResolver} silently falls back to the {@code public}
     * schema and the sandbox appears to work while writing nowhere.
     *
     * <p>Roles are deliberately an EMPTY set - a demo caller holds no
     * {@code tenant-admin} authority, so anything that checks roles refuses
     * it. The only reason that is not the load-bearing control is that the
     * demo token never reaches those endpoints in the first place: Spring
     * Security only permits {@code /api/demo/**} unauthenticated, and every
     * other route still requires a real JWT.
     */
    public <T> T inDemoTenantContext(DemoSession session, java.util.function.Supplier<T> action) {
        return DemoTenantContextScope.call(session.getTenantSchema(), session.getId(), action);
    }

    /**
     * /try Agent Studio: runs {@code action} in this sandbox's schema but as
     * the actor subject of ANOTHER live sandbox from the same IP. The studio
     * lets every session on one network see and edit the agents its
     * neighbours built; {@code ResourceAccessService} only recognises the
     * creating subject as owner, so edits are executed as that subject. The
     * caller is responsible for having checked the IP relationship first
     * ({@link #liveSessionsSharingIp}).
     */
    public <T> T inDemoTenantContextAs(DemoSession session, UUID actorSessionId, java.util.function.Supplier<T> action) {
        return DemoTenantContextScope.call(session.getTenantSchema(), actorSessionId, action);
    }

    /** The {@code created_by} subject stamped on everything a sandbox creates. */
    public static String actorSubject(UUID sessionId) {
        return DEMO_ACTOR_PREFIX + sessionId;
    }

    /** Inverse of {@link #actorSubject}: the session id inside a demo subject, or empty for any other subject. */
    public static Optional<UUID> sessionIdFromSubject(String subject) {
        if (subject == null || !subject.startsWith(DEMO_ACTOR_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(subject.substring(DEMO_ACTOR_PREFIX.length())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Every live sandbox created from the same IP as {@code session},
     * always including {@code session} itself (a visitor whose address
     * changed mid-sandbox must never lose sight of their own agents).
     */
    public List<DemoSession> liveSessionsSharingIp(DemoSession session, Instant now) {
        List<DemoSession> out = new java.util.ArrayList<>();
        out.add(session);
        String ip = session.getCreatedIp();
        if (ip != null && !ip.isBlank()) {
            for (DemoSession other : demoSessionRepository.findLiveForIp(ip, now)) {
                if (!other.getId().equals(session.getId())) {
                    out.add(other);
                }
            }
        }
        return out;
    }

    /**
     * /try Agent Studio "make this the live agent": the agent the share link
     * (/a/{shareId}) and the Telegram bridge answer with. Same column the
     * seeder writes ({@code demo_session.agent_id}); {@code null} is not
     * accepted because a sandbox with no live agent falls back to re-seeding
     * on the next chat turn, which would silently create yet another agent.
     */
    public void setLiveAgent(DemoSession session, UUID agentId) {
        if (agentId == null) {
            throw new IllegalArgumentException("agentId is required");
        }
        recorder.recordSeededAgent(session.getId(), agentId);
        session.recordSeededAgent(agentId);
    }

    /**
     * Empties the live-agent slot (the visitor deleted the agent it pointed
     * at). Callers that read the slot already handle null - see ensureSeededAgent.
     */
    public void clearLiveAgent(DemoSession session) {
        recorder.recordSeededAgent(session.getId(), null);
        session.recordSeededAgent(null);
    }

    /** True when this sandbox has spent its turn allowance. */
    public boolean isMessageCapReached(DemoSession session) {
        return session.getMessageCount() >= messageLimit();
    }

    public void recordMessage(DemoSession session) {
        Instant now = Instant.now();
        recorder.recordMessage(session.getId(), now, now.plus(Duration.ofDays(lifetimeDays())));
    }

    /**
     * Late/repair seeding, called by the demo chat endpoint when a session
     * has no agent yet (see {@link #create}'s best-effort note). Idempotent
     * from the caller's side: it only ever runs when {@code agentId} is null.
     */
    public Optional<UUID> ensureSeededAgent(DemoSession session, String businessName) {
        if (session.getAgentId() != null) {
            return Optional.of(session.getAgentId());
        }
        // The persisted persona (V130), not a request parameter - the repair
        // seed must build the same agent the visitor originally asked for.
        DemoPersona persona = DemoPersona.fromParam(session.getPersona()).orElse(null);
        DemoWorkspaceSeeder.SeededAgent seeded =
            agentSeeding.seedAgentQuietly(session.getTenantSchema(), sanitizeBusinessName(businessName), persona, session.getId());
        return seeded == null ? Optional.empty() : Optional.of(seeded.agentId());
    }

    /**
     * Retires ONE expired sandbox - called by {@link DemoSessionExpiryJob}
     * instead of reaching into {@link DemoSessionRecorder} directly, so the
     * best-effort agent cleanup below travels with every retirement path
     * rather than needing to be remembered at each call site.
     */
    public void retire(DemoSession session, Instant at) {
        agentSeeding.deleteSeededAgentQuietly(session);
        recorder.retire(session.getId(), at);
    }

    private DemoSessionResponse toResponse(
        DemoSession session, String rawToken, String workspaceName,
        DemoWorkspaceSeeder.SeededAgent seeded, Instant now
    ) {
        return new DemoSessionResponse(
            rawToken,
            session.getTenantId(),
            workspaceName,
            seeded != null ? seeded.agentId() : null,
            seeded != null ? seeded.agentName() : null,
            session.getCreatedAt(),
            session.getExpiresAt(),
            session.daysRemaining(now),
            session.getMessageCount(),
            messageLimit(),
            session.getImageCount(),
            demoImageService.perSessionLimit(),
            demoImageService.canServe(),
            false,
            false,
            editableUntil(session),
            session.getPersona()
        );
    }

    /**
     * Strips anything that is not a letter, digit, space, apostrophe or dash
     * before this string is interpolated into the seeded agent's name and
     * system prompt. Not because the prompt is executable, but because an
     * anonymous, unauthenticated free-text field flowing into an LLM system
     * prompt is a prompt-injection surface, and 60 sanitised characters is
     * all the personalisation this feature actually needs.
     */
    private String sanitizeBusinessName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().replaceAll("[^\\p{L}\\p{N} '\\-&.]", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    private String normalizeEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return trimmed.length() > 320 ? null : trimmed;
    }
}
