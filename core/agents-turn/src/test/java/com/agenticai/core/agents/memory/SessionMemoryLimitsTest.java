package com.agenticai.core.agents.memory;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

/** The session list is trimmed to {@code app.memory.limits.session-max-stored-entries}, not to a constant. */
class SessionMemoryLimitsTest extends SessionMemoryServiceTestBase {

    @Test
    void appendTrimsToTheConfiguredStorageBound() {
        SessionMemoryService service = newService(40, 32000, 0);
        MemoryLimitsProperties limits = new MemoryLimitsProperties();
        limits.setSessionMaxStoredEntries(120);
        service.setLimits(limits);

        service.appendTurn(SESSION_ID, user("hello"));

        verify(listOps).rightPush(eq(REDIS_KEY), anyString());
        verify(listOps).trim(REDIS_KEY, -120, -1);
    }

    @Test
    void replaceHistoryKeepsOnlyTheConfiguredNumberOfNewestTurns() {
        SessionMemoryService service = newService(40, 32000, 0);
        MemoryLimitsProperties limits = new MemoryLimitsProperties();
        limits.setSessionMaxStoredEntries(3);
        service.setLimits(limits);

        service.replaceHistory(
                SESSION_ID,
                java.util.List.of(user("a"), assistant("b"), user("c"), assistant("d"), user("e")));

        verify(listOps)
                .rightPushAll(
                        eq(REDIS_KEY),
                        org.mockito.ArgumentMatchers.<java.util.Collection<String>>argThat(c -> c.size() == 3));
    }
}
