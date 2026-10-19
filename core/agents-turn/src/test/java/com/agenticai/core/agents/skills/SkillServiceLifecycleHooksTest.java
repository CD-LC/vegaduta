package com.agenticai.core.agents.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What the lifecycle adds to the existing write paths, and that without it nothing changes. */
class SkillServiceLifecycleHooksTest {

    private BuiltInSkillCatalog builtIns;
    private SkillRepository skills;
    private SkillLifecycleService lifecycle;
    private SkillService service;

    @BeforeEach
    void setUp() {
        builtIns = mock(BuiltInSkillCatalog.class);
        skills = mock(SkillRepository.class);
        lifecycle = mock(SkillLifecycleService.class);
        service =
                new SkillService(
                        builtIns,
                        skills,
                        mock(SkillBuiltInOverrideRepository.class),
                        mock(AgentSkillConfigRepository.class));
        when(skills.save(any(Skill.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void createSnapshotsTheNewSkill() {
        service.setLifecycle(lifecycle);
        service.create("new-skill", "Does a thing. Use when asked.", "Body", Map.of(), "alice");
        verify(lifecycle).afterWrite(any(Skill.class), eq("created"));
    }

    @Test
    void anEditIsReportedToTheLifecycleSoItCanVersionIt() {
        service.setLifecycle(lifecycle);
        Skill existing = new Skill("live", "Does a thing. Use when asked.", "Old body", "alice");
        when(skills.findByIdWithFiles(existing.getId())).thenReturn(Optional.of(existing));
        service.update(existing.getId(), "Does a thing. Use when asked.", "New body", null);
        verify(lifecycle).afterWrite(eq(existing), eq("edited"));
    }

    @Test
    void aProposalBecomesAPendingDraftNotADisabledSkill() {
        service.setLifecycle(lifecycle);
        SkillDefinition created =
                service.propose("learned-skill", "Does a thing. Use when asked.", "Body", "agent:7");
        assertEquals("learned-skill", created.name());
        verify(lifecycle).markPending("learned-skill", null, null);
        verify(skills, never()).findByNameWithFiles(any());
    }

    @Test
    void theDraftCapCountsPendingDraftsOnly() {
        service.setLifecycle(lifecycle);
        when(skills.countByStatus(SkillStatus.PENDING))
                .thenReturn((long) SkillService.MAX_PENDING_PROPOSALS);
        SkillValidationException e =
                assertThrows(
                        SkillValidationException.class,
                        () -> service.propose("one-more", "Does a thing. Use when asked.", "Body", "agent:7"));
        assertTrue(e.getMessage().contains("waiting for an admin"));
        verify(skills, never()).save(any());
    }

    @Test
    void withoutALifecycleAProposalIsStillStoredDisabled() {
        // The pre-V590 behaviour, kept for contexts that have no lifecycle bean.
        Skill[] saved = new Skill[1];
        when(skills.save(any(Skill.class)))
                .thenAnswer(
                        i -> {
                            saved[0] = i.getArgument(0);
                            return saved[0];
                        });
        when(skills.findByNameWithFiles("learned-skill"))
                .thenAnswer(i -> Optional.ofNullable(saved[0]));
        service.propose("learned-skill", "Does a thing. Use when asked.", "Body", "agent:7");
        assertFalse(saved[0].isEnabled());
    }

    @Test
    void usageIsNotCountedWhileTheLearningFlagIsOff() {
        service.setLifecycle(lifecycle);
        service.setCountUsage(false);
        service.recordUse("live");
        verify(lifecycle, org.mockito.Mockito.never()).recordUse("live");
    }

    @Test
    void springWiresTheUsageCounterToTheLearningFlagDefaultOff() throws Exception {
        var setter = SkillService.class.getMethod("setCountUsage", boolean.class);
        String expr =
                setter.getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();
        org.junit.jupiter.api.Assertions.assertEquals("${app.skills.learning.enabled:false}", expr);
    }

    @Test
    void recordUseGoesToTheLifecycleAndIsANoOpWithoutOne() {
        service.recordUse("live"); // no lifecycle yet: must not throw
        service.setLifecycle(lifecycle);
        service.recordUse("live");
        verify(lifecycle).recordUse("live");
    }

    @Test
    void enablingASkillReportsTheWrite() {
        service.setLifecycle(lifecycle);
        Skill s = new Skill("live", "Does a thing. Use when asked.", "Body", "alice");
        s.archive();
        when(skills.findByNameWithFiles("live")).thenReturn(Optional.of(s));
        service.setEnabled("live", true);
        assertTrue(s.isEnabled());
        assertEquals(SkillStatus.ACTIVE, s.getStatus());
        verify(lifecycle).afterWrite(eq(s), eq("enabled"));
    }
}
