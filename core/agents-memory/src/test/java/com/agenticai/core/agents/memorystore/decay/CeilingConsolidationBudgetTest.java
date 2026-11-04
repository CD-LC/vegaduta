package com.agenticai.core.agents.memorystore.decay;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.memorystore.decay.ConsolidationBudget.CapProbe;
import com.agenticai.core.agents.memorystore.decay.ConsolidationBudget.Decision;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CeilingConsolidationBudgetTest {

    static final Instant T = Instant.parse("2026-11-04T10:00:00Z");
    final DecayProperties props = new DecayProperties();

    static final CapProbe OPEN = new CapProbe() {
        public boolean withinTenantCap(String s) {
            return true;
        }

        public boolean withinSubjectCap(String s, String u) {
            return true;
        }
    };

    static BigDecimal usd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void tenantNightCeilingThenSettleReleases() {
        CeilingConsolidationBudget b = new CeilingConsolidationBudget(props, Clock.fixed(T, ZoneOffset.UTC), OPEN);
        assertThat(b.tryReserve("t1", "u", usd("0.30"))).isEqualTo(Decision.ALLOWED);
        assertThat(b.tryReserve("t1", "u", usd("0.30"))).isEqualTo(Decision.TENANT_NIGHT_CEILING);
        assertThat(b.tryReserve("t2", "u", usd("0.30"))).isEqualTo(Decision.ALLOWED);
        b.settle("t1", usd("0.30"), usd("0.05"));
        assertThat(b.tryReserve("t1", "u", usd("0.30"))).isEqualTo(Decision.ALLOWED);
    }

    @Test
    void runCeilingAcrossTenants() {
        props.setMaxUsdPerTenantPerNight(10);
        props.setMaxUsdPerRun(1.0);
        CeilingConsolidationBudget b = new CeilingConsolidationBudget(props, Clock.fixed(T, ZoneOffset.UTC), OPEN);
        assertThat(b.tryReserve("a", "u", usd("0.6"))).isEqualTo(Decision.ALLOWED);
        assertThat(b.tryReserve("b", "u", usd("0.6"))).isEqualTo(Decision.RUN_CEILING);
    }

    @Test
    void capsFromTheExistingServicesWin() {
        CapProbe closed = new CapProbe() {
            public boolean withinTenantCap(String s) {
                return !s.equals("capped");
            }

            public boolean withinSubjectCap(String s, String u) {
                return !u.equals("heavy");
            }
        };
        CeilingConsolidationBudget b = new CeilingConsolidationBudget(props, Clock.fixed(T, ZoneOffset.UTC), closed);
        assertThat(b.tryReserve("capped", "u", usd("0.01"))).isEqualTo(Decision.TENANT_CAP_REACHED);
        assertThat(b.tryReserve("ok", "heavy", usd("0.01"))).isEqualTo(Decision.SUBJECT_CAP_REACHED);
        assertThat(b.tryReserve("ok", "light", usd("0.01"))).isEqualTo(Decision.ALLOWED);
    }

    @Test
    void nightlyTotalResetsNextUtcDay() {
        Clock day1 = Clock.fixed(T, ZoneOffset.UTC);
        CeilingConsolidationBudget b = new CeilingConsolidationBudget(props, day1, OPEN);
        props.setMaxUsdPerRun(100);
        assertThat(b.tryReserve("t", "u", usd("0.5"))).isEqualTo(Decision.ALLOWED);
        assertThat(b.tryReserve("t", "u", usd("0.1"))).isEqualTo(Decision.TENANT_NIGHT_CEILING);
        CeilingConsolidationBudget next = new CeilingConsolidationBudget(props,
                Clock.fixed(T.plus(Duration.ofDays(1)), ZoneOffset.UTC), OPEN);
        assertThat(next.tryReserve("t", "u", usd("0.5"))).isEqualTo(Decision.ALLOWED);
    }
}
