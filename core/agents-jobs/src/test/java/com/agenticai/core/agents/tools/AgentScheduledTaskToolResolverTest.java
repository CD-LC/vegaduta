package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskService;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskSettings;
import com.agenticai.core.agents.tools.scheduling.ScheduleTaskToolCallbackFactory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * schedule_task is offered to a chat turn only when the flag is on AND the agent opted in via its
 * allow-list AND the turn is not an unattended job; the admin tool picker lists it whenever the
 * flag is on. With the flag off (the default) the resolver is an identity over the tool list.
 */
class AgentScheduledTaskToolResolverTest {

    private AgentScheduledTaskToolResolver resolver;
    private AgentService agentService;
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AgentToolGuard toolGuard = mock(AgentToolGuard.class);
        when(toolGuard.guardAndLog(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        agentService = mock(AgentService.class);
        resolver = new AgentScheduledTaskToolResolver(toolGuard, agentService);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providing(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private static ScheduledTaskSettings settings(boolean enabled) {
        return new ScheduledTaskSettings(
                enabled, true, 10, 3, 15, 3, 2000, 720, true, 3500, "in_app");
    }

    private void wire(boolean enabled) {
        resolver.setFactory(
                providing(
                        new ScheduleTaskToolCallbackFactory(
                                providing(mock(ScheduledTaskService.class)),
                                providing(settings(enabled)))));
    }

    private List<String> turnTools(String sessionId) {
        return names(
                resolver.resolveScheduledTaskTools(
                        agentId, sessionId, new AtomicInteger(), null, null, false));
    }

    private List<String> discoveryTools() {
        return names(
                resolver.resolveScheduledTaskToolsForDiscovery(
                        agentId, "probe", new AtomicInteger(), null, null, false));
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void nothingIsOfferedWhenNeverWiredOrTheBeansAreAbsent() {
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of("schedule_task"));
        assertThat(turnTools("s1")).isEmpty();
        assertThat(discoveryTools()).isEmpty();

        resolver.setFactory(providing(null));
        assertThat(turnTools("s1")).isEmpty();
        assertThat(discoveryTools()).isEmpty();

        resolver.setFactory(
                providing(new ScheduleTaskToolCallbackFactory(providing(null), providing(null))));
        assertThat(turnTools("s1")).isEmpty();
        assertThat(discoveryTools()).isEmpty();

        resolver.setFactory(
                providing(
                        new ScheduleTaskToolCallbackFactory(
                                providing(null), providing(settings(true)))));
        assertThat(turnTools("s1")).isEmpty();
    }

    @Test
    void withTheFlagOffNothingIsOfferedEvenToAnOptedInAgentAndNotInThePicker() {
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of("schedule_task"));
        wire(false);

        assertThat(turnTools("s1")).isEmpty();
        assertThat(discoveryTools()).isEmpty();
    }

    @Test
    void anAgentThatDidNotOptInGetsNothingEvenWithTheFlagOn() {
        wire(true);
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of());
        assertThat(turnTools("s1")).isEmpty();
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of("data_table_query"));
        assertThat(turnTools("s1")).isEmpty();
        when(agentService.getAllowedToolNames(any())).thenReturn(null);
        assertThat(turnTools("s1")).isEmpty();
    }

    @Test
    void anOptedInAgentGetsTheToolWhenTheFlagIsOn() {
        wire(true);
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of("schedule_task"));

        assertThat(turnTools("chat-session-1")).containsExactly("schedule_task");
    }

    @Test
    void anUnattendedJobNeverGetsTheToolSoARunCannotScheduleMoreRuns() {
        wire(true);
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of("schedule_task"));

        assertThat(turnTools(AgentJobSession.sessionIdFor(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void thePickerListsTheToolWheneverTheFlagIsOnWithoutTheOptIn() {
        wire(true);
        when(agentService.getAllowedToolNames(any())).thenReturn(Set.of());

        assertThat(discoveryTools()).containsExactly("schedule_task");
    }

    @Test
    void aFailingAllowListLookupFailsClosed() {
        wire(true);
        when(agentService.getAllowedToolNames(any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(turnTools("s1")).isEmpty();
    }

    @Test
    void aNullAgentGetsNothing() {
        wire(true);
        assertThat(
                        names(
                                resolver.resolveScheduledTaskTools(
                                        null, "s1", new AtomicInteger(), null, null, false)))
                .isEmpty();
    }

    @Test
    void evenAFullyAutonomousAgentStillConfirmsEveryScheduleTaskCall() {
        // NEVER_GATE degrades to POLICY_DEFAULT for these tools, so the classification gate
        // (irreversible AND
        // consequential) always asks a person before a standing unattended job is created.
        assertThat(AgentToolGuard.ALWAYS_CONFIRM_TOOLS).contains("schedule_task", "a2a_call_agent");
    }
}
