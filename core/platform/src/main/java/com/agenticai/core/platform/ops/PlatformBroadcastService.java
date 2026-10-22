package com.agenticai.core.platform.ops;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.logging.LogSafe;
import com.agenticai.core.tenancy.PlatformSettingsService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

/**
 * Platform-wide maintenance mode and announcement banner, each stored as ONE JSON document in a
 * {@code control_plane.platform_setting} row (seeded disabled by controlplane/V476), so one PUT is
 * one atomic row write and every replica converges on the next cache refresh.
 *
 * <p><b>Hot path.</b> {@link MaintenanceModeFilter} asks {@link #maintenance()} on every {@code
 * /api/**} request, so reads are served from an in-memory snapshot refreshed at most every {@link
 * #CACHE_TTL} (10 s): at most one {@code platform_setting} read per 10 s per replica, never one per
 * request. A write refreshes this replica's snapshot immediately; other replicas follow within the
 * TTL. A failed read keeps serving the last good snapshot (or the disabled default at boot) - a
 * database blip must not flip the platform into maintenance, nor out of it.
 *
 * <p><b>Writes</b> are made only through {@code PlatformBroadcastController}; the generic {@code
 * PUT /api/platform/settings/{key}} refuses these two keys ({@link #isReservedKey}) so maintenance
 * cannot be switched on without the master key. Who changed what is kept in the row's {@code
 * updated_by} / {@code updated_at} and a {@code platform-ops-audit} log line (there is no
 * control-plane audit table; tenant audit logs are per schema).
 */
