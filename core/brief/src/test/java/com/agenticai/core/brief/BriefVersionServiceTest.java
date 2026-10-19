package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.brief.dto.ApplyDtos.ApplyRequest;
import com.agenticai.core.brief.dto.ApplyDtos.ApplyResult;
import com.agenticai.core.brief.dto.ApplyDtos.StepDto;
import com.agenticai.core.brief.model.Brief;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BriefVersionServiceTest {

    private final EnvoyKit kit = new EnvoyKit();

    private static Brief withPrompt(Brief b, String prompt) {
        Brief.AgentSpec a = b.agents().get(0);
        Brief.AgentSpec changed = new Brief.AgentSpec(a.ref(), a.name(), a.description(), prompt, a.reasoningDepth(),
            a.tuningMode(), a.generation(), a.toolAllowList(), a.knowledgeRefs(), a.procedureRefs(), a.guardrails(),
            a.edgeRoutingPolicy(), a.shareMemoryWithTenant());
        return new Brief(b.briefVersion(), b.title(), b.goal(), b.locale(), b.clarifications(), b.trust(), List.of(changed),
            b.procedures(), b.workflows(), b.tools(), b.mcpServers(), b.knowledge(), b.secrets(), b.channels(), b.evalSet(),
            b.rehearsal(), b.narrative());
    }

    private UUID appliedWithASecondVersion() {
        UUID id = kit.savedAndApplied(EnvoyKit.richBrief());
        ApplyResult second = kit.apply.apply(id, new ApplyRequest(false, withPrompt(EnvoyKit.richBrief(), "Version two prompt."), 1, "v2"));
        assertThat(second.status()).isEqualTo("SUCCEEDED");
        return id;
    }

    @Test
    void listsVersionsNewestFirstAndDiffsThemAsAJsonPatch() {
        UUID id = appliedWithASecondVersion();

        assertThat(kit.versionService.list(id)).extracting("version").containsExactly(2, 1);
        assertThat(kit.versionService.diff(id, 1, 2).patch().toString()).contains("/agents/0/systemPrompt").contains("Version two prompt.");
        assertThat(kit.versionService.diff(id, 2, 2).patch()).isEmpty();
    }

    @Test
    void rollbackReAppliesTheOlderManifestAsANewVersionThroughTheEngine() {
        UUID id = appliedWithASecondVersion();
        String agentId = kit.entityIdOf(id, "a1");
        String v1Prompt = EnvoyKit.richBrief().agents().get(0).systemPrompt();
        assertThat(kit.ports.agents.get(agentId).systemPrompt()).isEqualTo("Version two prompt.");

        ApplyResult result = kit.versionService.rollback(id, 1, false);

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.steps()).extracting(StepDto::kind).contains("AGENT_UPDATE");
        assertThat(kit.ports.agents.get(agentId).systemPrompt()).isEqualTo(v1Prompt);
        // History is not rewritten: v3 is v1's content, and the brief now points at it.
        assertThat(kit.versions).hasSize(3);
        assertThat(kit.entity(id).getCurrentVersion()).isEqualTo(3);
        assertThat(kit.store.load(id, 3).agents().get(0).systemPrompt()).isEqualTo(v1Prompt);
        assertThat(kit.versions.get(2).getNote()).isEqualTo("Rollback to version 1");
        assertThat(kit.versions.get(2).getParentVersion()).isEqualTo(2);
    }

    @Test
    void rollbackNeverRaisesTheRungEvenIfTheOldManifestSaidSo() {
        UUID id = appliedWithASecondVersion();
        // Forge an old version that claims AUTONOMOUS (a manifest can be edited or imported; the rung it names means nothing).
        Brief forged = BriefVersionStore.withRung(EnvoyKit.richBrief(), TrustRung.AUTONOMOUS);
        kit.versions.add(new BriefVersionEntity(id, 3, kit.store.toJson(forged), 2, "forged", "mallory"));

        kit.versionService.rollback(id, 3, false);

        assertThat(kit.entity(id).getRung()).isEqualTo("UNDER_WATCH");
        assertThat(kit.store.loadCurrent(kit.entity(id)).trust().rung()).isEqualTo("UNDER_WATCH");
        assertThat(kit.ports.tiers.values()).containsOnly(TrustRung.UNDER_WATCH.tier());
    }

    @Test
    void rollbackKeepsTheCurrentRungWhenItIsHigherThanTheOldManifests() {
        UUID id = appliedWithASecondVersion();
        kit.proof[0] = new InMemoryProofSource(InMemoryProofSource.strong(EnvoyKit.NOW));
        kit.trust.setRung(id, TrustRung.TRUSTED);

        kit.versionService.rollback(id, 1, false);

        assertThat(kit.entity(id).getRung()).isEqualTo("TRUSTED");
        assertThat(kit.store.loadCurrent(kit.entity(id)).trust().rung()).isEqualTo("TRUSTED");
    }

    @Test
    void aRollbackThatCannotStartLeavesTheBriefPointingAtWhatIsRunning() {
        UUID id = appliedWithASecondVersion();
        int running = kit.entity(id).getCurrentVersion();
        kit.ledger.startJob(id, running, UUID.randomUUID()); // another apply is in flight

        assertThatThrownBy(() -> kit.versionService.rollback(id, 1, false)).isInstanceOf(BriefConflictException.class);

        assertThat(kit.entity(id).getCurrentVersion()).isEqualTo(running);
    }

    @Test
    void rollbackToTheCurrentVersionOrOfADraftIsRefused() {
        UUID id = appliedWithASecondVersion();
        assertThatThrownBy(() -> kit.versionService.rollback(id, 2, false))
            .isInstanceOf(BriefConflictException.class).hasMessageContaining("already the current");

        UUID draft = kit.save(EnvoyKit.richBrief());
        assertThatThrownBy(() -> kit.versionService.rollback(draft, 1, false))
            .isInstanceOf(BriefConflictException.class).hasMessageContaining("Only an applied solution");
    }
}
