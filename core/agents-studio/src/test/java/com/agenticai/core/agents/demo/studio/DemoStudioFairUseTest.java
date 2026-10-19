package com.agenticai.core.agents.demo.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.demo.DemoSession;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DemoStudioFairUseTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private DemoStudioFairUse fairUse;

    @BeforeEach
    void setUp() {
        // 3 per minute per sandbox, 5 per minute per address, 8 a day, 2 in flight, 2 strikes -> 5 minute cool-down
        fairUse = new DemoStudioFairUse(3, 5, 8, 2, 2, Duration.ofMinutes(10), Duration.ofMinutes(5), now::get);
    }

    private static DemoSession session(String ip) {
        DemoSession s = mock(DemoSession.class);
        when(s.getId()).thenReturn(UUID.randomUUID());
        when(s.getCreatedIp()).thenReturn(ip);
        return s;
    }

    @Test
    void aSandboxIsThrottledByBurstNotByLifetime() {
        DemoSession s = session("1.1.1.1");
        for (int i = 0; i < 3; i++) {
            fairUse.admit(s, "mission").release();
        }
        DemoStudioService.StudioException ex = assertThrows(DemoStudioService.StudioException.class, () -> fairUse.admit(s, "mission"));
        assertEquals("rate_limited", ex.code());
        assertEquals(429, ex.status());
        now.addAndGet(61_000); // the window slides; nothing was spent for good
        fairUse.admit(s, "mission").release();
    }

    @Test
    void runsInFlightAreCapped() {
        DemoSession s = session("1.1.1.1");
        DemoStudioFairUse.Permit a = fairUse.admit(s, "mission");
        fairUse.admit(s, "mission");
        assertThrows(DemoStudioService.StudioException.class, () -> fairUse.admit(s, "mission"));
        a.release();
        a.release(); // idempotent - a stream's doFinally may fire twice
        fairUse.admit(s, "mission");
    }

    @Test
    void oneAddressCannotMultiplyItselfWithNewSandboxes() {
        for (int i = 0; i < 5; i++) {
            fairUse.admit(session("2.2.2.2"), "mission").release();
        }
        assertThrows(DemoStudioService.StudioException.class, () -> fairUse.admit(session("2.2.2.2"), "mission"));
        fairUse.admit(session("3.3.3.3"), "mission").release(); // someone else on another network is unaffected
    }

    @Test
    void dailyCeilingPerAddressResetsGradually() {
        for (int i = 0; i < 8; i++) {
            fairUse.admit(session("4.4.4.4"), "mission").release();
            now.addAndGet(61_000);
        }
        assertThrows(DemoStudioService.StudioException.class, () -> fairUse.admit(session("4.4.4.4"), "mission"));
        now.addAndGet(Duration.ofDays(1).toMillis());
        fairUse.admit(session("4.4.4.4"), "mission").release();
    }

    @Test
    void repeatedGuardrailHitsCoolTheSandboxDown() {
        DemoSession s = session("5.5.5.5");
        fairUse.strike(s, "guardrail");
        assertEquals(1, fairUse.view(s).strikesLeft());
        fairUse.strike(s, "guardrail");
        DemoStudioService.StudioException ex = assertThrows(DemoStudioService.StudioException.class, () -> fairUse.admit(s, "mission"));
        assertEquals("cooling_down", ex.code());
        assertTrue(fairUse.view(s).coolingSeconds() > 0);
        now.addAndGet(Duration.ofMinutes(5).toMillis() + 1000);
        fairUse.admit(s, "mission").release();
    }
}
