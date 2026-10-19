package com.agenticai.core.common.roi;

import com.agenticai.core.common.roi.RoiDtos.PriceRequest;
import com.agenticai.core.common.roi.RoiDtos.PriceResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CRUD over the tenant's {@code llm_price} versions (tenant schema, V496). Nothing is seeded and
 * nothing is defaulted: an empty table means every model is "unknown".
 *
 * <p>Transactional on purpose: a raw {@code JdbcTemplate} only runs in the tenant's schema when it
 * joins the JPA-bound connection that {@code SchemaMultiTenantConnectionProvider} scopes, which
 * needs an open transaction; outside one it would read the default schema. {@code RoiApiIT} fails
 * if that is removed.
 */
@Service
@Transactional(readOnly = true)
public class RoiPriceService {

    private static final BigDecimal MAX_RATE =
            new BigDecimal("100000"); // USD per million tokens, sanity bound

    private final JdbcTemplate jdbc;

    public RoiPriceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PriceResponse> list() {
        return jdbc.query(
                "SELECT id, provider_name, model_prefix, input_per_million, output_per_million,"
                        + " cache_write_per_million, cache_read_per_million, effective_from, set_at, set_by, note"
                        + " FROM llm_price ORDER BY provider_name, model_prefix, effective_from DESC",
                (rs, n) ->
                        new PriceResponse(
                                rs.getObject("id", UUID.class),
                                rs.getString("provider_name"),
                                rs.getString("model_prefix"),
                                rs.getBigDecimal("input_per_million"),
                                rs.getBigDecimal("output_per_million"),
                                rs.getBigDecimal("cache_write_per_million"),
                                rs.getBigDecimal("cache_read_per_million"),
                                rs.getObject("effective_from", LocalDate.class),
                                rs.getTimestamp("set_at").toInstant(),
                                rs.getString("set_by"),
                                rs.getString("note")));
    }

    List<RoiCostModel.Rate> rates() {
        return list().stream()
                .map(
                        p ->
                                new RoiCostModel.Rate(
                                        p.id(),
                                        p.providerName(),
                                        p.modelPrefix(),
                                        p.inputPerMillion(),
                                        p.outputPerMillion(),
                                        p.cacheWritePerMillion(),
                                        p.cacheReadPerMillion(),
                                        p.effectiveFrom()))
                .toList();
    }

    /**
     * Records a new price version. The same (provider, model prefix, effective date) replaces the
     * earlier row of that day (a same-day correction); a different date keeps history.
     */
    @Transactional
    public PriceResponse upsert(PriceRequest req, String subject) {
        if (req == null || blank(req.providerName()) || blank(req.modelPrefix())) {
            throw bad("providerName and modelPrefix are required");
        }
        for (BigDecimal r :
                new BigDecimal[] {
                    req.inputPerMillion(),
                    req.outputPerMillion(),
                    req.cacheWritePerMillion(),
                    req.cacheReadPerMillion()
                }) {
            if (r != null && (r.signum() < 0 || r.compareTo(MAX_RATE) > 0)) {
                throw bad(
                        "rates are USD per million tokens and must be between 0 and "
                                + MAX_RATE.toPlainString());
            }
        }
        if (req.note() != null && req.note().length() > 300) {
            throw bad("note must be at most 300 characters");
        }
        LocalDate effective =
                req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.now(ZoneOffset.UTC);
        String provider = req.providerName().trim().toUpperCase(Locale.ROOT);
        String prefix = req.modelPrefix().trim();
        jdbc.update(
                "INSERT INTO llm_price (id, provider_name, model_prefix, input_per_million, output_per_million,"
                        + " cache_write_per_million, cache_read_per_million, effective_from, set_by, note)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)"
                        + " ON CONFLICT (provider_name, model_prefix, effective_from) DO UPDATE SET"
                        + " input_per_million = EXCLUDED.input_per_million,"
                        + " output_per_million = EXCLUDED.output_per_million,"
                        + " cache_write_per_million = EXCLUDED.cache_write_per_million,"
                        + " cache_read_per_million = EXCLUDED.cache_read_per_million,"
                        + " set_by = EXCLUDED.set_by, set_at = now(), note = EXCLUDED.note",
                UUID.randomUUID(),
                provider,
                prefix,
                req.inputPerMillion(),
                req.outputPerMillion(),
                req.cacheWritePerMillion(),
                req.cacheReadPerMillion(),
                effective,
                subject,
                req.note());
        return list().stream()
                .filter(
                        p ->
                                p.providerName().equals(provider)
                                        && p.modelPrefix().equals(prefix)
                                        && p.effectiveFrom().equals(effective))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM llm_price WHERE id = ?", id) > 0;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
