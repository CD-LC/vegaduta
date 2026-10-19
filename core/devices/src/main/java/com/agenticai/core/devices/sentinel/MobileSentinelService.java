package com.agenticai.core.devices.sentinel;

import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mobile Sentinels backend - see the Mobile Sentinels design (opt-in sync, privacy-first defaults; described below).
 *
 * <p>Sentinels run on the phone; core only stores what the tenant policy lets
 * the device upload. The policy is privacy-first by default (sync OFF, raw
 * readings OFF, caregiver escalation OFF) and only a tenant/platform admin
 * can change it. Every user reads and erases only their own events; the
 * admin view is aggregate counts, never content or user ids.
 *
 * <p><b>Ingest</b> validates per event: a bad event lands in
 * {@code rejected} with a reason and the rest of the batch still goes in (a
 * phone flushing a backlog shouldn't lose 99 good events to one bad one).
 * Only request-level problems (missing body, more than {@value #MAX_EVENTS}
 * events) are a 400. A duplicate {@code clientEventId} counts as accepted
 * and never re-escalates.
 *
 * <p><b>Escalation</b> needs all of: an {@code escalate} block, severity
 * &gt;= ALERT, {@code caregiverEscalationEnabled}, and no escalation for the
 * same (user, sentinelId) in the last 30 minutes. It is best-effort: a plain
 * text email to the caregiver (skipped when no {@link JavaMailSender} is
 * configured or the address is invalid) plus a SAFETY bell notification to
 * the user. Raw readings are never put in the email.
 *
 * <p>Deliberately NOT {@code @Transactional} (same posture as
 * WorkflowCommentService): each event is its own single-row save, so a
 * unique-key race on one duplicate can't roll back the rest of the batch.
 */
@Service
public class MobileSentinelService {

    private static final Logger log = LoggerFactory.getLogger(MobileSentinelService.class);

    static final int MAX_EVENTS = 100;
    static final int MAX_TITLE = 200;
    static final int MAX_SUMMARY = 4000;
    static final int MAX_CLIENT_EVENT_ID = 64;
    static final int MAX_SENTINEL_ID = 64;
    static final int MAX_SENTINEL_NAME = 160;
    static final int MAX_TEMPLATE = 64;
    static final int MAX_DEVICE_LABEL = 120;
    static final int MAX_FINDINGS_JSON = 64 * 1024;
    static final int MAX_READINGS_JSON = 512 * 1024;
    static final int DEFAULT_RETENTION_DAYS = 30;
    static final int MAX_RETENTION_DAYS = 3650;
    static final int SUMMARY_WINDOW_DAYS = 7;
    static final Duration ESCALATION_COOLDOWN = Duration.ofMinutes(30);
    /**
     * Hard cap on caregiver escalations (emails) per user per hour, across ALL sentinel ids. The
     * 30-minute cooldown is keyed by the device-supplied sentinelId, so on its own a client could
     * rotate ids to make the platform mail relay send unlimited messages to an arbitrary address.
     */
    static final int MAX_ESCALATIONS_PER_USER_PER_HOUR = 5;
    static final String DISCLAIMER = "This is an automated wellness notice from an app that is not a medical device. "
        + "If this may be an emergency, call local emergency services.";

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final MobileSentinelPolicyRepository policies;
    private final MobileSentinelEventRepository events;
    private final ObjectProvider<NotificationService> notifications;
    private final ObjectProvider<JavaMailSender> mailSenders;
    private final ObjectMapper objectMapper;
    private final String fromAddress;

    public MobileSentinelService(
        MobileSentinelPolicyRepository policies, MobileSentinelEventRepository events,
        ObjectProvider<NotificationService> notifications, ObjectProvider<JavaMailSender> mailSenders,
        ObjectMapper objectMapper,
        @Value("${spring.mail.username:no-reply@example.com}") String fromAddress
    ) {
        this.policies = policies;
        this.events = events;
        this.notifications = notifications;
        this.mailSenders = mailSenders;
        this.objectMapper = objectMapper;
        this.fromAddress = fromAddress;
    }

    // --- wire shapes ----------------------------------------------------------

    public record PolicyView(
        boolean enabled, boolean syncEnabled, boolean allowRawReadings, List<String> allowedKinds,
        boolean caregiverEscalationEnabled, int retentionDays, String updatedBy, Instant updatedAt
    ) {
    }

    /** PUT body. A null field keeps its current value. */
    public record PolicyRequest(
        Boolean enabled, Boolean syncEnabled, Boolean allowRawReadings, List<String> allowedKinds,
        Boolean caregiverEscalationEnabled, Integer retentionDays
    ) {
    }

    public record Escalate(String caregiverName, String caregiverEmail) {
    }

    public record EventIn(
        String clientEventId, String sentinelId, String sentinelName, String template, String severity,
        String title, String summary, List<String> kinds, JsonNode findings, JsonNode readings,
        Instant occurredAt, Escalate escalate
    ) {
    }

    public record IngestRequest(String deviceLabel, List<EventIn> events) {
    }

    public record Rejection(String clientEventId, String reason) {
    }

    public record IngestResult(
        int accepted, int duplicates, List<Rejection> rejected, int escalated, boolean readingsDropped
    ) {
    }

    public record EventView(
        java.util.UUID id, String clientEventId, String sentinelId, String sentinelName, String template,
        String severity, String title, String summary, List<String> kinds, JsonNode findings, JsonNode readings,
        String deviceLabel, Instant occurredAt, Instant receivedAt, Instant escalatedAt, String escalatedTo
    ) {
    }

    public record AdminSummary(
        int windowDays, long activeUsers, long events, Map<String, Long> bySeverity, long escalations
    ) {
    }

    // --- policy ---------------------------------------------------------------

    public PolicyView getPolicy() {
        requireUser();
        return view(policies.findById(MobileSentinelPolicy.SINGLETON_ID).orElse(null));
    }

    public PolicyView updatePolicy(PolicyRequest request) {
        String userId = requireAdmin();
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Policy body is required.");
        }
        MobileSentinelPolicy current = policies.findById(MobileSentinelPolicy.SINGLETON_ID).orElse(null);
        PolicyView base = view(current);

        int retention = request.retentionDays() == null ? base.retentionDays() : request.retentionDays();
        if (retention < 1 || retention > MAX_RETENTION_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "retentionDays must be between 1 and " + MAX_RETENTION_DAYS + ".");
        }
        String kinds;
        if (request.allowedKinds() == null) {
            kinds = current == null ? null : current.getAllowedKinds();
        } else {
            Set<SentinelKind> parsed = EnumSet.noneOf(SentinelKind.class);
            for (String k : request.allowedKinds()) {
                SentinelKind kind = parseKind(k);
                if (kind == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown sentinel kind: " + k);
                }
                parsed.add(kind);
            }
            kinds = joinKinds(parsed);
        }

        MobileSentinelPolicy policy = current != null ? current : new MobileSentinelPolicy(
            base.enabled(), base.syncEnabled(), base.allowRawReadings(), null,
            base.caregiverEscalationEnabled(), base.retentionDays());
        policy.update(
            request.enabled() == null ? base.enabled() : request.enabled(),
            request.syncEnabled() == null ? base.syncEnabled() : request.syncEnabled(),
            request.allowRawReadings() == null ? base.allowRawReadings() : request.allowRawReadings(),
            kinds,
            request.caregiverEscalationEnabled() == null
                ? base.caregiverEscalationEnabled() : request.caregiverEscalationEnabled(),
            retention, userId);
        return view(policies.save(policy));
    }

    // --- ingest ---------------------------------------------------------------

    public IngestResult ingest(IngestRequest request) {
        String userId = requireUser();
        MobileSentinelPolicy policy = policies.findById(MobileSentinelPolicy.SINGLETON_ID).orElse(null);
        PolicyView p = view(policy);
        if (!p.enabled() || !p.syncEnabled()) {
            throw new SentinelSyncDisabledException();
        }
        if (request == null || request.events() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "events is required.");
        }
        if (request.events().size() > MAX_EVENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "At most " + MAX_EVENTS + " events per call.");
        }

        purgeExpired(p.retentionDays());

        Set<SentinelKind> allowed = toKindSet(p.allowedKinds());
        String deviceLabel = truncate(clean(request.deviceLabel()), MAX_DEVICE_LABEL);
        int accepted = 0;
        int duplicates = 0;
        int escalated = 0;
        boolean readingsDropped = false;
        List<Rejection> rejected = new ArrayList<>();
        Set<String> seenInBatch = new HashSet<>();

        for (EventIn in : request.events()) {
            if (in == null) {
                rejected.add(new Rejection(null, "invalid-event"));
                continue;
            }
            String clientEventId = clean(in.clientEventId());
            if (clientEventId == null || clientEventId.length() > MAX_CLIENT_EVENT_ID) {
                rejected.add(new Rejection(in.clientEventId(), "invalid-client-event-id"));
                continue;
            }
            if (!seenInBatch.add(clientEventId) || events.existsByUserIdAndClientEventId(userId, clientEventId)) {
                accepted++;
                duplicates++;
                continue;
            }
            SentinelSeverity severity = parseSeverity(in.severity());
            if (severity == null) {
                rejected.add(new Rejection(clientEventId, "invalid-severity"));
                continue;
            }
            if (in.occurredAt() == null) {
                rejected.add(new Rejection(clientEventId, "invalid-occurred-at"));
                continue;
            }
            String sentinelId = clean(in.sentinelId());
            if (sentinelId != null && sentinelId.length() > MAX_SENTINEL_ID) {
                rejected.add(new Rejection(clientEventId, "invalid-sentinel-id"));
                continue;
            }
            Set<SentinelKind> kinds = EnumSet.noneOf(SentinelKind.class);
            boolean unknownKind = false;
            for (String k : in.kinds() == null ? List.<String>of() : in.kinds()) {
                SentinelKind kind = parseKind(k);
                if (kind == null) {
                    unknownKind = true;
                    break;
                }
                kinds.add(kind);
            }
            if (unknownKind) {
                rejected.add(new Rejection(clientEventId, "unknown-kind"));
                continue;
            }
            if (!allowed.containsAll(kinds)) {
                rejected.add(new Rejection(clientEventId, "kind-not-allowed"));
                continue;
            }

            String findingsJson = toJson(in.findings());
            if (findingsJson != null && findingsJson.length() > MAX_FINDINGS_JSON) {
                rejected.add(new Rejection(clientEventId, "too-large"));
                continue;
            }
            String readingsJson = null;
            if (hasContent(in.readings())) {
                if (!p.allowRawReadings()) {
                    readingsDropped = true;
                } else {
                    JsonNode kept = keepAllowedReadings(in.readings(), allowed);
                    if (kept.size() < in.readings().size()) {
                        readingsDropped = true;
                    }
                    readingsJson = kept.isEmpty() ? null : toJson(kept);
                    if (readingsJson != null && readingsJson.length() > MAX_READINGS_JSON) {
                        rejected.add(new Rejection(clientEventId, "too-large"));
                        continue;
                    }
                }
            }

            MobileSentinelEvent event = new MobileSentinelEvent(
                userId, clientEventId, sentinelId,
                truncate(clean(in.sentinelName()), MAX_SENTINEL_NAME),
                truncate(clean(in.template()), MAX_TEMPLATE),
                severity.name(),
                truncate(clean(in.title()), MAX_TITLE),
                truncate(in.summary(), MAX_SUMMARY),
                joinKinds(kinds), findingsJson, readingsJson, deviceLabel, in.occurredAt());
            try {
                event = events.saveAndFlush(event);
            } catch (DataIntegrityViolationException e) {
                // Lost a race with a concurrent upload of the same clientEventId.
                accepted++;
                duplicates++;
                continue;
            }
            accepted++;

            if (in.escalate() != null && severity.atLeast(SentinelSeverity.ALERT)
                && p.caregiverEscalationEnabled() && escalate(userId, event, severity, in.escalate())) {
                escalated++;
            }
        }
        return new IngestResult(accepted, duplicates, rejected, escalated, readingsDropped);
    }

    /** True when something was actually delivered (email or bell) and the event was marked escalated. */
    private boolean escalate(String userId, MobileSentinelEvent event, SentinelSeverity severity, Escalate target) {
        Instant now = Instant.now();
        if (events.countByUserIdAndEscalatedAtGreaterThanEqual(userId, now.minus(1, ChronoUnit.HOURS))
            >= MAX_ESCALATIONS_PER_USER_PER_HOUR) {
            log.warn("Sentinel escalation budget exhausted for a user; event {} not escalated", event.getId());
            return false;
        }
        Optional<MobileSentinelEvent> last = events
            .findFirstByUserIdAndSentinelIdAndEscalatedAtIsNotNullOrderByEscalatedAtDesc(userId, event.getSentinelId());
        if (last.isPresent() && last.get().getEscalatedAt() != null
            && last.get().getEscalatedAt().isAfter(now.minus(ESCALATION_COOLDOWN))) {
            return false;
        }
        String email = clean(target.caregiverEmail());
        boolean emailed = email != null && isValidEmail(email) && sendCaregiverEmail(email, target.caregiverName(), event);
        boolean notified = notifyUser(userId, event, severity, emailed ? clean(target.caregiverName()) : null, emailed);
        if (!emailed && !notified) {
            return false;
        }
        event.markEscalated(now, emailed ? email : null);
        try {
            events.save(event);
        } catch (RuntimeException e) {
            log.warn("Could not record escalation on sentinel event {}: {}", event.getId(), e.getMessage());
        }
        return true;
    }

    private boolean sendCaregiverEmail(String email, String caregiverName, MobileSentinelEvent event) {
        JavaMailSender mailSender = mailSenders.getIfAvailable();
        if (mailSender == null) {
            log.warn("Sentinel caregiver email for event {} skipped - no JavaMailSender configured "
                + "(spring.mail.host unset)", event.getId());
            return false;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(email);
            helper.setSubject("VegaDūta Sentinel alert: " + oneLine(
                event.getTitle() != null ? event.getTitle() : event.getSeverity()));
            helper.setText(buildEmailBody(caregiverName, event, currentDisplayName()), false);
            mailSender.send(message);
            log.info("Sent sentinel caregiver email for event {}", event.getId());
            return true;
        } catch (jakarta.mail.MessagingException | RuntimeException e) {
            log.warn("Sentinel caregiver email for event {} failed: {}", event.getId(), e.getMessage());
            return false;
        }
    }

    /** Plain text. Never includes raw readings or findings values. */
    static String buildEmailBody(String caregiverName, MobileSentinelEvent event, String userName) {
        StringBuilder b = new StringBuilder();
        String name = oneLine(caregiverName);
        b.append(name == null || name.isBlank() ? "Hello," : "Hello " + name + ",").append("\n\n");
        b.append(userName == null ? "Someone" : oneLine(userName))
            .append(" listed you as a caregiver in the VegaDūta app, and one of their sentinels raised an alert.\n\n");
        b.append("Sentinel: ").append(event.getSentinelName() != null ? oneLine(event.getSentinelName())
            : event.getSentinelId() != null ? event.getSentinelId() : "Sentinel").append('\n');
        b.append("Severity: ").append(event.getSeverity()).append('\n');
        if (event.getTitle() != null) {
            b.append("Alert: ").append(oneLine(event.getTitle())).append('\n');
        }
        b.append("Occurred at: ").append(event.getOccurredAt()).append(" (UTC)\n\n");
        if (event.getSummary() != null && !event.getSummary().isBlank()) {
            b.append(event.getSummary().strip()).append("\n\n");
        }
        b.append(DISCLAIMER).append('\n');
        return b.toString();
    }

    private boolean notifyUser(
        String userId, MobileSentinelEvent event, SentinelSeverity severity, String caregiverName, boolean emailed
    ) {
        NotificationService service = notifications.getIfAvailable();
        if (service == null) {
            return false;
        }
        String title = "Sentinel alert: " + (event.getTitle() != null ? event.getTitle() : severity.name());
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }
        String who = event.getSentinelName() != null ? event.getSentinelName() : "A sentinel";
        String body = who + " raised a " + severity.name() + " event. "
            + (emailed ? "Your caregiver" + (caregiverName == null || caregiverName.isBlank() ? "" : " " + caregiverName)
                + " was emailed." : "No caregiver email could be sent.")
            + " Not a medical device - if this may be an emergency, call local emergency services.";
        try {
            service.create(userId, NotificationCategory.SAFETY, title, body,
                severity == SentinelSeverity.CRITICAL ? NotificationSeverity.ERROR : NotificationSeverity.WARNING,
                null, null);
            return true;
        } catch (RuntimeException e) {
            log.warn("Failed to create sentinel notification for event {}: {}", event.getId(), e.getMessage());
            return false;
        }
    }

    private void purgeExpired(int retentionDays) {
        try {
            int n = events.deleteOccurredBefore(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
            if (n > 0) {
                log.info("Purged {} sentinel events older than {} days", n, retentionDays);
            }
        } catch (RuntimeException e) {
            log.warn("Sentinel retention purge failed: {}", e.getMessage());
        }
    }

    // --- own data -------------------------------------------------------------

    public List<EventView> listOwn(Integer limit, String sentinelId) {
        String userId = requireUser();
        int size = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        String sid = clean(sentinelId);
        List<MobileSentinelEvent> rows = sid == null
            ? events.findByUserIdOrderByOccurredAtDesc(userId, PageRequest.of(0, size))
            : events.findByUserIdAndSentinelIdOrderByOccurredAtDesc(userId, sid, PageRequest.of(0, size));
        return rows.stream().map(this::view).toList();
    }

    public int eraseOwn() {
        return events.deleteAllOfUser(requireUser());
    }

    // --- admin aggregate ------------------------------------------------------

    public AdminSummary adminSummary() {
        requireAdmin();
        Instant since = Instant.now().minus(SUMMARY_WINDOW_DAYS, ChronoUnit.DAYS);
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (SentinelSeverity s : SentinelSeverity.values()) {
            bySeverity.put(s.name(), 0L);
        }
        for (Object[] row : events.countBySeveritySince(since)) {
            if (row != null && row.length == 2 && row[0] != null && bySeverity.containsKey(row[0].toString())) {
                bySeverity.put(row[0].toString(), ((Number) row[1]).longValue());
            }
        }
        return new AdminSummary(SUMMARY_WINDOW_DAYS, events.countActiveUsersSince(since),
            events.countByOccurredAtGreaterThanEqual(since), bySeverity,
            events.countByEscalatedAtGreaterThanEqual(since));
    }

    // --- helpers --------------------------------------------------------------

    private static String requireUser() {
        String userId = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to use sentinels.");
        }
        return userId;
    }

    private static String requireAdmin() {
        String userId = requireUser();
        TenantContext ctx = TenantContext.current().orElseThrow();
        if (!ctx.hasRole("ROLE_tenant-admin") && !ctx.hasRole("ROLE_platform-admin")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a tenant admin can do this.");
        }
        return userId;
    }

    static PolicyView view(MobileSentinelPolicy p) {
        if (p == null) {
            return new PolicyView(true, false, false, allKindNames(), false, DEFAULT_RETENTION_DAYS, null, null);
        }
        List<String> kinds = p.getAllowedKinds() == null ? allKindNames() : splitKinds(p.getAllowedKinds());
        return new PolicyView(p.isEnabled(), p.isSyncEnabled(), p.isAllowRawReadings(), kinds,
            p.isCaregiverEscalationEnabled(), p.getRetentionDays(), p.getUpdatedBy(), p.getUpdatedAt());
    }

    private EventView view(MobileSentinelEvent e) {
        return new EventView(e.getId(), e.getClientEventId(), e.getSentinelId(), e.getSentinelName(), e.getTemplate(),
            e.getSeverity(), e.getTitle(), e.getSummary(), splitKinds(e.getKinds()), readJson(e.getFindings()),
            readJson(e.getReadings()), e.getDeviceLabel(), e.getOccurredAt(), e.getReceivedAt(), e.getEscalatedAt(),
            e.getEscalatedTo());
    }

    private static List<String> allKindNames() {
        return Arrays.stream(SentinelKind.values()).map(Enum::name).toList();
    }

    private static List<String> splitKinds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static Set<SentinelKind> toKindSet(List<String> names) {
        Set<SentinelKind> out = EnumSet.noneOf(SentinelKind.class);
        for (String n : names) {
            SentinelKind k = parseKind(n);
            if (k != null) {
                out.add(k);
            }
        }
        return out;
    }

    private static String joinKinds(Set<SentinelKind> kinds) {
        return kinds.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    static SentinelKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SentinelKind.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static SentinelSeverity parseSeverity(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SentinelSeverity.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean isValidEmail(String email) {
        return email != null && email.length() <= 254 && EMAIL.matcher(email).matches();
    }

    private static boolean hasContent(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode() && !(node.isContainerNode() && node.isEmpty());
    }

    /** Readings whose {@code kind} is outside the allowed set never get stored. */
    private JsonNode keepAllowedReadings(JsonNode readings, Set<SentinelKind> allowed) {
        ArrayNode out = objectMapper.createArrayNode();
        if (!readings.isArray()) {
            return out;
        }
        for (JsonNode r : readings) {
            SentinelKind kind = r == null ? null : parseKind(r.path("kind").asText(null));
            if (kind != null && allowed.contains(kind)) {
                out.add(r);
            }
        }
        return out;
    }

    private String toJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** Strip CR/LF and other control characters (mail headers, one-line fields). */
    private static String oneLine(String s) {
        return s == null ? null : s.replaceAll("[\\p{Cntrl}]+", " ").strip();
    }

    /** The caller's display name from their token (name, then preferred_username), or null. */
    private static String currentDisplayName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            for (String claim : List.of("name", "preferred_username")) {
                String value = jwt.getClaimAsString(claim);
                if (value != null && !value.isBlank()) {
                    return value.length() > 120 ? value.substring(0, 120) : value;
                }
            }
        }
        return null;
    }
}
