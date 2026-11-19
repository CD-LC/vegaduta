package com.agenticai.core.platform.impersonation;

import com.agenticai.core.admin.identity.IdentityUserAdminPort;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.platform.PlatformTenantNotFoundException;
import com.agenticai.core.platform.impersonation.ImpersonationJdbcRepository.Row;
import com.agenticai.core.platform.ops.PlatformTenantAuditor;
import com.agenticai.core.security.ImpersonationGrantResolver;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Platform-admin read-only "view as user" sessions ({@code control_plane.impersonation_session},
 * V477).
 *
 * <p><b>Grant handling.</b> A session is unlocked by a 32-byte random grant (base64url) returned
 * ONCE from {@link #start}. Only its SHA-256 is stored and looked up; the raw grant is never
 * persisted or logged. The request path ({@code ViewAsFilter} in core/security) resolves it through
 * {@link #resolve}, cached for at most {@link #CACHE_TTL} so an ended or expired session stops
 * working within that window on every replica (and immediately on the replica that ended it -
 * {@link #end} evicts).
 *
 * <p><b>Guards at start:</b> reason of at least {@value #MIN_REASON_LENGTH} characters,
 * X-Master-Key (controller), tenant must be ACTIVE and neither erased nor a demo sandbox, the
 * target must belong to that tenant (Keycloak {@code tenant_id} attribute, or its {@code app_user}
 * row), must not hold {@code platform-admin}, and the admin may hold at most {@value
 * #MAX_ACTIVE_PER_ADMIN} active sessions (checked under a per-admin advisory lock so two quick
 * clicks cannot both pass).
 *
 * <p><b>Request counting</b> is in memory and flushed every ~30 s in one batch (and on shutdown) -
 * never a DB write per request.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
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

    private final ImpersonationJdbcRepository repository;
    private final TransactionTemplate tx;
    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final IdentityUserAdminPort keycloak;
    private final PlatformTenantAuditor auditor;
    private final String webPublicUrl;
    private final Clock clock;

    private final Map<String, CachedLookup> cache = new ConcurrentHashMap<>();
    private final Map<UUID, PendingRequests> pending = new ConcurrentHashMap<>();

    private record CachedLookup(Optional<ViewAsSession> session, Instant loadedAt) {}

    private static final class PendingRequests {
        final AtomicLong count = new AtomicLong();
        final AtomicReference<Instant> last = new AtomicReference<>();
    }

    /** POST response. {@code grant} is the only time the raw grant ever leaves the server. */
    public record StartedSession(
            UUID id,
            UUID tenantId,
            String tenantName,
            String userId,
            String userEmail,
            String reason,
            Instant startedAt,
            Instant expiresAt,
            String grant,
            String viewUrl) {}

    public record SessionItem(
            UUID id,
            UUID tenantId,
            String tenantName,
            String userId,
            String userEmail,
            String adminEmail,
            String reason,
            Instant startedAt,
            Instant expiresAt,
            Instant endedAt,
            int requestCount) {}

    public record SessionPage(List<SessionItem> items, long total) {}

    /** {@code GET /api/view-as/session}. */
    public record ViewAsSessionInfo(
            UUID id, String tenantName, String userEmail, Instant expiresAt, boolean readOnly) {}

    @Autowired
    public ImpersonationService(
            ImpersonationJdbcRepository repository,
            PlatformTransactionManager transactionManager,
            TenantRepository tenantRepository,
            AppUserRepository appUserRepository,
            IdentityUserAdminPort keycloak,
            PlatformTenantAuditor auditor,
            @Value("${app.web.public-url:}") String webPublicUrl) {
        this(
                repository,
                new TransactionTemplate(transactionManager),
                tenantRepository,
                appUserRepository,
                keycloak,
                auditor,
                webPublicUrl,
                Clock.systemUTC());
    }

    ImpersonationService(
            ImpersonationJdbcRepository repository,
            TransactionTemplate tx,
            TenantRepository tenantRepository,
            AppUserRepository appUserRepository,
            IdentityUserAdminPort keycloak,
            PlatformTenantAuditor auditor,
            String webPublicUrl,
            Clock clock) {
        this.repository = repository;
        this.tx = tx;
        this.tenantRepository = tenantRepository;
        this.appUserRepository = appUserRepository;
        this.keycloak = keycloak;
        this.auditor = auditor;
        this.webPublicUrl = webPublicUrl;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ start / end / list

    public StartedSession start(
            String adminKeycloakId,
            String adminEmail,
            UUID tenantId,
            String userId,
            String reason) {
        if (adminKeycloakId == null || adminKeycloakId.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "No platform-admin identity on this request.");
        }
        if (tenantId == null || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("tenantId and userId are required.");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.length() < MIN_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "A reason of at least " + MIN_REASON_LENGTH + " characters is required.");
        }
        if (why.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "The reason must be at most " + MAX_REASON_LENGTH + " characters.");
        }
        Tenant tenant =
                tenantRepository
                        .findById(tenantId)
                        .orElseThrow(() -> new PlatformTenantNotFoundException(tenantId));
        if (tenant.getStatus() == TenantStatus.ERASED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This tenant has been erased.");
        }
        if (tenantRepository.isDemoSchema(tenant.getSchemaName()).orElse(false)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Demo sandboxes cannot be viewed as a user.");
        }
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only ACTIVE tenants can be viewed (this one is " + tenant.getStatus() + ").");
        }
        String targetId = userId.trim();
        if (targetId.equals(adminKeycloakId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "You cannot view as yourself.");
        }
        KeycloakUserRepresentation target;
        try {
            target = keycloak.getUserById(targetId);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found.");
        }
        if (!belongsToTenant(target, tenant)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "That user does not belong to this tenant.");
        }
        List<String> roles = keycloak.getUserRealmRoleNames(targetId);
        if (roles.contains(PLATFORM_ADMIN_ROLE)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Platform operators cannot be viewed as.");
        }

        String grant = newGrant();
        String grantHash = sha256Hex(grant);
        Instant startedAt = clock.instant();
        Instant expiresAt = startedAt.plus(SESSION_TTL);
        UUID id = UUID.randomUUID();
        String targetEmail = target.email();
        tx.executeWithoutResult(
                status -> {
                    repository.lockAdmin(adminKeycloakId);
                    long active = repository.countActiveForAdmin(adminKeycloakId, startedAt);
                    if (active >= MAX_ACTIVE_PER_ADMIN) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "You already have "
                                        + MAX_ACTIVE_PER_ADMIN
                                        + " active view-as sessions. End one before starting another.");
                    }
                    repository.insert(
                            id,
                            adminKeycloakId,
                            adminEmail,
                            tenant.getId(),
                            tenant.getSchemaName(),
                            targetId,
                            targetEmail,
                            String.join(",", roles),
                            why,
                            grantHash,
                            startedAt,
                            expiresAt);
                });
        auditor.record(
                tenant.getSchemaName(),
                adminKeycloakId,
                "platform.impersonation.start",
                "user",
                targetId,
                "Platform admin "
                        + display(adminEmail, adminKeycloakId)
                        + " started a read-only view-as session "
                        + id
                        + " as "
                        + display(targetEmail, targetId)
                        + " until "
                        + expiresAt
                        + ". Reason: "
                        + why);
        return new StartedSession(
                id,
                tenant.getId(),
                tenant.getName(),
                targetId,
                targetEmail,
                why,
                startedAt,
                expiresAt,
                grant,
                viewUrl(grant));
    }

    /** Idempotent: ending an already-ended session is a no-op (no second audit row). */
    public void end(UUID id, String actorKeycloakId, String actorEmail) {
        Row row =
                findById(id)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "View-as session not found."));
        int updated = repository.end(id, clock.instant());
        evict(id);
        if (updated == 0) {
            return;
        }
        flushOne(id);
        auditor.record(
                row.tenantSchema(),
                actorKeycloakId,
                "platform.impersonation.end",
                "user",
                row.targetKeycloakId(),
                "Platform admin "
                        + display(actorEmail, actorKeycloakId)
                        + " ended view-as session "
                        + id
                        + " (started by "
                        + display(row.adminEmail(), row.adminKeycloakId())
                        + "). Reason given at start: "
                        + row.reason());
    }

    /**
     * @param page 1-based.
     */
    public SessionPage list(Boolean activeOnly, Integer page, Integer limit) {
        int safePage = page == null || page < 1 ? 1 : page;
        int safeLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int offset = (safePage - 1) * safeLimit;
        Instant now = clock.instant();
        boolean active = Boolean.TRUE.equals(activeOnly);
        List<Row> rows = repository.list(active, now, safeLimit, offset);
        long total = repository.count(active, now);
        List<SessionItem> items = new ArrayList<>(rows.size());
        for (Row r : rows) {
            SessionItem item = r.toItem();
            PendingRequests p = pending.get(r.id());
            if (p != null) {
                item =
                        new SessionItem(
                                item.id(),
                                item.tenantId(),
                                item.tenantName(),
                                item.userId(),
                                item.userEmail(),
                                item.adminEmail(),
                                item.reason(),
                                item.startedAt(),
                                item.expiresAt(),
                                item.endedAt(),
                                (int)
                                        Math.min(
                                                Integer.MAX_VALUE,
                                                item.requestCount() + p.count.get()));
            }
            items.add(item);
        }
        return new SessionPage(items, total);
    }

    /**
     * {@code GET /api/view-as/session}: the session behind the CURRENT (already validated) view-as
     * request.
     */
    public Optional<ViewAsSessionInfo> info(UUID sessionId) {
        return findById(sessionId)
                .map(
                        r ->
                                new ViewAsSessionInfo(
                                        r.id(),
                                        r.tenantName(),
                                        r.targetEmail(),
                                        r.expiresAt(),
                                        true));
    }

    // ------------------------------------------------------------------ request path
    // (ViewAsFilter)

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
        List<Row> rows = repository.findByGrantHash(hash);
        Optional<ViewAsSession> found =
                rows.stream()
                        .filter(
                                r ->
                                        MessageDigest.isEqual(
                                                r.grantHash().getBytes(StandardCharsets.US_ASCII),
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

    @AllowUnlockedSchedule(
            reason =
                    "Flushes THIS JVM's in-memory view-as request counters; every replica drains its own.")
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
            log.warn(
                    "view-as request counters could not be flushed on shutdown ({})",
                    e.getClass().getSimpleName());
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
            repository.flushRequests(id, count, last);
        } catch (RuntimeException e) {
            // Put the counts back for the next tick rather than losing them.
            PendingRequests back = pending.computeIfAbsent(id, k -> new PendingRequests());
            back.count.addAndGet(count);
            back.last.accumulateAndGet(last, (a, b) -> a == null || b.isAfter(a) ? b : a);
            log.warn(
                    "view-as request counter flush failed for session {} ({})",
                    id,
                    e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ helpers

    Optional<Row> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return repository.findById(id);
    }

    private void evict(UUID id) {
        cache.entrySet()
                .removeIf(e -> e.getValue().session().map(s -> s.id().equals(id)).orElse(false));
    }

    private boolean belongsToTenant(KeycloakUserRepresentation user, Tenant tenant) {
        List<String> attr =
                user.attributes() == null
                        ? List.of()
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
     * {@code <app.web.public-url>/view-as#grant=<grant>} - the grant rides in the fragment so it
     * never reaches a server log or a Referer. Null when the public URL is unset or points at a
     * local host: a localhost link handed to an operator is always wrong (engineering rule 4).
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
            if (h.equals("localhost")
                    || h.equals("127.0.0.1")
                    || h.equals("[::1]")
                    || h.equals("::1")
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

}
