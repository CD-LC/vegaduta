package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.tools.ToolCallLogService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A short follow-up ("yes", "continue", "try again") says nothing about which tools the task needs or
 * how hard it is. {@link FollowUpTools} answers both from the conversation: the tools it already used,
 * and whether it is in the middle of a tool task.
 */
class FollowUpToolsTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String SESSION = "session-1";

    private final ToolCallLogRepository repository = mock(ToolCallLogRepository.class);
    private final FollowUpProperties properties = new FollowUpProperties();
    private final FollowUpTools followUp = new FollowUpTools(new ToolCallLogService(repository), properties);

    private void loggedCalls(String... toolNames) {
        List<ToolCallLog> rows = new ArrayList<>();
        Instant at = Instant.now().minusSeconds(600);
        for (String name : toolNames) {
            rows.add(new ToolCallLog(AGENT, SESSION, name, "{}", "ok", null, at, at.plusMillis(5)));
            at = at.plusSeconds(10);
        }
        when(repository.findTop200ByAgentIdAndSessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
                        any(), anyString(), any()))
                .thenReturn(rows);
    }

    @Test
    void aShortFollowUpKeepsTheToolsTheConversationAlreadyUsedMostRecentFirst() {
        loggedCalls("search_orders", "lookup_customer", "search_orders", "send_invoice");

        Set<String> sticky = followUp.stickyTools(AGENT, SESSION, "yes, do it");

        assertThat(sticky).containsExactly("send_invoice", "search_orders", "lookup_customer");
    }

    @Test
    void aMessageWithRealContentIsNotAFollowUpAndTheLogIsNotEvenRead() {
        loggedCalls("search_orders");

        Set<String> sticky = followUp.stickyTools(AGENT, SESSION, "Please find every order from March and total them by customer");

        assertThat(sticky).isEmpty();
        verify(repository, never())
                .findTop200ByAgentIdAndSessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(any(), anyString(), any());
    }

    @Test
    void theShortMessageThresholdIsConfigurableAndCountsCharactersNotWords() {
        loggedCalls("search_orders");
        properties.setShortMessageChars(10);

        assertThat(followUp.stickyTools(AGENT, SESSION, "go ahead now")).isEmpty();
        assertThat(followUp.stickyTools(AGENT, SESSION, "go ahead")).containsExactly("search_orders");
        assertThat(followUp.stickyTools(AGENT, SESSION, "जारी रखें")).as("a non-Latin follow-up counts like any other").containsExactly("search_orders");
    }

    @Test
    void theNumberOfStickyToolsIsCapped() {
        loggedCalls("a_tool", "b_tool", "c_tool", "d_tool");
        properties.setMaxStickyTools(2);

        assertThat(followUp.stickyTools(AGENT, SESSION, "continue")).containsExactly("d_tool", "c_tool");
    }

    @Test
    void theLookbackWindowIsTheConfiguredOne() {
        loggedCalls("search_orders");
        properties.setStickyToolsWindow(Duration.ofMinutes(5));
        Instant before = Instant.now();

        followUp.stickyTools(AGENT, SESSION, "continue");

        org.mockito.ArgumentCaptor<Instant> since = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(repository)
                .findTop200ByAgentIdAndSessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
                        any(), anyString(), since.capture());
        assertThat(Duration.between(since.getValue(), before)).isBetween(Duration.ofMinutes(5).minusSeconds(2), Duration.ofMinutes(5).plusSeconds(2));
    }

    @Test
    void turnedOffNothingIsSticky() {
        loggedCalls("search_orders");
        properties.setStickyToolsEnabled(false);

        assertThat(followUp.stickyTools(AGENT, SESSION, "continue")).isEmpty();
    }

    @Test
    void aLogThatCannotBeReadCostsNothingExtraAndNeverFailsTheTurn() {
        when(repository.findTop200ByAgentIdAndSessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
                        any(), anyString(), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(followUp.stickyTools(AGENT, SESSION, "continue")).isEmpty();
    }

    @Test
    void aFollowUpInAConversationThatUsedToolsStaysOnThePrimaryModel() {
        assertThat(followUp.keepsPrimaryModel("yes", Set.of("search_orders"))).isTrue();
    }

    @Test
    void aFollowUpWithNoToolUseBehindItIsJudgedAsBeforeAndALongMessageAlwaysIs() {
        assertThat(followUp.keepsPrimaryModel("yes", Set.of())).isFalse();
        assertThat(followUp.keepsPrimaryModel("Summarise the attached contract and list the risks", Set.of("search_orders"))).isFalse();
    }

    @Test
    void keepingThePrimaryModelCanBeTurnedOff() {
        properties.setKeepPrimaryModel(false);

        assertThat(followUp.keepsPrimaryModel("yes", Set.of("search_orders"))).isFalse();
    }

    @Test
    void aPolicyOfTheTenantOrAgentCanSwitchStickyToolsOffAndTheLogIsNotRead() {
        loggedCalls("search_orders");

        Set<String> sticky =
                followUp.stickyTools(AGENT, SESSION, "yes, do it", () -> new FollowUpTools.StickyPolicy(false, Duration.ofHours(1)));

        assertThat(sticky).isEmpty();
        verify(repository, never())
                .findTop200ByAgentIdAndSessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(any(), anyString(), any());
    }

    @Test
    void aPolicyCanSwitchStickyToolsOnWherePropertiesHaveThemOff() {
        properties.setStickyToolsEnabled(false);
        loggedCalls("search_orders");

        assertThat(followUp.stickyTools(AGENT, SESSION, "yes, do it")).isEmpty();
        assertThat(followUp.stickyTools(AGENT, SESSION, "yes, do it", () -> new FollowUpTools.StickyPolicy(true, Duration.ofHours(1))))
                .containsExactly("search_orders");
    }

    @Test
    void thePoliciesWindowDecidesHowFarBackTheLogIsRead() {
        loggedCalls("search_orders");
        org.mockito.ArgumentCaptor<Instant> since = org.mockito.ArgumentCaptor.forClass(Instant.class);

        followUp.stickyTools(AGENT, SESSION, "yes, do it", () -> new FollowUpTools.StickyPolicy(true, Duration.ofMinutes(5)));

        verify(repository)
                .findTop200ByAgentIdAndSessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(any(), anyString(), since.capture());
        assertThat(Duration.between(since.getValue(), Instant.now())).isBetween(Duration.ofMinutes(4), Duration.ofMinutes(6));
    }

    @Test
    void thePolicyIsAskedOnlyForAShortFollowUp() {
        loggedCalls("search_orders");
        int[] asked = new int[1];

        followUp.stickyTools(AGENT, SESSION, "Please find every order from March and total them by customer",
                () -> { asked[0]++; return followUp.platformPolicy(); });
        assertThat(asked[0]).isZero();

        followUp.stickyTools(AGENT, SESSION, "yes", () -> { asked[0]++; return followUp.platformPolicy(); });
        assertThat(asked[0]).isEqualTo(1);
    }

    @Test
    void thePlatformPolicyIsWhatThePropertiesSay() {
        properties.setStickyToolsEnabled(false);
        properties.setStickyToolsWindow(Duration.ofMinutes(9));

        assertThat(followUp.platformPolicy()).isEqualTo(new FollowUpTools.StickyPolicy(false, Duration.ofMinutes(9)));
    }
}
