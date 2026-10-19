package com.agenticai.core.platform.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.platform.TenantDeletionController;
import com.agenticai.core.platform.ops.LangfuseApiClientTest.MutableClock;
import com.agenticai.core.platform.ops.PlatformBroadcastService.AnnouncementUpdate;
import com.agenticai.core.platform.ops.PlatformBroadcastService.MaintenanceUpdate;
import com.agenticai.core.tenancy.MasterKeyInvalidException;
import com.agenticai.core.tenancy.MasterKeyMissingException;
import com.agenticai.core.tenancy.PlatformSettingsService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class PlatformBroadcastServiceTest {

    private final Map<String, String> store = new HashMap<>();
    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final MutableClock clock = new MutableClock();
    private PlatformBroadcastService service;

    @BeforeEach
    void setUp() {
        when(settings.getString(anyString(), any()))
                .thenAnswer(inv -> store.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        doAnswer(
                        inv -> {
                            store.put(inv.getArgument(0), inv.getArgument(1));
                            return null;
                        })
                .when(settings)
                .update(anyString(), anyString(), any());
        service = new PlatformBroadcastService(settings, clock);
    }

    @Test
    void defaultsToOffWhenNothingIsStored() {
        assertFalse(service.maintenance().enabled());
        assertFalse(service.maintenanceActive());
        assertNull(service.publicAnnouncement());
        assertEquals("info", service.announcement().level());
    }

    @Test
    void readsAreCachedForTenSecondsSoTheFilterNeverHitsTheDbPerRequest() {
        for (int i = 0; i < 50; i++) {
            service.maintenanceActive();
        }
        verify(settings, times(1)).getString(eq(PlatformBroadcastService.MAINTENANCE_KEY), any());
        clock.advance(Duration.ofSeconds(11));
        service.maintenanceActive();
        verify(settings, times(2)).getString(eq(PlatformBroadcastService.MAINTENANCE_KEY), any());
    }

    /**
     * The reload is a blocking database read on a virtual thread, on every request. Under a monitor
     * it pins the carrier; a burst then pins them all and the pod stops answering (found on the
     * kind stage with a step to 40 long streams: 35 of 185 requests got no first byte). No monitor
     * on the service may be held during the read.
     */
    @Test
    void theSettingsReadNeverRunsUnderAMonitor() {
        AtomicBoolean heldMonitor = new AtomicBoolean(true);
        when(settings.getString(anyString(), any()))
                .thenAnswer(
                        inv -> {
                            heldMonitor.set(Thread.holdsLock(service));
                            return inv.getArgument(1);
                        });
        service.maintenanceActive();
        assertFalse(
                heldMonitor.get(),
                "the platform_setting read must not run inside synchronized(service)");
    }

    @Test
    void aStaleSnapshotIsServedAtOnceWhileAnotherThreadReloads() throws Exception {
        service.maintenanceActive(); // prime the snapshot
        clock.advance(Duration.ofSeconds(11)); // expired
        CountDownLatch reloading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(settings.getString(anyString(), any()))
                .thenAnswer(
                        inv -> {
                            reloading.countDown();
                            release.await(10, TimeUnit.SECONDS);
                            return inv.getArgument(1);
                        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var loader = pool.submit(() -> service.maintenanceActive());
            assertTrue(reloading.await(5, TimeUnit.SECONDS));
            // The loader is now inside the read. Every other caller must get the stale value
            // immediately.
            for (int i = 0; i < 20; i++) {
                var other = pool.submit(() -> service.maintenanceActive());
                assertFalse(other.get(2, TimeUnit.SECONDS), "a reader queued behind the reload");
            }
            release.countDown();
            assertFalse(loader.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        // One extra read (the reload) on top of the priming read for the maintenance key: callers
        // did not each reload.
        verify(settings, times(2)).getString(eq(PlatformBroadcastService.MAINTENANCE_KEY), any());
    }

    @Test
    void callersWithNoSnapshotWaitForTheFirstLoadAndShareIt() throws Exception {
        CountDownLatch inRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(settings.getString(anyString(), any()))
                .thenAnswer(
                        inv -> {
                            inRead.countDown();
                            release.await(10, TimeUnit.SECONDS);
                            return inv.getArgument(1);
                        });
        var pool = Executors.newFixedThreadPool(4);
        try {
            var first = pool.submit(() -> service.maintenanceActive());
            assertTrue(inRead.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> service.maintenanceActive());
            Thread.sleep(100);
            release.countDown();
            assertFalse(first.get(5, TimeUnit.SECONDS));
            assertFalse(second.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        verify(settings, times(1)).getString(eq(PlatformBroadcastService.MAINTENANCE_KEY), any());
    }

    @Test
    void enablingMaintenanceStampsStartedAtAndKeepsItWhileExtending() {
        var on =
                service.updateMaintenance(
                        new MaintenanceUpdate(true, " Upgrading DB ", "2026-10-09T12:00:00Z"),
                        "ops-1");
        assertTrue(on.enabled());
        assertEquals("Upgrading DB", on.message());
        assertEquals(Instant.parse("2026-10-09T12:00:00Z"), on.endsAt());
        Instant started = on.startedAt();
        assertNotNull(started);
        assertTrue(
                store.get(PlatformBroadcastService.MAINTENANCE_KEY)
                        .contains("\"startedAt\":\"2026-10-09T10:00:00Z\""));

        clock.advance(Duration.ofMinutes(5));
        var extended =
                service.updateMaintenance(
                        new MaintenanceUpdate(true, "Still upgrading", null), "ops-1");
        assertEquals(started, extended.startedAt());

        var off = service.updateMaintenance(new MaintenanceUpdate(false, "", null), "ops-1");
        assertFalse(off.enabled());
        assertNull(off.startedAt());
        assertFalse(service.maintenanceActive());
    }

    @Test
    void maintenanceValidation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateMaintenance(new MaintenanceUpdate(null, "", null), "a"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.updateMaintenance(new MaintenanceUpdate(true, "", "soon"), "a"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.updateMaintenance(
                                new MaintenanceUpdate(true, "x".repeat(1001), null), "a"));
    }

    @Test
    void announcementRequiresHttpsLinkAndKnownLevel() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.updateAnnouncement(
                                new AnnouncementUpdate(
                                        true, "Hi", "info", "http://example.com", null),
                                "a"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.updateAnnouncement(
                                new AnnouncementUpdate(
                                        true, "Hi", "info", "javascript:alert(1)", null),
                                "a"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.updateAnnouncement(
                                new AnnouncementUpdate(true, "Hi", "loud", null, null), "a"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.updateAnnouncement(
                                new AnnouncementUpdate(true, "  ", "info", null, null), "a"));
        verify(settings, never()).update(anyString(), anyString(), any());

        var saved =
                service.updateAnnouncement(
                        new AnnouncementUpdate(
                                true,
                                "New models",
                                "WARNING",
                                "https://status.example.com/x",
                                "2026-10-10T00:00:00Z"),
                        "a");
        assertEquals("warning", saved.level());
        assertEquals("https://status.example.com/x", saved.linkUrl());
    }

    @Test
    void publicAnnouncementIsNullWhenDisabledOrExpired() {
        service.updateAnnouncement(
                new AnnouncementUpdate(true, "Heads up", "critical", null, "2026-10-09T11:00:00Z"),
                "a");
        assertNotNull(service.publicAnnouncement());
        assertEquals("critical", service.publicAnnouncement().level());
        clock.advance(Duration.ofHours(2));
        assertNull(service.publicAnnouncement());

        service.updateAnnouncement(
                new AnnouncementUpdate(false, "Heads up", "info", null, null), "a");
        assertNull(service.publicAnnouncement());
    }

    @Test
    void publicMaintenanceHidesMessageWhileOff() {
        store.put(
                PlatformBroadcastService.MAINTENANCE_KEY,
                "{\"enabled\":false,\"message\":\"old\"}");
        assertEquals("", service.publicMaintenance().message());
    }

    @Test
    void aDatabaseBlipKeepsTheLastKnownState() {
        service.updateMaintenance(new MaintenanceUpdate(true, "m", null), "a");
        assertTrue(service.maintenanceActive());
        when(settings.getString(anyString(), any()))
                .thenThrow(new IllegalStateException("db down"));
        clock.advance(Duration.ofSeconds(11));
        assertTrue(service.maintenanceActive());
    }

    @Test
    void garbageStoredValuesReadAsOff() {
        store.put(PlatformBroadcastService.MAINTENANCE_KEY, "not json");
        store.put(PlatformBroadcastService.ANNOUNCEMENT_KEY, "[1,2]");
        assertFalse(service.maintenance().enabled());
        assertFalse(service.announcement().enabled());
    }

    @Test
    void reservedKeys() {
        assertTrue(PlatformBroadcastService.isReservedKey("platform.ops.maintenance"));
        assertTrue(PlatformBroadcastService.isReservedKey("platform.ops.announcement"));
        assertFalse(PlatformBroadcastService.isReservedKey("platform.signup-trial.enabled"));
    }

    @Test
    void controllerRequiresTheMasterKeyForMaintenanceOnly() {
        PlatformBroadcastController controller = new PlatformBroadcastController(service, "mk");
        MaintenanceUpdate on = new MaintenanceUpdate(true, "m", null);
        assertThrows(MasterKeyMissingException.class, () -> controller.setMaintenance(null, on));
        assertThrows(MasterKeyInvalidException.class, () -> controller.setMaintenance("wrong", on));
        assertThrows(
                MasterKeyMissingException.class,
                () -> new PlatformBroadcastController(service, "").setMaintenance("mk", on));
        assertFalse(service.maintenanceActive());

        assertTrue(controller.setMaintenance("mk", on).enabled());
        assertTrue(
                controller
                        .setAnnouncement(new AnnouncementUpdate(true, "hi", "info", null, null))
                        .enabled());
        // Same static gate as tenant deletion.
        assertThrows(
                MasterKeyInvalidException.class,
                () -> TenantDeletionController.authenticateMasterKey("mk", "x"));
    }
}
