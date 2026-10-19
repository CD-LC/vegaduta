package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.api.RlPolicyPort;
import com.agenticai.core.rl.domain.PolicyDimension;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RlArmPrefetchTest {

    private final RlPolicyPort port = mock(RlPolicyPort.class);
    private final UUID agentId = UUID.randomUUID();

    private static final EnumSet<PolicyDimension> FIVE = EnumSet.of(
        PolicyDimension.MODEL_ROUTE, PolicyDimension.TOOL_SUBSET, PolicyDimension.PROMPT_VARIANT,
        PolicyDimension.GEN_PARAMS, PolicyDimension.REASONING_DEPTH);

    private static ChatHotpath flag(boolean rlBatch) {
        return new ChatHotpath(Duration.ZERO, Duration.ZERO, rlBatch, false);
    }

    @Test
    void flagOffIsTheSameFiveSequentialSelectArmCallsAndNoBatchCall() {
        when(port.selectArm(any(), any(), isNull())).thenReturn(Optional.empty());
        RlArmPrefetch rl = flag(false).rlArms(true);
        for (PolicyDimension d : FIVE) {
            assertThat(rl.select(port, agentId, d)).isEmpty();
        }
        for (PolicyDimension d : FIVE) {
            verify(port).selectArm(agentId, d, null);
        }
        verify(port, never()).selectArms(any(), any(), any());
    }

    @Test
    void flagOnIssuesOneBatchAndServesEveryDimensionFromIt() {
        var arm = new RlPolicyPort.ArmSelection(UUID.randomUUID(), "FAST", "{}");
        when(port.selectArms(any(), any(), isNull())).thenReturn(Map.of(
            PolicyDimension.REASONING_DEPTH, Optional.of(arm),
            PolicyDimension.MODEL_ROUTE, Optional.empty(),
            PolicyDimension.TOOL_SUBSET, Optional.empty(),
            PolicyDimension.PROMPT_VARIANT, Optional.empty(),
            PolicyDimension.GEN_PARAMS, Optional.empty()));
        RlArmPrefetch rl = flag(true).rlArms(true);

        assertThat(rl.select(port, agentId, PolicyDimension.MODEL_ROUTE)).isEmpty();
        assertThat(rl.select(port, agentId, PolicyDimension.TOOL_SUBSET)).isEmpty();
        assertThat(rl.select(port, agentId, PolicyDimension.PROMPT_VARIANT)).isEmpty();
        assertThat(rl.select(port, agentId, PolicyDimension.GEN_PARAMS)).isEmpty();
        assertThat(rl.select(port, agentId, PolicyDimension.REASONING_DEPTH)).containsSame(arm);

        verify(port, times(1)).selectArms(any(), any(), isNull());
        verify(port, never()).selectArm(any(), any(), any());
    }

    @Test
    void manualTuningNeverAsksForTheTunedDimensions() {
        when(port.selectArms(any(), any(), isNull())).thenReturn(Map.of(
            PolicyDimension.TOOL_SUBSET, Optional.empty()));
        RlArmPrefetch rl = flag(true).rlArms(false);
        rl.select(port, agentId, PolicyDimension.TOOL_SUBSET);
        verify(port).selectArms(agentId,
            EnumSet.of(PolicyDimension.MODEL_ROUTE, PolicyDimension.TOOL_SUBSET, PolicyDimension.PROMPT_VARIANT), null);
    }

    @Test
    void batchReturningNullFallsBackToSelectArm() {
        when(port.selectArms(any(), any(), isNull())).thenReturn(null);
        when(port.selectArm(agentId, PolicyDimension.TOOL_SUBSET, null)).thenReturn(Optional.empty());
        RlArmPrefetch rl = flag(true).rlArms(true);
        assertThat(rl.select(port, agentId, PolicyDimension.TOOL_SUBSET)).isEmpty();
        verify(port).selectArm(agentId, PolicyDimension.TOOL_SUBSET, null);
        verify(port, times(1)).selectArms(any(), any(), any());
    }
}
