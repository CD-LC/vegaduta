package com.agenticai.core.agents.version;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.FeatureFlagService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * A turn used to evaluate the tenant's flags once to pin the version and again to decide the
 * effective agent (a third time for the experiment variants, in the prompt assembler). With the
 * flags a caller already evaluated, the pin and the overlay evaluate nothing.
 */
@SuppressWarnings("unchecked")
class AgentVersionResolutionFlagsOnceTest {

    private final AgentVersionRepository repository = mock(AgentVersionRepository.class);
    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final UUID agentId = UUID.randomUUID();
    private final Agent live = mock(Agent.class);
    private AgentVersionResolutionService service;

    @BeforeEach
    void setUp() {
        ObjectProvider<FeatureFlagService> flagProvider = mock(ObjectProvider.class);
        when(flagProvider.getIfAvailable()).thenReturn(flags);
        ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
        when(redis.getIfAvailable()).thenReturn(null);
        service =
                new AgentVersionResolutionService(
                        repository, mock(AgentConfigSnapshotCodec.class), flagProvider, redis);
        when(live.getId()).thenReturn(agentId);
        when(repository.findFirstByAgentIdOrderByVersionNumberDesc(agentId))
                .thenReturn(Optional.empty());
        TenantContext.set(TenantContext.of("acme", "u", Set.of()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void passingTheTurnsFlagsEvaluatesNothingMore() {
        Agent effective = service.pinAndApply(agentId, live, Map.of());

        assertSame(live, effective);
        verifyNoInteractions(flags);
    }

    @Test
    void notPassingThemEvaluatesThemExactlyOnceForThePinAndTheOverlayTogether() {
        when(flags.evaluateActiveFlags("acme")).thenReturn(Map.of());

        Agent effective = service.pinAndApply(agentId, live, null);

        assertSame(live, effective);
        verify(flags, times(1)).evaluateActiveFlags("acme");
    }

    @Test
    void theExplicitPublishAnswerIsReadOffTheMapNotOffAFreshEvaluation() {
        Map<String, String> treatment =
                Map.of(
                        AgentVersionResolutionService.EXPLICIT_PUBLISH_FLAG,
                        FeatureFlagService.TREATMENT);

        assertTrue(AgentVersionResolutionService.explicitPublish(treatment));
        assertFalse(AgentVersionResolutionService.explicitPublish(Map.of()));
        assertFalse(AgentVersionResolutionService.explicitPublish(null));

        service.pinAndApply(agentId, live, treatment);

        // Explicit mode resolves the PRODUCTION label, not the newest snapshot.
        verify(repository, atLeastOnce())
                .findByAgentIdAndLabel(agentId, AgentVersionLabel.PRODUCTION);
        verifyNoInteractions(flags);
    }
}
