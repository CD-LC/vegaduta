package com.agenticai.core.agents.memorystore.decay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemorySalienceTest {

    static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");

    static ItemSnapshot item(String kind, MemoryTrustLevel trust, boolean pinned, double imp, int uses, long idleDays) {
        Instant t = NOW.minus(Duration.ofDays(idleDays));
        return new ItemSnapshot(UUID.randomUUID(), null, kind, trust, ItemSnapshot.Status.ACTIVE, pinned, false,
                imp, uses, t, null, List.of(), 0, false, false);
    }

    MemorySalience at(Instant now, DecayProperties p) {
        return new MemorySalience(p, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void defaultsMatchTheDesignDocument() {
        DecayProperties p = new DecayProperties();
        assertThat(p.getHalfLifeDays()).isEqualTo(45);
        assertThat(p.halfLifeFor("preference")).isEqualTo(120);
        assertThat(p.halfLifeFor("Episodic")).isEqualTo(21);
        assertThat(p.halfLifeFor("task-context")).isEqualTo(7);
        assertThat(p.halfLifeFor("other")).isEqualTo(45);
        assertThat(p.getWeights().getImportance()).isEqualTo(0.45);
        assertThat(p.getWeights().getRecency()).isEqualTo(0.35);
        assertThat(p.getWeights().getFrequency()).isEqualTo(0.20);
        assertThat(p.getForgetThreshold()).isEqualTo(0.12);
        assertThat(p.getSummariseThreshold()).isEqualTo(0.30);
        assertThat(p.getMinAgeDays()).isEqualTo(14);
        assertThat(p.getDryRunDays()).isEqualTo(7);
        assertThat(p.getMaxFractionForgotten()).isEqualTo(0.30);
        assertThat(p.getFrequencySaturation()).isEqualTo(20);
    }

    @Test
    void workedExamplePreferenceIdle200Days() {
        // doc: R = 0.31, S = 0.225 + 0.109 = 0.33
        double s = at(NOW, new DecayProperties()).score(item("preference", MemoryTrustLevel.TRUSTED, false, 0.5, 0, 200));
        assertThat(s).isCloseTo(0.225 + 0.35 * Math.pow(0.5, 200d / 120), within(1e-9));
        assertThat(s).isCloseTo(0.33, within(0.01));
        assertThat(s).isGreaterThan(0.30); // kept
    }

    @Test
    void workedExampleLowImportanceIdle300Days() {
        double s = at(NOW, new DecayProperties()).score(item("fact", MemoryTrustLevel.TRUSTED, false, 0.3, 0, 300));
        assertThat(s).isCloseTo(0.14, within(0.005));
        assertThat(s).isGreaterThan(0.12); // importance is a floor
    }

    @Test
    void frequencyTermSaturates() {
        MemorySalience m = at(NOW, new DecayProperties());
        double at20 = m.score(item("x", MemoryTrustLevel.TRUSTED, false, 0, 20, 0));
        double at500 = m.score(item("x", MemoryTrustLevel.TRUSTED, false, 0, 500, 0));
        assertThat(at20).isEqualTo(at500);
        assertThat(at20).isCloseTo(0.35 + 0.20, within(1e-9));
    }

    @Test
    void pinnedIsOneAndQuarantineHalvesIt() {
        MemorySalience m = at(NOW, new DecayProperties());
        ItemSnapshot pin = item("x", MemoryTrustLevel.TRUSTED, true, 0, 0, 1000);
        assertThat(m.score(pin)).isEqualTo(1.0);
        assertThat(m.effective(pin)).isEqualTo(1.0);
        ItemSnapshot q = item("x", MemoryTrustLevel.QUARANTINED, false, 0.5, 3, 10);
        ItemSnapshot t = item("x", MemoryTrustLevel.TRUSTED, false, 0.5, 3, 10);
        assertThat(m.effective(q)).isCloseTo(m.effective(t) * 0.5, within(1e-12));
    }

    @Test
    void lastUsedResetsTheRecencyClock() {
        MemorySalience m = at(NOW, new DecayProperties());
        ItemSnapshot old = item("x", MemoryTrustLevel.TRUSTED, false, 0.2, 0, 400);
        ItemSnapshot read = new ItemSnapshot(old.id(), null, "x", MemoryTrustLevel.TRUSTED, ItemSnapshot.Status.ACTIVE,
                false, false, 0.2, 0, old.createdAt(), NOW, List.of(), 0, false, false);
        assertThat(m.score(read)).isGreaterThan(m.score(old));
    }

    @Test
    void clockControlsTheResult() {
        DecayProperties p = new DecayProperties();
        ItemSnapshot i = item("x", MemoryTrustLevel.TRUSTED, false, 0.5, 0, 0);
        double now = at(NOW, p).score(i);
        double later = at(NOW.plus(Duration.ofDays(90)), p).score(i);
        assertThat(later).isLessThan(now);
        // earlier than creation clamps age to zero
        assertThat(at(NOW.minus(Duration.ofDays(5)), p).score(i)).isEqualTo(now);
    }

    @Test
    void propertyMonotonicBoundedAndDeterministic() {
        DecayProperties p = new DecayProperties();
        java.util.Random r = new java.util.Random(42);
        for (int n = 0; n < 2000; n++) {
            double imp = r.nextDouble();
            int uses = r.nextInt(100);
            String kind = List.of("preference", "episodic", "task-context", "misc").get(r.nextInt(4));
            long age = r.nextInt(2000);
            ItemSnapshot a = item(kind, MemoryTrustLevel.TRUSTED, false, imp, uses, age);
            ItemSnapshot b = new ItemSnapshot(a.id(), null, kind, MemoryTrustLevel.TRUSTED, ItemSnapshot.Status.ACTIVE,
                    false, false, imp, uses, NOW.minus(Duration.ofDays(age + 1 + r.nextInt(50))), null,
                    List.of(), 0, false, false);
            MemorySalience m = at(NOW, p);
            assertThat(m.score(a)).isBetween(0.0, 1.0);
            assertThat(m.score(b)).isLessThanOrEqualTo(m.score(a));
            assertThat(m.score(a)).isEqualTo(m.score(a));
            ItemSnapshot moreUsed = new ItemSnapshot(a.id(), null, kind, MemoryTrustLevel.TRUSTED,
                    ItemSnapshot.Status.ACTIVE, false, false, imp, uses + 1, a.createdAt(), null, List.of(), 0, false,
                    false);
            assertThat(m.score(moreUsed)).isGreaterThanOrEqualTo(m.score(a));
        }
    }

    @Test
    void settersClampBadValues() {
        DecayProperties p = new DecayProperties();
        p.setHalfLifeDays(-3);
        p.setForgetThreshold(7);
        p.setMaxFractionForgotten(Double.NaN);
        assertThat(p.getHalfLifeDays()).isEqualTo(45);
        assertThat(p.getForgetThreshold()).isEqualTo(1);
        assertThat(p.getMaxFractionForgotten()).isZero();
    }
}
