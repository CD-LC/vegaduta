package com.agenticai.core.platform;

import com.agenticai.core.platform.PlatformAlertWebhookEvent.AlertStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alerting infrastructure pass (2026-07) - persists every alert in an
 * Alertmanager webhook delivery as a {@link PlatformAlertWebhookEvent} row.
 * See that entity's javadoc for why this is a dedicated {@code control_plane}
 * table rather than a reuse of {@code core.audit.PlatformIssueService}
 * (tenant-scoped, wrong fit for a platform-wide infra alert).
 *
 * <p>Deliberately tolerant of whatever shape Alertmanager actually sends -
 * this is parsed from a raw {@code Map<String,Object>} (Alertmanager's
 * webhook_config payload, see
 * https://prometheus.io/docs/alerting/latest/configuration/#webhook_config)
 * rather than a strict Jackson-bound DTO, so a field Alertmanager adds or
 * omits in a future version degrades to "just doesn't populate that column"
 * rather than a 400 that drops the whole delivery. Every alert in a single
 * delivery's {@code alerts} array is recorded independently (a `for` grouping
 * routinely bundles several alerts into one HTTP POST).
 */
@Service
public class PlatformAlertWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAlertWebhookService.class);

    private final PlatformAlertWebhookEventRepository repository;
    private final ObjectMapper objectMapper;

    public PlatformAlertWebhookService(PlatformAlertWebhookEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records every alert in one Alertmanager webhook delivery. Never throws
     * on a single malformed alert entry - logged and skipped, same "one bad
     * reading never blanks the whole response" posture the rest of this
     * codebase's ingest paths (e.g. {@code SystemStatusController#safe})
     * already use; Alertmanager itself doesn't retry on anything but a
     * non-2xx response, so a partial failure here should still 2xx once the
     * alerts that DID parse are saved.
     */
    @SuppressWarnings("unchecked")
    public int recordDelivery(Map<String, Object> payload) {
        if (payload == null) {
            return 0;
        }
        Object rawAlerts = payload.get("alerts");
        if (!(rawAlerts instanceof List<?> alerts)) {
            log.warn("Alertmanager webhook delivery had no 'alerts' array - ignoring. Payload keys: {}", payload.keySet());
            return 0;
        }
        int recorded = 0;
        for (Object alertObj : alerts) {
            if (!(alertObj instanceof Map)) {
                continue;
            }
            try {
                recordAlert((Map<String, Object>) alertObj);
                recorded++;
            } catch (RuntimeException e) {
                log.warn("Failed to record one alert from an Alertmanager webhook delivery - skipping it, continuing with the rest", e);
            }
        }
        return recorded;
    }

    @Transactional
    void recordAlert(Map<String, Object> alert) {
        Map<String, Object> labels = asStringObjectMap(alert.get("labels"));
        Map<String, Object> annotations = asStringObjectMap(alert.get("annotations"));

        String fingerprint = stringOrNull(alert.get("fingerprint"));
        if (fingerprint == null || fingerprint.isBlank()) {
            // Alertmanager always sends one, but this receiver must not
            // silently drop a hand-crafted test POST (e.g. `curl` during
            // setup verification) just because it omitted the field.
            fingerprint = "no-fingerprint:" + (labels.getOrDefault("alertname", "unknown")) + ":" + Instant.now().toEpochMilli();
        }
        String alertName = stringOrNull(labels.get("alertname"));
        String severity = stringOrNull(labels.get("severity"));
        String summary = truncate(stringOrNull(annotations.get("summary")), 500);
        String description = stringOrNull(annotations.get("description"));
        String generatorUrl = stringOrNull(alert.get("generatorURL"));
        AlertStatus status = "resolved".equalsIgnoreCase(stringOrNull(alert.get("status"))) ? AlertStatus.RESOLVED : AlertStatus.FIRING;
        Instant startsAt = parseInstant(stringOrNull(alert.get("startsAt")));
        Instant endsAt = status == AlertStatus.RESOLVED ? parseInstant(stringOrNull(alert.get("endsAt"))) : null;
        String labelsJson = toJson(labels);
        String annotationsJson = toJson(annotations);

        var existing = repository.findByFingerprint(fingerprint);
        if (existing.isPresent()) {
            PlatformAlertWebhookEvent event = existing.get();
            event.recordRedelivery(status, summary, description, labelsJson, annotationsJson, endsAt);
            repository.save(event);
            return;
        }
        try {
            repository.save(new PlatformAlertWebhookEvent(
                fingerprint, alertName, severity, status, summary, description, labelsJson, annotationsJson, generatorUrl, startsAt, endsAt
            ));
        } catch (DataIntegrityViolationException raceOnUniqueFingerprint) {
            // Two near-simultaneous deliveries for a brand-new fingerprint - the loser
            // here just treats it as a redelivery, same race handling PlatformIssueService uses.
            PlatformAlertWebhookEvent event = repository.findByFingerprint(fingerprint).orElseThrow(() -> raceOnUniqueFingerprint);
            event.recordRedelivery(status, summary, description, labelsJson, annotationsJson, endsAt);
            repository.save(event);
        }
        if (status == AlertStatus.FIRING) {
            log.warn("Alertmanager alert firing: {} (severity={}) - {}", alertName, severity, summary);
        } else {
            log.info("Alertmanager alert resolved: {} (severity={}) - {}", alertName, severity, summary);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringObjectMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength - 1) + "…" : value;
    }

    /** Alertmanager sends RFC3339 timestamps (e.g. {@code 2026-07-27T10:00:00.000Z}) - {@link Instant#parse} handles that format directly. */
    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