@Service
public class PlatformBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(PlatformBroadcastService.class);

    public static final String MAINTENANCE_KEY = "platform.ops.maintenance";
    public static final String ANNOUNCEMENT_KEY = "platform.ops.announcement";
    static final Duration CACHE_TTL = Duration.ofSeconds(10);
    static final int MAX_MESSAGE_CHARS = 1000;
    static final int MAX_URL_CHARS = 2000;
    private static final Set<String> LEVELS = Set.of("info", "warning", "critical");
    private static final ObjectMapper JSON = JsonMappers.isoDates();

    /**
     * {@code GET/PUT /api/platform/maintenance}. {@code startedAt} is set by the server when it is
     * switched on.
     */
    public record MaintenanceState(
            boolean enabled, String message, Instant endsAt, Instant startedAt) {
        static final MaintenanceState OFF = new MaintenanceState(false, "", null, null);
    }

    /** {@code GET/PUT /api/platform/announcement}. */
    public record AnnouncementState(
            boolean enabled, String message, String level, String linkUrl, Instant expiresAt) {
        static final AnnouncementState OFF = new AnnouncementState(false, "", "info", null, null);
    }

    public record MaintenanceUpdate(Boolean enabled, String message, String endsAt) {}

    public record AnnouncementUpdate(
            Boolean enabled, String message, String level, String linkUrl, String expiresAt) {}

    /** Public, unauthenticated view: {@code /api/public/status}'s {@code maintenance} member. */
    public record PublicMaintenance(boolean enabled, String message, Instant endsAt) {}

    /** Public view of a LIVE announcement (null when disabled or expired). */
    public record PublicAnnouncement(
            boolean enabled, String message, String level, String linkUrl, Instant expiresAt) {}

    private record Snapshot(
            Instant readAt, MaintenanceState maintenance, AnnouncementState announcement) {}

    private final PlatformSettingsService settings;
    private final Clock clock;
    private volatile Snapshot snapshot;
    private final ReentrantLock reloadLock = new ReentrantLock();

    @Autowired
    public PlatformBroadcastService(PlatformSettingsService settings) {
        this(settings, Clock.systemUTC());
    }

    PlatformBroadcastService(PlatformSettingsService settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public static boolean isReservedKey(String key) {
        return MAINTENANCE_KEY.equals(key) || ANNOUNCEMENT_KEY.equals(key);
    }

    // ------------------------------------------------------------------ reads

    public MaintenanceState maintenance() {
        return current().maintenance();
    }

    public AnnouncementState announcement() {
        return current().announcement();
    }

    /**
     * True when maintenance is on right now. An {@code endsAt} in the past does NOT end it
     * automatically: the window is advisory text for users, and an operator who overruns must not
     * have the platform silently reopen mid-migration.
     */
    public boolean maintenanceActive() {
        return maintenance().enabled();
    }

    public PublicMaintenance publicMaintenance() {
        MaintenanceState m = maintenance();
        return new PublicMaintenance(
                m.enabled(), m.enabled() ? m.message() : "", m.enabled() ? m.endsAt() : null);
    }

    /** The live announcement, or null when disabled, empty or past {@code expiresAt}. */
    public PublicAnnouncement publicAnnouncement() {
        AnnouncementState a = announcement();
        if (!a.enabled() || a.message() == null || a.message().isBlank()) {
            return null;
        }
        if (a.expiresAt() != null && !clock.instant().isBefore(a.expiresAt())) {
            return null;
        }
        return new PublicAnnouncement(true, a.message(), a.level(), a.linkUrl(), a.expiresAt());
    }

    /**
     * The snapshot, reloaded when older than {@link #CACHE_TTL}. This runs on EVERY {@code /api/**}
     * request (the maintenance filter), on virtual threads, and the reload is a blocking database
     * read, so it must never run under a {@code synchronized} block: on JDK 21 a virtual thread
     * that blocks inside a monitor pins its carrier thread. The old {@code synchronized (this)}
     * made every request that arrived while one thread reloaded block on the monitor and pin a
     * carrier too; with about as many carriers as cores, a burst of in-flight requests at the
     * moment the TTL expired pinned them all, the reloading thread then waited for a database
     * connection held by a request that could not get a carrier to finish, and the pod stopped
     * answering (even its probes) for tens of seconds up to minutes
     * (docs/architecture/HOT-PATH-PERFORMANCE.md section 4d).
     *
     * <p>Now: a {@link ReentrantLock} (parks the virtual thread, pins nothing), and a caller that
     * finds a stale snapshot while another thread is already reloading serves the stale one at once
     * instead of queueing, so at most one request per TTL pays the read and no request waits for
     * it. Only a caller with no snapshot at all (first request, or just after a write) waits for
     * the load.
     */
    private Snapshot current() {
        Snapshot s = snapshot;
        Instant now = clock.instant();
        if (s != null && now.isBefore(s.readAt().plus(CACHE_TTL))) {
            return s;
        }
        if (s != null) {
            if (!reloadLock.tryLock()) {
                return s;
            }
        } else {
            reloadLock.lock();
        }
        try {
            s = snapshot;
            if (s != null && now.isBefore(s.readAt().plus(CACHE_TTL))) {
                return s;
            }
            Snapshot fresh = load(now, s);
            snapshot = fresh;
            return fresh;
        } finally {
            reloadLock.unlock();
        }
    }

    private Snapshot load(Instant now, Snapshot previous) {
        try {
            MaintenanceState m = parseMaintenance(settings.getString(MAINTENANCE_KEY, null));
            AnnouncementState a = parseAnnouncement(settings.getString(ANNOUNCEMENT_KEY, null));
            return new Snapshot(now, m, a);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not read maintenance/announcement settings ({}); serving the last known"
                            + " state",
                    e.getClass().getSimpleName());
            return previous != null
                    ? new Snapshot(now, previous.maintenance(), previous.announcement())
                    : new Snapshot(now, MaintenanceState.OFF, AnnouncementState.OFF);
        }
    }

    // ----------------------------------------------------------------- writes

    public MaintenanceState updateMaintenance(MaintenanceUpdate update, String actor) {
        if (update == null || update.enabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        MaintenanceState before = maintenance();
        String message = cleanMessage(update.message());
        Instant endsAt = parseOptionalInstant(update.endsAt(), "endsAt");
        Instant startedAt = null;
        if (update.enabled()) {
            // Keep the original start when an already-active window is only being re-worded or
            // extended.
            startedAt =
                    before.enabled() && before.startedAt() != null
                            ? before.startedAt()
                            : clock.instant();
        }
        MaintenanceState next = new MaintenanceState(update.enabled(), message, endsAt, startedAt);
        write(MAINTENANCE_KEY, next, actor);
        log.info(
                "platform-ops-audit actor={} action=platform.maintenance.{} endsAt={}",
                LogSafe.clean(actor),
                LogSafe.clean(next.enabled() ? "enable" : "disable"),
                LogSafe.clean(endsAt));
        refresh();
        return maintenance();
    }

    public AnnouncementState updateAnnouncement(AnnouncementUpdate update, String actor) {
        if (update == null || update.enabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        String message = cleanMessage(update.message());
        if (update.enabled() && message.isBlank()) {
            throw new IllegalArgumentException(
                    "message is required when the announcement is enabled");
        }
        String level =
                update.level() == null || update.level().isBlank()
                        ? "info"
                        : update.level().trim().toLowerCase(Locale.ROOT);
        if (!LEVELS.contains(level)) {
            throw new IllegalArgumentException("level must be one of info, warning, critical");
        }
        String linkUrl = validateLinkUrl(update.linkUrl());
        Instant expiresAt = parseOptionalInstant(update.expiresAt(), "expiresAt");
        AnnouncementState next =
                new AnnouncementState(update.enabled(), message, level, linkUrl, expiresAt);
        write(ANNOUNCEMENT_KEY, next, actor);
        log.info(
                "platform-ops-audit actor={} action=platform.announcement.{} level={} expiresAt={}",
                LogSafe.clean(actor),
                LogSafe.clean(next.enabled() ? "publish" : "withdraw"),
                LogSafe.clean(level),
                LogSafe.clean(expiresAt));
        refresh();
        return announcement();
    }

    private void write(String key, Object state, String actor) {
        try {
            settings.update(key, JSON.writeValueAsString(state), actor);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("Could not serialise " + key, e);
        }
    }

    /** Drops this replica's snapshot so the next read reloads it. */
    void refresh() {
        snapshot = null;
    }

    // ---------------------------------------------------------------- parsing

    static MaintenanceState parseMaintenance(String raw) {
        JsonNode n = parse(raw);
        if (n == null) {
            return MaintenanceState.OFF;
        }
        return new MaintenanceState(
                n.path("enabled").asBoolean(false),
                n.path("message").asString(""),
                instantOrNull(n.path("endsAt")),
                instantOrNull(n.path("startedAt")));
    }

    static AnnouncementState parseAnnouncement(String raw) {
        JsonNode n = parse(raw);
        if (n == null) {
            return AnnouncementState.OFF;
        }
        String level = n.path("level").asString("info");
        String link = n.path("linkUrl").isString() ? n.path("linkUrl").asString() : null;
        return new AnnouncementState(
                n.path("enabled").asBoolean(false),
                n.path("message").asString(""),
                LEVELS.contains(level) ? level : "info",
                link == null || link.isBlank() ? null : link,
                instantOrNull(n.path("expiresAt")));
    }

    private static JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(raw);
            return n != null && n.isObject() ? n : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static Instant instantOrNull(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isNumber()) {
            // Jackson's default for Instant is fractional epoch seconds.
            double seconds = n.asDouble();
            return Instant.ofEpochSecond(
                    (long) Math.floor(seconds), Math.round((seconds % 1) * 1_000_000_000L));
        }
        try {
            return Instant.parse(n.asString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String cleanMessage(String message) {
        if (message == null) {
            return "";
        }
        String trimmed = message.trim();
        if (trimmed.length() > MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException(
                    "message can be at most " + MAX_MESSAGE_CHARS + " characters");
        }
        return trimmed;
    }

    /** Null for blank; otherwise must be an absolute https URL with a host. */
    static String validateLinkUrl(String linkUrl) {
        if (linkUrl == null || linkUrl.isBlank()) {
            return null;
        }
        String value = linkUrl.trim();
        if (value.length() > MAX_URL_CHARS) {
            throw new IllegalArgumentException("linkUrl is too long");
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("linkUrl must be an https:// URL");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("linkUrl must be an https:// URL");
        }
        return value;
    }

    static Instant parseOptionalInstant(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(raw.trim()).toInstant();
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException(field + " must be an ISO-8601 timestamp");
            }
        }
    }
}
