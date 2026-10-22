package com.agenticai.core.common.systemstatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.platform.ops.PlatformBroadcastService;
import com.agenticai.core.platform.ops.PlatformBroadcastService.PublicAnnouncement;
import com.agenticai.core.platform.ops.PlatformBroadcastService.PublicMaintenance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Instant;

class PublicStatusControllerTest {

    private final HealthContributorRegistry registry = mock(HealthContributorRegistry.class);

    private PublicStatusController controller(PlatformBroadcastService broadcast) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (broadcast != null) {
            beans.addBean("broadcast", broadcast);
        }
        return new PublicStatusController(
                registry,
                false,
                beans.getBeanProvider(KafkaAdmin.class),
                "",
                beans.getBeanProvider(PlatformBroadcastService.class));
    }

    @Test
    void carriesMaintenanceAndAnnouncementAndKeepsTheOriginalFields() {
        PlatformBroadcastService broadcast = mock(PlatformBroadcastService.class);
        Instant ends = Instant.parse("2026-10-09T12:00:00Z");
        when(broadcast.publicMaintenance())
                .thenReturn(new PublicMaintenance(true, "Upgrading", ends));
        when(broadcast.publicAnnouncement())
                .thenReturn(new PublicAnnouncement(true, "New", "info", null, null));

        PublicStatusController.PublicStatusResponse r = controller(broadcast).get();

        assertEquals("UP", r.status());
        assertFalse(r.components().isEmpty());
        assertTrue(r.maintenance().enabled());
        assertEquals(ends, r.maintenance().endsAt());
        assertEquals("New", r.announcement().message());
    }

    @Test
    void isCachedForFifteenSeconds() {
        PlatformBroadcastService broadcast = mock(PlatformBroadcastService.class);
        when(broadcast.publicMaintenance()).thenReturn(new PublicMaintenance(false, "", null));
        PublicStatusController c = controller(broadcast);
        PublicStatusController.PublicStatusResponse first = c.get();
        assertSame(first, c.get());
        verify(broadcast, times(1)).publicMaintenance();
    }

    @Test
    void withoutTheBroadcastServiceMaintenanceIsOffAndNoAnnouncement() {
        PublicStatusController.PublicStatusResponse r = controller(null).get();
        assertFalse(r.maintenance().enabled());
        assertNull(r.announcement());
    }
}
