package com.agenticai.core.agents.skills.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.skills.SkillDefinition;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.skills.SkillSource;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** load_skill is the point where a skill's body enters the context; that is also a "use". */
class LoadSkillUsageTest {

    private static final UUID AGENT = UUID.randomUUID();

    @Test
    void loadingACustomSkillCountsOneUse() {
        SkillService service = mock(SkillService.class);
        when(service.loadForAgent(AGENT, "refund-replies"))
                .thenReturn(
                        Optional.of(
                                new SkillDefinition(
                                        UUID.randomUUID(),
                                        "refund-replies",
                                        "d",
                                        "Be kind.",
                                        Map.of(),
                                        SkillSource.CUSTOM)));
        String out =
                new LoadSkillToolCallback(service, AGENT, false).call("{\"name\":\"refund-replies\"}");
        assertTrue(out.startsWith("# Skill: refund-replies"));
        verify(service).recordUse("refund-replies");
    }

    @Test
    void aBuiltInIsNotCounted() {
        SkillService service = mock(SkillService.class);
        when(service.loadForAgent(AGENT, "pdf"))
                .thenReturn(
                        Optional.of(
                                new SkillDefinition(null, "pdf", "d", "Steps.", Map.of(), SkillSource.BUILT_IN)));
        new LoadSkillToolCallback(service, AGENT, false).call("{\"name\":\"pdf\"}");
        verify(service, never()).recordUse(anyString());
    }

    @Test
    void aMissingSkillIsNotCounted() {
        SkillService service = mock(SkillService.class);
        when(service.loadForAgent(AGENT, "ghost")).thenReturn(Optional.empty());
        new LoadSkillToolCallback(service, AGENT, false).call("{\"name\":\"ghost\"}");
        verify(service, never()).recordUse(anyString());
    }
}
