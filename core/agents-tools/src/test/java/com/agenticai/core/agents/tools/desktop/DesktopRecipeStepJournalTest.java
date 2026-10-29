package com.agenticai.core.agents.tools.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.approval.ToolCallScope;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DesktopRecipeStepJournalTest {

    private static final UUID AGENT = UUID.randomUUID();

    @Test
    void eachStepWritesAJournalRowUnderTheCallingScope() throws IOException {
        ToolCallLogRepository journal = mock(ToolCallLogRepository.class);
        DesktopRecipeReplayer.Executor raw =
                (path, body) -> JsonNodeFactory.instance.objectNode().put("ok", true);
        DesktopRecipeReplayer.Executor wrapped = DesktopRecipeStepJournal.wrap(raw, journal);

        try (var ignored = ToolCallScope.open("run_desktop_recipe", AGENT, "s1", null, null)) {
            wrapped.post("/click", Map.of("x", 10, "y", 20));
            wrapped.post("/type", Map.of("text", "s3cr3t-value"));
        }

        ArgumentCaptor<ToolCallLog> rows = ArgumentCaptor.forClass(ToolCallLog.class);
        verify(journal, org.mockito.Mockito.times(2)).save(rows.capture());
        ToolCallLog first = rows.getAllValues().get(0);
        assertThat(first.getAgentId()).isEqualTo(AGENT);
        assertThat(first.getSessionId()).isEqualTo("s1");
        assertThat(first.getToolName()).isEqualTo("desktop_recipe_step/click");
        assertThat(first.getToolInput()).contains("\"/click\"").contains("\"x\":10");

        // The typed text - the field a resolved {{secret.*}} lands in - is never journaled.
        ToolCallLog second = rows.getAllValues().get(1);
        assertThat(second.getToolName()).isEqualTo("desktop_recipe_step/type");
        assertThat(second.getToolInput()).doesNotContain("s3cr3t-value");
        assertThat(second.getToolOutput()).contains("\"ok\":true");
    }

    @Test
    void outsideAToolScopeNothingIsJournaled() throws IOException {
        ToolCallLogRepository journal = mock(ToolCallLogRepository.class);
        DesktopRecipeReplayer.Executor raw =
                (path, body) -> JsonNodeFactory.instance.objectNode().put("ok", true);
        DesktopRecipeReplayer.Executor wrapped = DesktopRecipeStepJournal.wrap(raw, journal);

        wrapped.post("/click", Map.of());

        verify(journal, never()).save(any());
    }

    @Test
    void aThrowingStepIsJournaledWithItsError() {
        ToolCallLogRepository journal = mock(ToolCallLogRepository.class);
        DesktopRecipeReplayer.Executor raw =
                (path, body) -> {
                    throw new IOException("sidecar is down");
                };
        DesktopRecipeReplayer.Executor wrapped = DesktopRecipeStepJournal.wrap(raw, journal);

        try (var ignored = ToolCallScope.open("run_desktop_recipe", AGENT, "s1", null, null)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> wrapped.post("/open", Map.of()))
                    .isInstanceOf(IOException.class);
        }

        ArgumentCaptor<ToolCallLog> rows = ArgumentCaptor.forClass(ToolCallLog.class);
        verify(journal).save(rows.capture());
        assertThat(rows.getValue().getErrorMessage()).contains("IOException").contains("sidecar is down");
        assertThat(rows.getValue().getToolOutput()).isNull();
    }

    @Test
    void theInputDropsEveryFieldThatCanCarryASecret() {
        String input =
                DesktopRecipeStepJournal.journalableInput(
                        "/type",
                        Map.of("text", "hunter2", "key", "enter", "keys", "a,b", "password", "p", "value", "v", "x", 1));
        assertThat(input).doesNotContain("hunter2").doesNotContain("\"password\"");
        assertThat(input).contains("\"x\":1");
    }
}
