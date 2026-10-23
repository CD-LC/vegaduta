package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.turn.quality.TaskPlanningService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatPromptDirectivesAnnouncementTest {

    @Test
    void dateDirectiveCarriesTheDate() {
        assertTrue(ChatPromptDirectives.currentDateDirective(LocalDate.of(2026, 10, 2)).contains("2026-10-02"));
    }

    @Test
    void plannerToolHint() {
        assertEquals("", TaskPlanningService.toolsHint(null));
        assertTrue(TaskPlanningService.toolsHint(List.of()).contains("NO tools"));
        assertTrue(TaskPlanningService.toolsHint(List.of("web_search", "fetch_web_page")).contains("web_search, fetch_web_page"));
    }
}
