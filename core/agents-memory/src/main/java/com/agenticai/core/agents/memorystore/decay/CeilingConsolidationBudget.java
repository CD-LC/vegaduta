package com.agenticai.core.agents.memorystore.decay;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link ConsolidationBudget} that enforces the two job-level USD ceilings and delegates the tenant
 * and per-user caps to a {@link CapProbe}. Create one instance per sweep run: the per-run total
 * lives in the instance, the per-tenant nightly total is keyed by UTC date from the injected clock.
 * Thread-safe.
 *
 * <p>Order of checks: tenant cap, subject cap, then the ceilings, so the most specific reason is
 * reported. A reservation counts against the ceilings until settled.
 */
public final class CeilingConsolidationBudget implements ConsolidationBudget {

    private final DecayProperties props;
    private final Clock clock;
    private final CapProbe probe;
    private final Map<String, BigDecimal> tenantNight = new HashMap<>();
    private LocalDate day;
    private BigDecimal run = BigDecimal.ZERO;

    public CeilingConsolidationBudget(DecayProperties props, Clock clock, CapProbe probe) {
        this.props = props;
        this.clock = clock;
        this.probe = probe;
    }

    @Override
    public synchronized Decision tryReserve(
            String tenantSchema, String subjectKey, BigDecimal estimatedUsd) {
        BigDecimal est =
                estimatedUsd == null || estimatedUsd.signum() < 0 ? BigDecimal.ZERO : estimatedUsd;
        rollDay();
        if (!probe.withinTenantCap(tenantSchema)) {
            return Decision.TENANT_CAP_REACHED;
        }
        if (!probe.withinSubjectCap(tenantSchema, subjectKey)) {
            return Decision.SUBJECT_CAP_REACHED;
        }
        BigDecimal tenant = tenantNight.getOrDefault(tenantSchema, BigDecimal.ZERO);
        if (tenant.add(est).compareTo(BigDecimal.valueOf(props.getMaxUsdPerTenantPerNight())) > 0) {
            return Decision.TENANT_NIGHT_CEILING;
        }
        if (run.add(est).compareTo(BigDecimal.valueOf(props.getMaxUsdPerRun())) > 0) {
            return Decision.RUN_CEILING;
        }
        tenantNight.put(tenantSchema, tenant.add(est));
        run = run.add(est);
        return Decision.ALLOWED;
    }

    @Override
    public synchronized void settle(
            String tenantSchema, BigDecimal reservedUsd, BigDecimal actualUsd) {
        rollDay();
        BigDecimal reserved =
                reservedUsd == null ? BigDecimal.ZERO : reservedUsd.max(BigDecimal.ZERO);
        BigDecimal actual = actualUsd == null ? BigDecimal.ZERO : actualUsd.max(BigDecimal.ZERO);
        BigDecimal delta = actual.subtract(reserved);
        tenantNight.merge(tenantSchema, delta, (a, b) -> a.add(b).max(BigDecimal.ZERO));
        run = run.add(delta).max(BigDecimal.ZERO);
    }

    private void rollDay() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!today.equals(day)) {
            day = today;
            tenantNight.clear();
        }
    }
}
