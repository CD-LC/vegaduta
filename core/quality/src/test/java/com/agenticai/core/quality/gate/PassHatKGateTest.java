package com.agenticai.core.quality.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.quality.gate.PassHatKGate.CaseRollouts;
import com.agenticai.core.quality.gate.PassHatKGate.Decision;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Known-answer tests for the pass^k promotion arithmetic. */
class PassHatKGateTest {

    @Test
    void tenOfTenAllPassHasWilsonLowerBoundNearZeroPointSevenTwo() {
        Decision d = PassHatKGate.decide(Collections.nCopies(10, new CaseRollouts(3, 3)), 0.5);

        assertTrue(d.applicable());
        assertTrue(d.passed());
        assertEquals(10, d.casesAllPassed());
        assertEquals(1.0, d.passHatK(), 1e-12);
        // Wilson 95% for 10/10 is [0.722, 1.0].
        assertEquals(0.722, d.lowerBound(), 1e-3);
    }

    @Test
    void oneFlakyRepetitionMakesTheCaseFailPassHatK() {
        Decision d = PassHatKGate.decide(List.of(
            new CaseRollouts(3, 3), new CaseRollouts(3, 2), new CaseRollouts(3, 3), new CaseRollouts(3, 3)), 0.5);

        assertEquals(3, d.casesAllPassed());
        assertEquals(4, d.cases());
        assertEquals(0.75, d.passHatK(), 1e-12);
        // Wilson 95% lower bound for 3/4 is ~0.30 < 0.5.
        assertTrue(d.lowerBound() < 0.5);
        assertFalse(d.passed());
    }

    @Test
    void fewCasesCannotCertifyHighBarEvenWhenAllPass() {
        // 3/3 has lower bound ~0.44: honest uncertainty blocks a 0.6 bar.
        Decision d = PassHatKGate.decide(Collections.nCopies(3, new CaseRollouts(3, 3)), 0.6);

        assertEquals(1.0, d.passHatK(), 1e-12);
        assertFalse(d.passed());
    }

    @Test
    void singleRolloutCasesAreIgnoredAndMeanVerdictIsLeftAlone() {
        Decision d = PassHatKGate.decide(List.of(new CaseRollouts(1, 0), new CaseRollouts(1, 1)), 0.9);

        assertFalse(d.applicable());
        assertTrue(d.passed());
    }

    @Test
    void emptyAndNullInputsAreNotApplicable() {
        assertFalse(PassHatKGate.decide(List.of(), 0.5).applicable());
        assertFalse(PassHatKGate.decide(null, 0.5).applicable());
    }

    @Test
    void zeroBarAlwaysPassesAndOutOfRangeBarIsRejected() {
        assertTrue(PassHatKGate.decide(List.of(new CaseRollouts(3, 0)), 0.0).passed());
        assertThrows(IllegalArgumentException.class, () -> PassHatKGate.decide(List.of(), 1.5));
        assertThrows(IllegalArgumentException.class, () -> PassHatKGate.decide(List.of(), Double.NaN));
    }

    @Test
    void passesAreClampedIntoRange() {
        Decision d = PassHatKGate.decide(List.of(new CaseRollouts(3, 9)), 0.0);

        assertEquals(1, d.casesAllPassed());
    }
}
