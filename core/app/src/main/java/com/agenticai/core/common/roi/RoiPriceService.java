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

    private final RoiJdbcRepository repository;

    public RoiPriceService(RoiJdbcRepository repository) {
        this.repository = repository;
    }

    public List<PriceResponse> list() {
        return repository.listPrices();
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
        repository.upsertPrice(
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
        return repository.deletePrice(id);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
