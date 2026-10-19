package com.agenticai.core.platform.impersonation;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.platform.PlatformTenantNotFoundException;
import com.agenticai.core.platform.ops.PlatformTenantAuditor;
import com.agenticai.core.security.ImpersonationGrantResolver;
import com.agenticai.core.tenancy.AllowUnlockedSchedule;
import com.agenticai.core.tenancy.AppUser;
import com.agenticai.core.tenancy.AppUserRepository;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantStatus;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Platform-admin read-only "view as user" sessions ({@code control_plane.impersonation_session}, V477).
 *
 * <p><b>Grant handling.</b> A session is unlocked by a 32-byte random grant
 * (base64url) returned ONCE from {@link #start}. Only its SHA-256 is stored and
 * looked up; the raw grant is never persisted or logged. The request path
 * ({@code ViewAsFilter} in core/security) resolves it through
 * {@link #resolve}, cached for at most {@link #CACHE_TTL} so an ended or
 * expired session stops working within that window on every replica (and
 * immediately on the replica that ended it - {@link #end} evicts).
 *
 * <p><b>Guards at start:</b> reason of at least {@value #MIN_REASON_LENGTH}
 * characters, X-Master-Key (controller), tenant must be ACTIVE and neither
 * erased nor a demo sandbox, the target must belong to that tenant (Keycloak
 * {@code tenant_id} attribute, or its {@code app_user} row), must not hold
 * {@code platform-admin}, and the admin may hold at most
 * {@value #MAX_ACTIVE_PER_ADMIN} active sessions (checked under a
 * per-admin advisory lock so two quick clicks cannot both pass).
 *
 * <p><b>Request counting</b> is in memory and flushed every ~30 s in one batch
 * (and on shutdown) - never a DB write per request.
 */
@Service
@Profile("security")
public class ImpersonationService implements ImpersonationGrantResolver {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationService.class);

    static final Duration SESSION_TTL = Duration.ofMinutes(30);
    static final Duration CACHE_TTL = Duration.ofSeconds(15);
    static final int MAX_ACTIVE_PER_ADMIN = 3;
    static final int MIN_REASON_LENGTH = 10;
    static final int MAX_REASON_LENGTH = 1000;
    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 100;
    private static final int MAX_CACHE_ENTRIES = 10_000;
    private static final String PLATFORM_ADMIN_ROLE = "platform-admin";
    private static final SecureRandom RANDOM = new SecureRandom();

    static final String SELECT_COLUMNS = "s.id, s.admin_keycloak_id, s.admin_email, s.tenant_id, s.tenant_schema, "
        + "s.target_keycloak_id, s.target_email, s.target_roles, s.reason, s.grant_hash, s.started_at, s.expires_at, "
        + "s.ended_at, s.request_count, t.name AS tenant_name";
    static final String FROM = " FROM control_plane.impersonation_session s "
        + "LEFT JOIN control_plane.tenant t ON t.id = s.tenant_id";
    static final String SQL_BY_HASH = "SELECT " + SELECT_COLUMNS + FROM + " WHERE s.grant_hash = ?";
    static final String SQL_BY_ID = "SELECT " + SELECT_COLUMNS + FROM + " WHERE s.id = ?";
    static final String SQL_LOCK_ADMIN = "SELECT pg_advisory_xact_lock(hashtext(?))";
    static final String SQL_COUNT_ACTIVE_FOR_ADMIN = "SELECT COUNT(*) FROM control_plane.impersonation_session "
        + "WHERE admin_keycloak_id = ? AND ended_at IS NULL AND expires_at > ?";
    static final String SQL_INSERT = "INSERT INTO control_plane.impersonation_session (id, admin_keycloak_id, "
        + "admin_email, tenant_id, tenant_schema, target_keycloak_id, target_email, target_roles, reason, grant_hash, "
        + "started_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    static final String SQL_END = "UPDATE control_plane.impersonation_session SET ended_at = ? "
        + "WHERE id = ? AND ended_at IS NULL";
    static final String SQL_FLUSH_REQUESTS = "UPDATE control_plane.impersonation_session "
        + "SET request_count = request_count + ?, last_request_at = GREATEST(COALESCE(last_request_at, ?), ?) "
        + "WHERE id = ?";
    static final String ACTIVE_PREDICATE = " WHERE s.ended_at IS NULL AND s.expires_at > ?";
    static final String SQL_LIST_ACTIVE = "SELECT " + SELECT_COLUMNS + FROM + ACTIVE_PREDICATE
        + " ORDER BY s.started_at DESC LIMIT ? OFFSET ?";
    static final String SQL_LIST_ALL = "SELECT " + SELECT_COLUMNS + FROM + " ORDER BY s.started_at DESC LIMIT ? OFFSET ?";
    static final String SQL_COUNT_ACTIVE = "SELECT COUNT(*) FROM control_plane.impersonation_session s" + ACTIVE_PREDICATE;
    static final String SQL_COUNT_ALL = "SELECT COUNT(*) FROM control_plane.impersonation_session";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final IdentityUserAdminPort keycloak;
    private final PlatformTenantAuditor auditor;
    private final String webPublicUrl;
    private final Clock clock;

    private final Map<String, CachedLookup> cache = new ConcurrentHashMap<>();
    private final Map<UUID, PendingRequests> pending = new ConcurrentHashMap<>();

    private record CachedLookup(Optional<ViewAsSession> session, Instant loadedAt) {
    }

    private static final class PendingRequests {
        final AtomicLong count = new AtomicLong();
        final AtomicReference<Instant> last = new AtomicReference<>();
    }

    /** POST response. {@code grant} is the only time the raw grant ever leaves the server. */
    public record StartedSession(UUID id, UUID tenantId, String tenantName, String userId, String userEmail,
                                 String reason, Instant startedAt, Instant expiresAt, String grant, String viewUrl) {
    }

    public record SessionItem(UUID id, UUID tenantId, String tenantName, String userId, String userEmail,
                              String adminEmail, String reason, Instant startedAt, Instant expiresAt,
                              Instant endedAt, int requestCount) {
    }

    public record SessionPage(List<SessionItem> items, long total) {
    }

    /** {@code GET /api/view-as/session}. */
    public record ViewAsSessionInfo(UUID id, String tenantName, String userEmail, Instant expiresAt, boolean readOnly) {
    }

    /** Full row (internal). */
    record Row(UUID id, String adminKeycloakId, String adminEmail, UUID tenantId, String tenantSchema,
               String tenantName, String targetKeycloakId, String targetEmail, List<String> targetRoles,
               String reason, String grantHash, Instant startedAt, Instant expiresAt, Instant endedAt,
               int requestCount) {

        ViewAsSession toSession() {
            return new ViewAsSession(id, adminKeycloakId, adminEmail, tenantId, tenantSchema, tenantName,
                targetKeycloakId, targetEmail, targetRoles, expiresAt, endedAt);
        }

        SessionItem toItem() {
            return new SessionItem(id, tenantId, tenantName, targetKeycloakId, targetEmail, adminEmail, reason,
                startedAt, expiresAt, endedAt, requestCount);
        }
    }

    @Autowired
    public ImpersonationService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                TenantRepository tenantRepository, AppUserRepository appUserRepository,
                                IdentityUserAdminPort keycloak, PlatformTenantAuditor auditor,
                                @Value("${app.web.public-url:}") String webPublicUrl) {
        this(jdbc, new TransactionTemplate(transactionManager), tenantRepository, appUserRepository, keycloak,
            auditor, webPublicUrl, Clock.systemUTC());
    }

    ImpersonationService(JdbcTemplate jdbc, TransactionTemplate tx, TenantRepository tenantRepository,
                         AppUserRepository appUserRepository, IdentityUserAdminPort keycloak,
                         PlatformTenantAuditor auditor, String webPublicUrl, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantRepository = tenantRepository;
        this.appUserRepository = appUserRepository;
        this.keycloak = keycloak;
        this.auditor = auditor;
        this.webPublicUrl = webPublicUrl;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ start / end / list

    public StartedSession start(String adminKeycloakId, String adminEmail, UUID tenantId, String userId, String reason) {
        if (adminKeycloakId == null || adminKeycloakId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No platform-admin identity on this request.");
        }
        if (tenantId == null || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("tenantId and userId are required.");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.length() < MIN_REASON_LENGTH) {
            throw new IllegalArgumentException("A reason of at least " + MIN_REASON_LENGTH + " characters is required.");
        }
        if (why.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("The reason must be at most " + MAX_REASON_LENGTH + " characters.");
        }
        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow(() -> new PlatformTenantNotFoundException(tenantId));
        if (tenant.getStatus() == TenantStatus.ERASED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This tenant has been erased.");
        }
        if (tenantRepository.isDemoSchema(tenant.getSchemaName()).orElse(false)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Demo sandboxes cannot be viewed as a user.");
        }
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only ACTIVE tenants can be viewed (this one is " + tenant.getStatus() + ").");
        }
        String targetId = userId.trim();
        if (targetId.equals(adminKeycloakId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot view as yourself.");
        }
        KeycloakUserRepresentation target;
        try {
            target = keycloak.getUserById(targetId);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found.");
        }
        if (!belongsToTenant(target, tenant)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That user does not belong to this tenant.");
        }
        List<String> roles = keycloak.getUserRealmRoleNames(targetId);
        if (roles.contains(PLATFORM_ADMIN_ROLE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform operators cannot be viewed as.");
        }

        String grant = newGrant();
        String grantHash = sha256Hex(grant);
        Instant startedAt = clock.instant();
        Instant expiresAt = startedAt.plus(SESSION_TTL);
        UUID id = UUID.randomUUID();
        String targetEmail = target.email();
        tx.executeWithoutResult(status -> {
            jdbc.query(SQL_LOCK_ADMIN, rs -> null, adminKeycloakId);
            Long active = jdbc.queryForObject(SQL_COUNT_ACTIVE_FOR_ADMIN, Long.class, adminKeycloakId,
                Timestamp.from(startedAt));
            if (active != null && active >= MAX_ACTIVE_PER_ADMIN) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have " + MAX_ACTIVE_PER_ADMIN
                    + " active view-as sessions. End one before starting another.");
            }
            jdbc.update(SQL_INSERT, id, adminKeycloakId, adminEmail, tenant.getId(), tenant.getSchemaName(), targetId,
                targetEmail, String.join(",", roles), why, grantHash, Timestamp.from(startedAt),
                Timestamp.from(expiresAt));
        });
        auditor.record(tenant.getSchemaName(), adminKeycloakId, "platform.impersonation.start", "user", targetId,
            "Platform admin " + display(adminEmail, adminKeycloakId) + " started a read-only view-as session "
                + id + " as " + display(targetEmail, targetId) + " until " + expiresAt + ". Reason: " + why);
        return new StartedSession(id, tenant.getId(), tenant.getName(), targetId, targetEmail, why, startedAt,
            expiresAt, grant, viewUrl(grant));
    }

    /** Idempotent: ending an already-ended session is a no-op (no second audit row). */
    public void end(UUID id, String actorKeycloakId, String actorEmail) {
        Row row = findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "View-as session not found."));
        int updated = jdbc.update(SQL_END, Timestamp.from(clock.instant()), id);
        evict(id);
        if (updated == 0) {
            return;
        }
        flushOne(id);
        auditor.record(row.tenantSchema(), actorKeycloakId, "platform.impersonation.end", "user",
            row.targetKeycloakId(), "Platform admin " + display(actorEmail, actorKeycloakId)
                + " ended view-as session " + id + " (started by " + display(row.adminEmail(), row.adminKeycloakId())
                + "). Reason given at start: " + row.reason());
    }

    /** @param page 1-based. */
    public SessionPage list(Boolean activeOnly, Integer page, Integer limit) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safeLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int offset = (safePage - 1) * safeLimit;
        Timestamp now = Timestamp.from(clock.instant());
        boolean active = Boolean.TRUE.equals(activeOnly);
        List<Row> rows = active
            ? jdbc.query(SQL_LIST_ACTIVE, ROW_MAPPER, now, safeLimit, offset)
            : jdbc.query(SQL_LIST_ALL, ROW_MAPPER, safeLimit, offset);
        Long total = active ? jdbc.queryForObject(SQL_COUNT_ACTIVE, Long.class, now)
            : jdbc.queryForObject(SQL_COUNT_ALL, Long.class);
        List<SessionItem> items = new ArrayList<>(rows.size());
        for (Row r : rows) {
            SessionItem item = r.toItem();
            PendingRequests p = pending.get(r.id());
            if (p != null) {
                item = new SessionItem(item.id(), item.tenantId(), item.tenantName(), item.userId(), item.userEmail(),
                    item.adminEmail(), item.reason(), item.startedAt(), item.expiresAt(), item.endedAt(),
                    (int) Math.min(Integer.MAX_VALUE, item.requestCount() + p.count.get()));
            }
            items.add(item);
        }
        return new SessionPage(items, total == null ? 0 : total);
    }

    /** {@code GET /api/view-as/session}: the session behind the CURRENT (already validated) view-as request. */
    public Optional<ViewAsSessionInfo> info(UUID sessionId) {
        return findById(sessionId).map(r -> new ViewAsSessionInfo(r.id(), r.tenantName(), r.targetEmail(),
            r.expiresAt(), true));
    }

    // ------------------------------------------------------------------ request path (ViewAsFilter)

    @Override
    public Optional<ViewAsSession> resolve(String rawGrant) {
        if (rawGrant == null || rawGrant.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256Hex(rawGrant.trim());
        Instant now = clock.instant();
        CachedLookup cached = cache.get(hash);
        if (cached != null && cached.loadedAt().plus(CACHE_TTL).isAfter(now)) {
            return cached.session();
        }
        List<Row> rows = jdbc.query(SQL_BY_HASH, ROW_MAPPER, hash);
        Optional<ViewAsSession> found = rows.stream()
            .filter(r -> MessageDigest.isEqual(r.grantHash().getBytes(StandardCharsets.US_ASCII),
                hash.getBytes(StandardCharsets.US_ASCII)))
            .findFirst()
            .map(Row::toSession);
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(hash, new CachedLookup(found, now));
        return found;
    }

    @Override
    public void recordRequest(UUID sessionId) {
        if (sessionId == null) {
            return;
        }
        PendingRequests p = pending.computeIfAbsent(sessionId, k -> new PendingRequests());
        p.count.incrementAndGet();
        p.last.set(clock.instant());
    }

    @AllowUnlockedSchedule(reason = "Flushes THIS JVM's in-memory view-as request counters; every replica drains its own.")
    @Scheduled(fixedDelayString = "${app.impersonation.request-flush-ms:30000}")
    public void flushRequestCounts() {
        for (UUID id : List.copyOf(pending.keySet())) {
            flushOne(id);
        }
    }

    @PreDestroy
    void flushOnShutdown() {
        try {
            flushRequestCounts();
        } catch (RuntimeException e) {
            log.warn("view-as request counters could not be flushed on shutdown ({})", e.getClass().getSimpleName());
        }
    }

    private void flushOne(UUID id) {
        PendingRequests p = pending.remove(id);
        if (p == null) {
            return;
        }
        long count = p.count.get();
        Instant last = p.last.get();
        if (count <= 0 || last == null) {
            return;
        }
        try {
            jdbc.update(SQL_FLUSH_REQUESTS, count, Timestamp.from(last), Timestamp.from(last), id);
        } catch (RuntimeException e) {
            // Put the counts back for the next tick rather than losing them.
            PendingRequests back = pending.computeIfAbsent(id, k -> new PendingRequests());
            back.count.addAndGet(count);
            back.last.accumulateAndGet(last, (a, b) -> a == null || b.isAfter(a) ? b : a);
            log.warn("view-as request counter flush failed for session {} ({})", id, e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ helpers

    Optional<Row> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.query(SQL_BY_ID, ROW_MAPPER, id).stream().findFirst();
    }

    private void evict(UUID id) {
        cache.entrySet().removeIf(e -> e.getValue().session().map(s -> s.id().equals(id)).orElse(false));
    }

    private boolean belongsToTenant(KeycloakUserRepresentation user, Tenant tenant) {
        List<String> attr = user.attributes() == null ? List.of()
            : user.attributes().getOrDefault("tenant_id", List.of());
        Optional<AppUser> appUser = appUserRepository.findByKeycloakUserId(user.id());
        if (appUser.isPresent() && !tenant.getId().equals(appUser.get().getTenantId())) {
            return false;
        }
        if (!attr.isEmpty()) {
            return attr.contains(tenant.getSchemaName());
        }
        return appUser.isPresent();
    }

    /**
     * {@code <app.web.public-url>/view-as#grant=<grant>} - the grant rides in the
     * fragment so it never reaches a server log or a Referer. Null when the
     * public URL is unset or points at a local host: a localhost link handed to
     * an operator is always wrong (CLAUDE.md RULE 0).
     */
    String viewUrl(String grant) {
        if (webPublicUrl == null || webPublicUrl.isBlank()) {
            return null;
        }
        String base = webPublicUrl.trim();
        try {
            String host = URI.create(base).getHost();
            if (host == null) {
                return null;
            }
            String h = host.toLowerCase(Locale.ROOT);
            if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1")
                || h.endsWith(".localhost")) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/view-as#grant=" + grant;
    }

    static String newGrant() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String display(String email, String id) {
        return email == null || email.isBlank() ? id : email;
    }

    static final RowMapper<Row> ROW_MAPPER = (ResultSet rs, int n) -> new Row(
        rs.getObject("id", UUID.class),
        rs.getString("admin_keycloak_id"),
        rs.getString("admin_email"),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("tenant_schema"),
        rs.getString("tenant_name"),
        rs.getString("target_keycloak_id"),
        rs.getString("target_email"),
        roles(rs.getString("target_roles")),
        rs.getString("reason"),
        rs.getString("grant_hash"),
        instant(rs, "started_at"),
        instant(rs, "expires_at"),
        instant(rs, "ended_at"),
        rs.getInt("request_count")
    );

    private static List<String> roles(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
