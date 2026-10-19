package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.tools.browseruse.BrowserStuckDetector.Level;
import com.agenticai.core.agents.tools.browseruse.BrowserStuckDetector.Verdict;
import org.junit.jupiter.api.Test;

/**
 * Repeated-identical-action / no-DOM-change detection with the default 4 (warn) and 8 (abort)
 * thresholds.
 */
class BrowserStuckDetectorTest {

    private static BrowserStuckDetector defaults() {
        return new BrowserStuckDetector(4, 8);
    }

    @Test
    void progressNeverTriggers() {
        BrowserStuckDetector d = defaults();
        for (int i = 0; i < 50; i++) {
            assertThat(d.record("click|button|Next", true).level()).isEqualTo(Level.OK);
        }
        assertThat(d.isAborted()).isFalse();
    }

    @Test
    void sameDeadActionRepeatedWarnsAtFourAndAbortsAtEight() {
        BrowserStuckDetector d = defaults();
        for (int i = 1; i <= 3; i++) {
            assertThat(d.record("click|button|Go", false).level()).isEqualTo(Level.OK);
        }
        Verdict fourth = d.record("click|button|Go", false);
        assertThat(fourth.level()).isEqualTo(Level.STUCK);
        assertThat(fourth.repeats()).isEqualTo(4);
        assertThat(fourth.reason())
                .contains("appear stuck")
                .contains("identical")
                .contains("browser_snapshot");
        for (int i = 5; i <= 7; i++) {
            assertThat(d.record("click|button|Go", false).level()).isEqualTo(Level.STUCK);
        }
        Verdict eighth = d.record("click|button|Go", false);
        assertThat(eighth.level()).isEqualTo(Level.ABORT);
        assertThat(eighth.reason()).contains("browsing aborted").contains("tell the user");
        assertThat(d.isAborted()).isTrue();
        assertThat(d.abortReason()).isEqualTo(eighth.reason());
    }

    @Test
    void anIdenticalActionThatChangesThePageIsProgressNotALoop() {
        BrowserStuckDetector d = defaults();
        // paging through results: same click, page changes every time
        for (int i = 0; i < 20; i++) {
            assertThat(d.record("click|link|Next page", true).level()).isEqualTo(Level.OK);
        }
    }

    @Test
    void aSingleEffectiveActionResetsBothRuns() {
        BrowserStuckDetector d = defaults();
        d.record("click|a", false);
        d.record("click|a", false);
        d.record("click|a", false);
        assertThat(d.record("click|b", true).level()).isEqualTo(Level.OK);
        // the count starts again from zero
        assertThat(d.record("click|a", false).level()).isEqualTo(Level.OK);
        assertThat(d.record("click|a", false).level()).isEqualTo(Level.OK);
        assertThat(d.record("click|a", false).level()).isEqualTo(Level.OK);
        assertThat(d.record("click|a", false).level()).isEqualTo(Level.STUCK);
    }

    @Test
    void differentActionsThatAllDoNothingAreAlsoStuck() {
        BrowserStuckDetector d = defaults();
        assertThat(d.record("click|a", false).level()).isEqualTo(Level.OK);
        assertThat(d.record("click|b", false).level()).isEqualTo(Level.OK);
        assertThat(d.record("click|c", false).level()).isEqualTo(Level.OK);
        Verdict fourth = d.record("click|d", false);
        assertThat(fourth.level()).isEqualTo(Level.STUCK);
        assertThat(fourth.reason()).doesNotContain("identical");
    }

    @Test
    void abortLatchesUntilReset() {
        BrowserStuckDetector d = defaults();
        for (int i = 0; i < 8; i++) {
            d.record("x", false);
        }
        assertThat(d.isAborted()).isTrue();
        // even an action that "worked" does not lift it
        assertThat(d.record("y", true).level()).isEqualTo(Level.ABORT);
        d.reset();
        assertThat(d.isAborted()).isFalse();
        assertThat(d.abortReason()).isNull();
        assertThat(d.record("y", false).level()).isEqualTo(Level.OK);
    }

    @Test
    void thresholdsAreConfigurableAndSanitised() {
        BrowserStuckDetector tight = new BrowserStuckDetector(2, 3);
        assertThat(tight.record("a", false).level()).isEqualTo(Level.OK);
        assertThat(tight.record("a", false).level()).isEqualTo(Level.STUCK);
        assertThat(tight.record("a", false).level()).isEqualTo(Level.ABORT);
        // nonsense values (abort <= warn, warn < 2) are corrected rather than making every action
        // abort
        BrowserStuckDetector silly = new BrowserStuckDetector(0, 0);
        assertThat(silly.record("a", false).level()).isEqualTo(Level.OK);
        assertThat(silly.record("a", false).level()).isEqualTo(Level.STUCK);
        assertThat(silly.record("a", false).level()).isEqualTo(Level.ABORT);
    }
}
