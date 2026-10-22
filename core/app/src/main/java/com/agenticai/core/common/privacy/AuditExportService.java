package com.agenticai.core.common.privacy;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.json.JsonMappers;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exports the current tenant's audit events for a time range, to support the fiduciary's breach
 * assessment and notification work (Act s.8(6); Rules 2025, rule 7). The tenant is the schema the
 * request runs in, so one tenant's export cannot contain another's events. Streams row by row; the
 * range is capped so one request cannot be asked to scan years of history.
 *
 * <p>Exporting is itself audited. The audit log is append-only (tenant V75), so the file is a read
 * of what was recorded, not something this service can alter.
 */
@Service
public class AuditExportService {

    public static final Duration MAX_RANGE = Duration.ofDays(366);
    private static final ObjectMapper MAPPER = JsonMappers.withJavaTime();

    private static final String QUERY =
            "SELECT id, created_at, actor_id, action_type, target_type, target_id, outcome, detail"
                    + " FROM audit_log_entry WHERE created_at >= ? AND created_at < ?"
                    + " ORDER BY created_at, id";

    private final JdbcTemplate jdbc;
    private final AuditLogService audit;

    public AuditExportService(JdbcTemplate jdbc, AuditLogService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public static void validate(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The range may not be longer than " + MAX_RANGE.toDays() + " days");
        }
    }

    /**
     * CSV with a header row. Cells starting with = + - @ are prefixed with ' (spreadsheet formula
     * injection).
     */
    public long exportCsv(Instant from, Instant to, OutputStream out, String actor)
            throws IOException {
        validate(from, to);
        AtomicLong rows = new AtomicLong();
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write("id,created_at,actor_id,action_type,target_type,target_id,outcome,detail\r\n");
        jdbc.query(
                QUERY,
                rs -> {
                    try {
                        for (int c = 1; c <= 8; c++) {
                            if (c > 1) {
                                w.write(',');
                            }
                            w.write(cell(rs.getObject(c)));
                        }
                        w.write("\r\n");
                        rows.incrementAndGet();
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                },
                Timestamp.from(from),
                Timestamp.from(to));
        w.flush();
        recordExport(actor, from, to, rows.get(), "csv");
        return rows.get();
    }

    /**
     * JSON object: tenant range metadata, the entries, and the signed audit seals covering the
     * range.
     */
    public long exportJson(Instant from, Instant to, OutputStream out, String actor)
            throws IOException {
        validate(from, to);
        AtomicLong rows = new AtomicLong();
        try (JsonGenerator g = MAPPER.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringProperty("from", from.toString());
            g.writeStringProperty("to", to.toString());
            g.writeStringProperty("generatedAt", Instant.now().toString());
            g.writeArrayPropertyStart("entries");
            jdbc.query(
                    QUERY,
                    rs -> {
                        g.writeStartObject();
                        g.writeStringProperty("id", String.valueOf(rs.getObject(1)));
                        g.writeStringProperty(
                                "createdAt", rs.getTimestamp(2).toInstant().toString());
                        g.writeStringProperty("actorId", rs.getString(3));
                        g.writeStringProperty("actionType", rs.getString(4));
                        g.writeStringProperty("targetType", rs.getString(5));
                        g.writeStringProperty("targetId", rs.getString(6));
                        g.writeStringProperty("outcome", rs.getString(7));
                        g.writeStringProperty("detail", rs.getString(8));
                        g.writeEndObject();
                        rows.incrementAndGet();
                    },
                    Timestamp.from(from),
                    Timestamp.from(to));
            g.writeEndArray();
            g.writeArrayPropertyStart("seals");
            jdbc.query(
                    "SELECT seq, first_row_at, last_row_at, row_count, seal_hash, prev_seal_hash,"
                            + " algorithm, key_id, signature FROM audit_seal"
                            + " WHERE last_row_at >= ? AND first_row_at < ? ORDER BY seq",
                    rs -> {
                        g.writeStartObject();
                        g.writeNumberProperty("seq", rs.getLong(1));
                        g.writeStringProperty(
                                "firstRowAt", rs.getTimestamp(2).toInstant().toString());
                        g.writeStringProperty(
                                "lastRowAt", rs.getTimestamp(3).toInstant().toString());
                        g.writeNumberProperty("rowCount", rs.getInt(4));
                        g.writeStringProperty("sealHash", rs.getString(5));
                        g.writeStringProperty("prevSealHash", rs.getString(6));
                        g.writeStringProperty("algorithm", rs.getString(7));
                        g.writeStringProperty("keyId", rs.getString(8));
                        g.writeStringProperty("signature", rs.getString(9));
                        g.writeEndObject();
                    },
                    Timestamp.from(from),
                    Timestamp.from(to));
            g.writeEndArray();
            g.writeNumberProperty("entryCount", rows.get());
            g.writeEndObject();
        }
        recordExport(actor, from, to, rows.get(), "json");
        return rows.get();
    }

    private void recordExport(String actor, Instant from, Instant to, long rows, String format) {
        audit.record(
                actor,
                "privacy.audit.export",
                "audit_log",
                format,
                AuditOutcome.SUCCESS,
                "Exported "
                        + rows
                        + " audit event(s) from "
                        + from
                        + " to "
                        + to
                        + " as "
                        + format);
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value instanceof Timestamp t ? t.toInstant().toString() : String.valueOf(value);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
