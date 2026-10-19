package com.agenticai.core.agents.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

class SkillLifecycleServiceTest {

    private SkillRepository skills;
    private SkillVersionRepository versions;
    private AgentSkillConfigRepository agentConfigs;
    private SkillSupplyChainService chain;
    private SkillLifecycleService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        skills = mock(SkillRepository.class);
        versions = mock(SkillVersionRepository.class);
        agentConfigs = mock(AgentSkillConfigRepository.class);
        chain = mock(SkillSupplyChainService.class);
        ObjectProvider<SkillSupplyChainService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(chain);
        service = new SkillLifecycleService(skills, versions, agentConfigs, provider, 90);
        when(skills.save(any(Skill.class))).thenAnswer(i -> i.getArgument(0));
        when(versions.findBySkillIdOrderByVersionDesc(any())).thenReturn(List.of());
        when(versions.findFirstBySkillIdOrderByVersionDesc(any())).thenReturn(Optional.empty());
        when(agentConfigs.findAllowedPairs()).thenReturn(List.of());
    }

    private Skill skill(String name) {
        Skill s = new Skill(name, "Does " + name + ". Use when needed.", "Body of " + name, "alice");
        s.setContentSha256("hash-" + name);
        when(skills.findByIdWithFiles(s.getId())).thenReturn(Optional.of(s));
        when(skills.findById(s.getId())).thenReturn(Optional.of(s));
        when(skills.findByName(name)).thenReturn(Optional.of(s));
        when(skills.existsById(s.getId())).thenReturn(true);
        return s;
    }

    private Skill pending(String name) {
        Skill s = skill(name);
        s.markPending(SkillOrigin.LEARNED, UUID.randomUUID(), "learned");
        return s;
    }

    // ---------------------------------------------------------------- the enabled invariant

    @Test
    void aNewSkillIsActiveAuthoredVersionOne() {
        Skill s = skill("a-skill");
        assertEquals(SkillStatus.ACTIVE, s.getStatus());
        assertEquals(SkillOrigin.AUTHORED, s.getOrigin());
        assertEquals(1, s.getContentVersion());
        assertTrue(s.isEnabled());
    }

    @Test
    void enabledAlwaysTracksActive() {
        Skill s = skill("a-skill");
        s.setEnabled(false);
        assertEquals(SkillStatus.ARCHIVED, s.getStatus());
        s.setEnabled(true);
        assertEquals(SkillStatus.ACTIVE, s.getStatus());
        s.markPending(SkillOrigin.LEARNED, null, null);
        assertFalse(s.isEnabled());
        // switching a pending draft "off" must not turn it into an archived skill
        s.setEnabled(false);
        assertEquals(SkillStatus.PENDING, s.getStatus());
        s.reject("no");
        assertFalse(s.isEnabled());
        assertEquals(SkillStatus.REJECTED, s.getStatus());
        s.archive();
        assertFalse(s.isEnabled());
    }

    // ---------------------------------------------------------------- approve / reject / archive

    @Test
    void approvingADraftMakesItLiveAndPinsItAndSnapshotsVersionOne() {
        Skill s = pending("learned-skill");
        SkillLifecycleService.Reviewed out = service.approve(s.getId());
        assertEquals(SkillStatus.ACTIVE, out.view().status());
        assertTrue(out.metadata().enabled());
        assertTrue(s.isEnabled());
        assertEquals(SkillOrigin.LEARNED, out.view().origin());
        verify(chain).pinIfUnpinned(any(Skill.class), org.mockito.ArgumentMatchers.startsWith("approve:"));
        ArgumentCaptor<SkillVersion> snap = ArgumentCaptor.forClass(SkillVersion.class);
        verify(versions).save(snap.capture());
        assertEquals(1, snap.getValue().getVersion());
        assertEquals("hash-learned-skill", snap.getValue().getContentSha256());
    }

    @Test
    void approvingAnActiveSkillIsIdempotent() {
        Skill s = skill("live");
        service.approve(s.getId());
        verify(skills, never()).save(any());
        verify(versions, never()).save(any());
    }

    @Test
    void anArchivedOrRejectedSkillCanBeApprovedAgain() {
        Skill s = skill("was-off");
        s.archive();
        assertEquals(SkillStatus.ACTIVE, service.approve(s.getId()).view().status());
        Skill r = pending("was-rejected");
        r.reject("no");
        assertEquals(SkillStatus.ACTIVE, service.approve(r.getId()).view().status());
    }

    @Test
    void rejectKeepsTheDraftWithTheReviewersNote() {
        Skill s = pending("bad-draft");
        SkillLifecycleService.Reviewed out =
                service.reject(s.getId(), "  too specific to one customer  ");
        assertEquals(SkillStatus.REJECTED, out.view().status());
        assertFalse(out.metadata().enabled());
        assertEquals("too specific to one customer", out.view().reviewNote());
    }

    @Test
    void onlyADraftCanBeRejected() {
        Skill live = skill("live");
        ResponseStatusException e =
                assertThrows(ResponseStatusException.class, () -> service.reject(live.getId(), null));
        assertEquals(409, e.getStatusCode().value());
        assertEquals(SkillStatus.ACTIVE, live.getStatus());
    }

    @Test
    void archiveSwitchesOffAndKeepsEverything() {
        Skill s = skill("live");
        assertEquals(SkillStatus.ARCHIVED, service.archive(s.getId()).view().status());
        assertFalse(s.isEnabled());
        verify(skills, never()).delete(any(Skill.class));
    }

    @Test
    void unknownIdsAre404() {
        UUID ghost = UUID.randomUUID();
        when(skills.findByIdWithFiles(ghost)).thenReturn(Optional.empty());
        assertThrows(SkillNotFoundException.class, () -> service.approve(ghost));
        assertThrows(SkillNotFoundException.class, () -> service.archive(ghost));
        assertThrows(SkillNotFoundException.class, () -> service.reject(ghost, null));
    }

    // ---------------------------------------------------------------- versions

    @Test
    void anEditOfALiveSkillIsANewVersionAndAnUnchangedWriteIsNot() {
        Skill s = skill("live");
        service.afterWrite(s, "created");
        ArgumentCaptor<SkillVersion> first = ArgumentCaptor.forClass(SkillVersion.class);
        verify(versions).save(first.capture());
        assertEquals(1, first.getValue().getVersion());

        SkillVersion v1 = first.getValue();
        when(versions.findFirstBySkillIdOrderByVersionDesc(s.getId())).thenReturn(Optional.of(v1));
        service.afterWrite(s, "touched, same content");
        verify(versions, org.mockito.Mockito.times(1)).save(any());
        assertEquals(1, s.getContentVersion());

        s.setContentSha256("hash-changed");
        service.afterWrite(s, "edited");
        ArgumentCaptor<SkillVersion> all = ArgumentCaptor.forClass(SkillVersion.class);
        verify(versions, org.mockito.Mockito.times(2)).save(all.capture());
        assertEquals(2, all.getAllValues().get(1).getVersion());
        assertEquals(2, s.getContentVersion());
        assertEquals("edited", all.getAllValues().get(1).getNote());
    }

    @Test
    void aDraftIsNotVersionedUntilItGoesLive() {
        Skill s = pending("draft");
        service.afterWrite(s, "edited");
        verify(versions, never()).save(any());
    }

    @Test
    void oldSnapshotsArePruned() {
        Skill s = skill("live");
        List<SkillVersion> history = new ArrayList<>();
        for (int v = SkillLifecycleService.MAX_VERSIONS_KEPT + 3; v >= 1; v--) {
            history.add(new SkillVersion(s.getId(), v, "d", "b", null, "h" + v, "alice", null));
        }
        when(versions.findBySkillIdOrderByVersionDesc(s.getId())).thenReturn(history);
        when(versions.findFirstBySkillIdOrderByVersionDesc(s.getId()))
                .thenReturn(Optional.of(history.get(0)));
        s.setContentSha256("brand-new");
        service.afterWrite(s, "edited");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<SkillVersion>> removed = ArgumentCaptor.forClass(Iterable.class);
        verify(versions).deleteAll(removed.capture());
        assertEquals(3, ((List<SkillVersion>) removed.getValue()).size());
        assertEquals(1, ((List<SkillVersion>) removed.getValue()).get(2).getVersion());
    }

    @Test
    void aSnapshotFailureNeverBreaksTheWrite() {
        Skill s = skill("live");
        when(versions.save(any())).thenThrow(new IllegalStateException("db down"));
        service.afterWrite(s, "created"); // must not throw
    }

    // ---------------------------------------------------------------- usage and effectiveness

    @Test
    void effectivenessIsUnknownUntilAnOutcomeWasSeenThenSmoothed() {
        assertNull(SkillLifecycleService.effectiveness(0, 0));
        assertEquals(2.0 / 3.0, SkillLifecycleService.effectiveness(1, 0), 1e-9);
        assertEquals(0.5, SkillLifecycleService.effectiveness(4, 4), 1e-9);
        assertEquals(1.0 / 12.0, SkillLifecycleService.effectiveness(0, 10), 1e-9);
    }

    @Test
    void usageAndOutcomesAreBulkUpdatesThatNeverThrow() {
        service.recordUse("live");
        verify(skills).recordUse(org.mockito.ArgumentMatchers.eq("live"), any(Instant.class));
        service.recordOutcome(Set.of("live"), true);
        service.recordOutcome(Set.of("live"), false);
        service.recordOutcome(Set.of(), true);
        verify(skills).recordSuccess(anyCollection());
        verify(skills).recordFailure(anyCollection());

        when(skills.recordUse(any(), any())).thenThrow(new IllegalStateException("db down"));
        service.recordUse("live");
    }

    // ---------------------------------------------------------------- staleness

    private SkillLifecycleService.SkillView view(Skill s) {
        return service.view(s, List.of(), Instant.now());
    }

    @Test
    void aFreshSkillIsNotStale() {
        assertFalse(view(skill("fresh")).stale());
    }

    @Test
    void aSkillNobodyHasUsedForAWhileIsFlagged() {
        Skill s = skill("old");
        SkillLifecycleService.SkillView v =
                service.view(s, List.of(), Instant.now().plus(91, ChronoUnit.DAYS));
        assertTrue(v.stale());
        assertTrue(v.staleReason().startsWith("Never used"));
    }

    @Test
    void aDraftOrArchivedSkillIsNeverFlaggedStale() {
        Skill p = pending("draft");
        assertFalse(service.view(p, List.of(), Instant.now().plus(400, ChronoUnit.DAYS)).stale());
    }

    @Test
    void fewOutcomesNeverCondemnASkillButManyBadOnesDo() throws Exception {
        Skill s = skill("flaky");
        setField(s, "failures", 3L);
        assertFalse(view(s).stale(), "3 bad outcomes are not yet a verdict");
        setField(s, "failures", 9L);
        setField(s, "successes", 1L);
        SkillLifecycleService.SkillView v = view(s);
        assertTrue(v.stale());
        assertTrue(v.staleReason().startsWith("Low success rate"), v.staleReason());
        assertNotNull(v.effectiveness());
    }

    private static void setField(Object target, String field, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ---------------------------------------------------------------- agent binding

    private AgentSkillConfig config(UUID agent, Set<String> allowed) {
        AgentSkillConfig c = new AgentSkillConfig(agent);
        c.update(true, allowed);
        return c;
    }

    @Test
    void bindingAddsTheSkillToAnExistingAllowListAndRemovesItElsewhere() {
        Skill s = skill("shared");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        AgentSkillConfig onA = config(a, Set.of("other"));
        AgentSkillConfig onB = config(b, Set.of("other", "shared"));
        when(agentConfigs.findAllById(anyIterable())).thenReturn(List.of(onA, onB));
        when(agentConfigs.findAllowedPairs())
                .thenReturn(
                        List.of(
                                new Object[] {a, "other"},
                                new Object[] {b, "other"},
                                new Object[] {b, "shared"}));

        service.setAgents(s.getId(), List.of(a));

        assertEquals(Set.of("other", "shared"), onA.getAllowedSkillNames());
        assertEquals(Set.of("other"), onB.getAllowedSkillNames());
    }

    @Test
    void anUnrestrictedAgentIsLeftAloneBecauseAddingWouldNarrowIt() {
        Skill s = skill("shared");
        UUID unrestricted = UUID.randomUUID();
        when(agentConfigs.findAllById(anyIterable())).thenReturn(List.of());
        SkillLifecycleService.AgentBinding binding =
                service.setAgents(s.getId(), List.of(unrestricted));
        assertEquals(List.of(unrestricted), binding.unrestricted());
        verify(agentConfigs, never()).save(any());
    }

    @Test
    void removingTheLastAllowedSkillFromAnAgentIsRefusedInsteadOfWideningIt() {
        Skill s = skill("only-one");
        UUID a = UUID.randomUUID();
        AgentSkillConfig onA = config(a, Set.of("only-one"));
        when(agentConfigs.findAllById(anyIterable())).thenReturn(List.of(onA));
        when(agentConfigs.findAllowedPairs()).thenReturn(List.<Object[]>of(new Object[] {a, "only-one"}));
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class, () -> service.setAgents(s.getId(), List.of()));
        assertEquals(409, e.getStatusCode().value());
        assertEquals(Set.of("only-one"), onA.getAllowedSkillNames());
    }

    @Test
    void bindingReadsOnlyTheRowsItCanChange() {
        Skill s = skill("shared");
        UUID a = UUID.randomUUID();
        when(agentConfigs.findAllById(anyIterable())).thenReturn(List.of(config(a, Set.of("x"))));
        service.setAgents(s.getId(), List.of(a));
        verify(agentConfigs, never()).findAll();
    }

    // ---------------------------------------------------------------- proposals

    @Test
    void aProposedDraftLosesTheSnapshotCreateTookWhileItWasStillActive() {
        Skill s = skill("fresh-draft");
        UUID agent = UUID.randomUUID();
        service.markPending("fresh-draft", agent, "why it was drafted");
        assertEquals(SkillStatus.PENDING, s.getStatus());
        assertFalse(s.isEnabled());
        assertEquals(agent, s.getLearnedForAgentId());
        verify(versions).deleteBySkillId(s.getId());
    }

    // ---------------------------------------------------------------- views

    @Test
    void theViewCarriesTheAgentsThatAllowTheSkill() {
        Skill s = skill("shared");
        UUID a = UUID.randomUUID();
        when(skills.findAllByOrderByNameAsc()).thenReturn(List.of(s));
        when(agentConfigs.findAllowedPairs()).thenReturn(List.<Object[]>of(new Object[] {a, "shared"}));
        Map<UUID, SkillLifecycleService.SkillView> views = service.views();
        assertEquals(List.of(a), views.get(s.getId()).agentIds());
        assertEquals(SkillStatus.ACTIVE, views.get(s.getId()).status());
    }
}
