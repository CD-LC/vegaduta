package com.agenticai.core.agents.demo.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.demo.DemoSession;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The failure modes that would turn the gateways into an outage: shared/internal addresses and leaked permits. */
class DemoStudioFairUseHardeningTest {

    private final AtomicLong now = new AtomicLong(5_000_000);

    private static DemoSession session(String ip) {
        DemoSession s = mock(DemoSession.class);
        when(s.getId()).thenReturn(UUID.randomUUID());
        when(s.getCreatedIp()).thenReturn(ip);
        return s;
    }

    @Test
    void onlyRealPublicAddressesAreBucketedOrShown() {
        assertTrue(DemoStudioFairUse.isPublicAddress("8.8.8.8"));
        assertTrue(DemoStudioFairUse.isPublicAddress("2001:4860:4860::8888"));
        for (String bad : new String[] {
            null, "", "unknown", "localhost", "127.0.0.1", "0:0:0:0:0:0:0:1", "10.0.0.7", "172.18.0.1", "192.168.1.5",
            "169.254.169.254", "100.64.0.9", "fd00::1", "not an ip", "8.8.8.8; drop table"
        }) {
            assertFalse(DemoStudioFairUse.isPublicAddress(bad), "should not be treated as a visitor address: " + bad);
        }
    }

    @Test
    void visitorsBehindOneInternalAddressAreNotLockedOutTogether() {
        // An ingress that hides client addresses would make every visitor "10.0.0.7". A per-address limit would then
        // be one global bucket. It must not be applied - only the per-sandbox limits are.
        DemoStudioFairUse fairUse = new DemoStudioFairUse(3, 2, 2, 2, 3, Duration.ofMinutes(10), Duration.ofMinutes(10), now::get);
        for (int i = 0; i < 6; i++) {
            fairUse.admit(session("10.0.0.7"), "mission").release(); // 6 different sandboxes, same internal address
        }
        DemoSession one = session("10.0.0.7");
        for (int i = 0; i < 3; i++) {
            fairUse.admit(one, "mission").release();
        }
        assertThrows(DemoStudioService.StudioException.class, () -> fairUse.admit(one, "mission")); // per-sandbox still bites
        assertEquals(0, fairUse.view(one).usedToday());
    }

    @Test
    void aLeakedPermitExpiresInsteadOfLockingTheSandboxOut() {
        DemoStudioFairUse fairUse = new DemoStudioFairUse(100, 100, 100, 1, 3, Duration.ofMinutes(10), Duration.ofMinutes(10), now::get);
        DemoSession s = session("8.8.4.4");
        fairUse.admit(s, "mission"); // never released: a stream that was never subscribed
        assertThrows(DemoStudioService.StudioException.class, () -> fairUse.admit(s, "mission"));
        now.addAndGet(Duration.ofMinutes(16).toMillis());
        fairUse.admit(s, "mission").release();
    }
}
