package com.agenticai.core.agents.coding;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.coding.CodingTaskDtos.Budget;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Characterises the progress-event state machine that was lifted out of CodingTaskService. */
class CodingTaskStateTest {

    private static CodingTaskState state() {
        CodingTaskService.Plan plan = new CodingTaskService.Plan("t1", "tenant", "sub", UUID.randomUUID(),
            "fix", "mvn test", "git", "https://x/y.git", null, new Budget(10, 60, 1000, 3), 1L);
        return new CodingTaskState(plan, "e2b");
    }

    @Test
    void testsEventRecordsRunAndMovesPhase() {
        CodingTaskState st = state();
        AtomicReference<String> baseline = new AtomicReference<>();
        st.applyProgress(5L, baseline, "baseline", "abcdef1234567890");
        assertThat(baseline.get()).isEqualTo("abcdef1234567890");
        assertThat(st.events.get(0).text()).isEqualTo("Baseline checkpoint abcdef12");
        st.applyProgress(6L, baseline, "tests", "run 2 exit 1\nboom");
        assertThat(st.tests).hasSize(1);
        assertThat(st.tests.get(0).outputTail()).isEqualTo("boom");
        assertThat(st.phase).isEqualTo("EDITING");
        st.applyProgress(7L, baseline, "tests", "run 3 exit 0\nok");
        assertThat(st.phase).isEqualTo("REVIEWING");
    }

    @Test
    void reviewEventParsesVerdict() {
        CodingTaskState st = state();
        st.applyProgress(1L, new AtomicReference<>(), "review", "rejected: needs a test");
        assertThat(st.review.approved()).isFalse();
        assertThat(st.review.notes()).isEqualTo("needs a test");
    }
}
