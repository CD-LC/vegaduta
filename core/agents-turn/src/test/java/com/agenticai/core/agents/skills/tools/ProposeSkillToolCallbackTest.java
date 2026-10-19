package com.agenticai.core.agents.skills.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.skills.SkillDefinition;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.skills.SkillValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProposeSkillToolCallbackTest {

    private final SkillService skillService = mock(SkillService.class);
    private final UUID agentId = UUID.randomUUID();
    private final ProposeSkillToolCallback tool =
            new ProposeSkillToolCallback(skillService, agentId);

    @Test
    void savesADraftAttributedToTheAgentAndSaysItNeedsReview() {
        SkillDefinition saved = mock(SkillDefinition.class);
        when(saved.name()).thenReturn("monthly-summary");
        when(skillService.propose(eq("monthly-summary"), any(), any(), eq("agent:" + agentId)))
                .thenReturn(saved);

        String out =
                tool.call(
                        "{\"name\":\"monthly-summary\",\"description\":\"Builds the monthly summary.\",\"instructions\":\"# Steps\"}");

        assertTrue(out.contains("draft"));
        assertTrue(out.contains("admin"));
    }

    @Test
    void missingFieldsNeverReachTheService() {
        String out = tool.call("{\"name\":\"x\"}");

        assertTrue(out.startsWith("Could not save"));
        verify(skillService, never()).propose(any(), any(), any(), any());
    }

    @Test
    void validationMessageIsHandedBackToTheModel() {
        when(skillService.propose(any(), any(), any(), any()))
                .thenThrow(new SkillValidationException("name is reserved"));

        String out = tool.call("{\"name\":\"a\",\"description\":\"b\",\"instructions\":\"c\"}");

        assertTrue(out.contains("name is reserved"));
    }
}
