package com.agenticai.core.agents.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RoutineObservationGatewayTest {

    private static final UUID AGENT = UUID.randomUUID();

    @AfterEach
    void clear() {
        RoutineObservationGateway.install(null);
        TenantContext.clear();
    }

    private static final class Recorder implements RoutineObservationGateway.Observer {
        final List<RoutineObservationGateway.Observation> seen = new ArrayList<>();
        boolean enabled = true;
        boolean throwing;

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public void onObservation(RoutineObservationGateway.Observation o) {
            if (throwing) {
                throw new IllegalStateException("boom");
            }
            seen.add(o);
        }
    }

    @Test
    void withNoObserverNothingHappens() {
        assertFalse(RoutineObservationGateway.active());
        RoutineObservationGateway.observe(AGENT, "s", "hello there friend", "chat", Optional.empty());
        RoutineObservationGateway.observeCurrent(AGENT, "hello there friend", "job");
    }

    @Test
    void anObserverGetsTheRequestWithTheTenantCapturedAsData() {
        Recorder r = new Recorder();
        RoutineObservationGateway.install(r);
        TenantContext ctx = TenantContext.of("t1", "me", Set.of());
        RoutineObservationGateway.observe(AGENT, "s1", "summarise my mail", "chat", Optional.of(ctx));
        assertEquals(1, r.seen.size());
        var o = r.seen.get(0);
        assertEquals(AGENT, o.agentId());
        assertEquals("s1", o.sessionId());
        assertEquals("summarise my mail", o.text());
        assertEquals("chat", o.source());
        assertEquals("me", o.scope().context().orElseThrow().getSubject());
    }

    @Test
    void observeCurrentUsesTheThreadsTenant() {
        Recorder r = new Recorder();
        RoutineObservationGateway.install(r);
        TenantContext.set(TenantContext.of("t1", "me", Set.of()));
        RoutineObservationGateway.observeCurrent(AGENT, "summarise my mail", RoutineObservationGateway.JOB);
        assertEquals("job", r.seen.get(0).source());
        assertTrue(r.seen.get(0).scope().context().isPresent());
    }

    @Test
    void aDisabledObserverBlankTextOrNoAgentIsIgnored() {
        Recorder r = new Recorder();
        RoutineObservationGateway.install(r);
        RoutineObservationGateway.observe(AGENT, "s", "  ", "chat", Optional.empty());
        RoutineObservationGateway.observe(null, "s", "summarise my mail", "chat", Optional.empty());
        r.enabled = false;
        RoutineObservationGateway.observe(AGENT, "s", "summarise my mail", "chat", Optional.empty());
        assertTrue(r.seen.isEmpty());
    }

    @Test
    void aFailingObserverNeverReachesTheTurn() {
        Recorder r = new Recorder();
        r.throwing = true;
        RoutineObservationGateway.install(r);
        RoutineObservationGateway.observe(AGENT, "s", "summarise my mail", "chat", Optional.empty());
    }
}
