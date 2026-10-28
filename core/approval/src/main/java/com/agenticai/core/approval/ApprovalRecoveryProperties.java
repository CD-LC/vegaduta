package com.agenticai.core.approval;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.approvals.recovery.*}: what {@link ApprovalRecoveryJob} does with an approved action a dead process left
 * behind. A person's decision is committed before the action runs, so a crash in between used to leave an approved
 * request that nothing ever ran or reported.
 */
@ConfigurationProperties(prefix = "app.approvals.recovery")
public class ApprovalRecoveryProperties {

    /** Off switch for the sweep. */
    private boolean enabled = true;

    /**
     * How long an approved request may sit with its action not started before the sweep runs it. A live decision starts
     * the action within milliseconds; this only has to be longer than a slow database write.
     */
    private Duration unstartedGrace = Duration.ofMinutes(2);

    /**
     * Past this age since the decision, a never-started action is NOT run: it was approved against a situation that
     * may no longer exist. It is closed as failed and the conversation is told.
     */
    private Duration maxRecoveryAge = Duration.ofHours(1);

    /**
     * How long a started action may go without recording its end before it is declared interrupted. Longer than the
     * slowest executor (a code-workspace run), or a live run is reported lost.
     */
    private Duration startedStaleAfter = Duration.ofMinutes(30);

    private int batchSize = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getUnstartedGrace() {
        return unstartedGrace;
    }

    public void setUnstartedGrace(Duration unstartedGrace) {
        this.unstartedGrace = unstartedGrace;
    }

    public Duration getMaxRecoveryAge() {
        return maxRecoveryAge;
    }

    public void setMaxRecoveryAge(Duration maxRecoveryAge) {
        this.maxRecoveryAge = maxRecoveryAge;
    }

    public Duration getStartedStaleAfter() {
        return startedStaleAfter;
    }

    public void setStartedStaleAfter(Duration startedStaleAfter) {
        this.startedStaleAfter = startedStaleAfter;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
