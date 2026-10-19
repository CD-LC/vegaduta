package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.experiment.AgentExperiment;
import com.agenticai.core.agents.experiment.AgentExperimentConflictException;
import com.agenticai.core.agents.experiment.AgentExperimentService;
import com.agenticai.core.agents.experiment.ExperimentStatus;
import com.agenticai.core.brief.dto.LedgerDtos.ExperimentRequest;
import com.agenticai.core.brief.dto.LedgerDtos.ExperimentStarted;
import com.agenticai.core.quality.dataset.AgentHeadVersionLookup;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** "Run as experiment": delegates to the platform's experiment service with the conventions the ledger relies on. */
class BriefExperimentServiceTest {

    private final EnvoyKit kit = new EnvoyKit();
    private final AgentExperimentService experiments = mock(AgentExperimentService.class);
    private final AgentHeadVersionLookup heads = mock(AgentHeadVersionLookup.class);
    private BriefExperimentService service;
    private UUID id;
    private final UUID agentUuid = UUID.randomUUID();
    private final UUID versionB = UUID.randomUUID();

    @BeforeEach
    void applyAndBindARealLookingAgentId() {
        service = new BriefExperimentService(kit.store, kit.ledger, experiments, heads);
        id = kit.savedAndApplied(EnvoyKit.richBrief());
        // The fake platform hands out ids like "agent-1"; the experiment service wants a UUID.
        kit.ledger.bind(id, "a1", "AGENT", agentUuid.toString());
    }

    private AgentExperiment experiment(ExperimentStatus status) {
        AgentExperiment e = mock(AgentExperiment.class);
        when(e.getId()).thenReturn(UUID.randomUUID());
        when(e.getName()).thenReturn(ProofLedgerService.EXPERIMENT_PREFIX + id + " running vs earlier version");
        when(e.getStatus()).thenReturn(status);
        when(e.getTrafficPercent()).thenReturn(50);
        return e;
    }

    @Test
    void createsAndStartsAnExperimentNamedForTheBriefWithTheRunningVersionAsControl() {
        AgentExperiment draft = experiment(ExperimentStatus.DRAFT);
        AgentExperiment running = experiment(ExperimentStatus.RUNNING);
        when(experiments.create(eq(agentUuid), anyString(), eq(versionB), eq(50), isNull(), anyString())).thenReturn(draft);
        when(experiments.start(agentUuid, draft.getId())).thenReturn(running);

        ExperimentStarted out = service.start(id, new ExperimentRequest("a1", null, versionB.toString(), null, null));

        assertThat(out.status()).isEqualTo("RUNNING");
        assertThat(out.agentRef()).isEqualTo("a1");
        assertThat(out.agentId()).isEqualTo(agentUuid.toString());
        assertThat(out.trafficPercent()).isEqualTo(50);
        verify(experiments).create(eq(agentUuid), eq(ProofLedgerService.EXPERIMENT_PREFIX + id + " running vs earlier version"),
            eq(versionB), eq(50), isNull(), anyString());
    }

    @Test
    void startFalseLeavesTheExperimentInDraft() {
        AgentExperiment draft = experiment(ExperimentStatus.DRAFT);
        when(experiments.create(any(), anyString(), any(), anyInt(), any(), anyString())).thenReturn(draft);

        ExperimentStarted out = service.start(id, new ExperimentRequest("a1", null, versionB.toString(), 20, false));

        assertThat(out.status()).isEqualTo("DRAFT");
        verify(experiments, never()).start(any(), any());
    }

    @Test
    void versionAMustBeTheRunningVersionBecauseTheControlIsAlwaysTheRunningAgent() {
        when(heads.findHeadVersionId(agentUuid)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThatThrownBy(() -> service.start(id, new ExperimentRequest("a1", UUID.randomUUID().toString(), versionB.toString(), null, null)))
            .isInstanceOf(BriefValidationException.class).hasMessageContaining("versionA");
        verify(experiments, never()).create(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void theRunningVersionIdIsAcceptedAsVersionA() {
        UUID head = UUID.randomUUID();
        when(heads.findHeadVersionId(agentUuid)).thenReturn(Optional.of(head));
        AgentExperiment draft = experiment(ExperimentStatus.DRAFT);
        when(experiments.create(any(), anyString(), any(), anyInt(), any(), anyString())).thenReturn(draft);
        AgentExperiment running = experiment(ExperimentStatus.RUNNING);
        when(experiments.start(any(), any())).thenReturn(running);

        ExperimentStarted out = service.start(id, new ExperimentRequest("a1", head.toString(), versionB.toString(), null, null));

        assertThat(out.status()).isEqualTo("RUNNING");
    }

    @Test
    void anUnknownAgentRefAMissingVersionOrABadTrafficShareIsRefusedBeforeAnythingIsCreated() {
        assertThatThrownBy(() -> service.start(id, new ExperimentRequest("a9", null, versionB.toString(), null, null)))
            .isInstanceOf(BriefValidationException.class).hasMessageContaining("agentRef");
        assertThatThrownBy(() -> service.start(id, new ExperimentRequest("a1", null, null, null, null)))
            .isInstanceOf(BriefValidationException.class).hasMessageContaining("versionB");
        assertThatThrownBy(() -> service.start(id, new ExperimentRequest("a1", null, "not-a-uuid", null, null)))
            .isInstanceOf(BriefValidationException.class).hasMessageContaining("versionB");
        assertThatThrownBy(() -> service.start(id, new ExperimentRequest("a1", null, versionB.toString(), 100, null)))
            .isInstanceOf(BriefValidationException.class).hasMessageContaining("trafficPercent");
        assertThatThrownBy(() -> service.start(id, null)).isInstanceOf(BriefValidationException.class);
        verify(experiments, never()).create(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void aBriefThatIsNotRunningCannotStartOne() {
        UUID draft = kit.save(EnvoyKit.richBrief());

        assertThatThrownBy(() -> service.start(draft, new ExperimentRequest("a1", null, versionB.toString(), null, null)))
            .isInstanceOf(BriefConflictException.class);
    }

    @Test
    void thePlatformsOneActiveExperimentRuleComesBackAsAConflictWithItsOwnSentence() {
        when(experiments.create(any(), anyString(), any(), anyInt(), any(), anyString()))
            .thenThrow(new AgentExperimentConflictException("Agent already has an active experiment."));

        assertThatThrownBy(() -> service.start(id, new ExperimentRequest("a1", null, versionB.toString(), null, null)))
            .isInstanceOf(BriefConflictException.class).hasMessageContaining("already has an active experiment");
    }

    @Test
    void aVersionThatIsNotTheAgentsOwnIsAValidationErrorOnVersionB() {
        when(experiments.create(any(), anyString(), any(), anyInt(), any(), anyString()))
            .thenThrow(new IllegalArgumentException("Version x is not a stored configuration of agent y."));

        assertThatThrownBy(() -> service.start(id, new ExperimentRequest("a1", null, versionB.toString(), null, null)))
            .isInstanceOf(BriefValidationException.class).hasMessageContaining("stored configuration");
    }
}
