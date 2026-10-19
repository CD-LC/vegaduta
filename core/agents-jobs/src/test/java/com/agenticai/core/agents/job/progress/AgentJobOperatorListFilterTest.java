package com.agenticai.core.agents.job.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.job.AgentJobController;
import com.agenticai.core.agents.job.AgentJobService;
import com.agenticai.core.agents.job.AgentJobStatus;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * PR-7's third acceptance criterion: <b>a jobs list endpoint with status
 * filtering for an operator view.</b>
 *
 * <p>Lives in this package rather than next to the controller because a
 * feature's tests belong to the feature's own test tree; the file it exercises
 * is shared and is not edited here.
 *
 * <p>The two things worth locking are the two an operator screen actually needs
 * and that a single-status parameter cannot express: several statuses at once
 * ("everything that ended badly"), and the {@code live} sentinel. The sentinel
 * matters beyond convenience - it makes the console's notion of "still going"
 * literally {@link AgentJobStatus#live()}, the same set the schedule overlap
 * guard and V208's unique index are built on, so a fourth definition of "live"
 * cannot drift into existence in a UI.
 */
class AgentJobOperatorListFilterTest {

    private final AgentJobService jobService = mock(AgentJobService.class);
    private final AgentJobController controller = new AgentJobController(jobService);

    @Test
    void severalStatusesAreCombinedIntoOneFilteredQuery() {
        UUID agentId = UUID.randomUUID();

        controller.listForAgent(agentId, List.of("FAILED", "CANCELLED"), 50, 0);

        verify(jobService).listForAgent(
            agentId, Set.of(AgentJobStatus.FAILED, AgentJobStatus.CANCELLED), 50, 0);
    }

    @Test
    void theLiveSentinelExpandsToTheServersOwnDefinitionOfLive() {
        controller.listForTenant(List.of("live"), null, 50, 0);

        verify(jobService).listForTenant(eq(AgentJobStatus.live()), isNull(), eq(50), eq(0));
        // Stated explicitly so a future edit to live() that forgets a
        // non-terminal state fails here rather than silently hiding parked jobs
        // from the one screen an operator watches.
        assertTrue(AgentJobStatus.live().contains(AgentJobStatus.AWAITING_APPROVAL));
        assertEquals(3, AgentJobStatus.live().size());
    }

    @Test
    void noStatusParameterMeansNoFilterRatherThanNoResults() {
        controller.listForTenant(null, null, 50, 0);

        verify(jobService).listForTenant(eq(Set.of()), isNull(), eq(50), eq(0));
    }

    @Test
    void anUnknownStatusIsRejectedWithAMessageNamingTheValue() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () ->
            controller.listForTenant(List.of("PENDING"), null, 50, 0));

        assertTrue(rejected.getMessage().contains("PENDING"));
        assertTrue(rejected.getMessage().contains("live"));
    }

    /**
     * Lower case is what a console query string actually carries, and rejecting
     * it would make the endpoint usable only by clients that happen to
     * upper-case their enum names.
     */
    @Test
    void statusMatchingIsCaseInsensitive() {
        UUID agentId = UUID.randomUUID();

        controller.listForAgent(agentId, List.of("running"), 50, 0);

        verify(jobService).listForAgent(agentId, Set.of(AgentJobStatus.RUNNING), 50, 0);
    }
}
