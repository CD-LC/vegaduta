package com.agenticai.core.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.devices.dto.IngestAppUsageRequest;
import com.agenticai.core.devices.dto.IngestNotificationsRequest;
import com.agenticai.core.devices.dto.IngestScreenStateRequest;
import com.agenticai.core.devices.dto.IngestSmsRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/**
 * Both halves of {@link DeviceDataIngestionService}: the write side upserts/maps the device's
 * reports and heartbeats the device on every call, and the read side resolves "which device did
 * the agent mean" (explicit id, name match, or most-recently-active) and clamps page sizes.
 */
class DeviceDataIngestionServiceTest {

    private MobileDeviceRepository deviceRepository;
    private DeviceScreenStateRepository screenStateRepository;
    private DeviceNotificationRepository notificationRepository;
    private DeviceAppUsageEventRepository appUsageRepository;
    private DeviceSmsMessageRepository smsRepository;
    private DeviceDataIngestionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        deviceRepository = mock(MobileDeviceRepository.class);
        screenStateRepository = mock(DeviceScreenStateRepository.class);
        notificationRepository = mock(DeviceNotificationRepository.class);
        appUsageRepository = mock(DeviceAppUsageEventRepository.class);
        smsRepository = mock(DeviceSmsMessageRepository.class);
        service =
                new DeviceDataIngestionService(
                        deviceRepository,
                        screenStateRepository,
                        notificationRepository,
                        appUsageRepository,
                        smsRepository);
        when(deviceRepository.save(any(MobileDevice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(screenStateRepository.save(any(DeviceScreenState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(appUsageRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(smsRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static MobileDevice device(String name, Instant lastSeen) {
        return new MobileDevice(name, DevicePlatform.ANDROID, "tok-" + name);
    }

    // ------------------------------------------------------------------ write side

    @Test
    void recordScreenStateCreatesTheSingleRowOnFirstReport() {
        MobileDevice device = device("Pixel", null);
        when(screenStateRepository.findByDeviceId(device.getId())).thenReturn(Optional.empty());
        Instant captured = Instant.now().minusSeconds(5);
        IngestScreenStateRequest request =
                new IngestScreenStateRequest("Login|Sign in", "com.bank", captured);

        DeviceScreenState saved = service.recordScreenState(device, request);

        assertThat(saved.getDeviceId()).isEqualTo(device.getId());
        assertThat(saved.getScreenText()).isEqualTo("Login|Sign in");
        assertThat(saved.getForegroundApp()).isEqualTo("com.bank");
        assertThat(saved.getCapturedAt()).isEqualTo(captured);
        assertThat(saved.getUpdatedAt()).isNotNull();
        // Every ingestion call is also a heartbeat.
        assertThat(device.getLastSeenAt()).isNotNull();
        verify(deviceRepository).save(device);
    }

    @Test
    void recordScreenStateOverwritesTheExistingRowRatherThanAppending() {
        MobileDevice device = device("Pixel", null);
        DeviceScreenState existing =
                new DeviceScreenState(device.getId(), "old text", "com.old", Instant.now());
        when(screenStateRepository.findByDeviceId(device.getId()))
                .thenReturn(Optional.of(existing));

        DeviceScreenState saved =
                service.recordScreenState(
                        device, new IngestScreenStateRequest("new text", "com.new", Instant.now()));

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getScreenText()).isEqualTo("new text");
        assertThat(saved.getForegroundApp()).isEqualTo("com.new");
    }

    @Test
    void recordNotificationsMapsEveryItemToAnAppendOnlyRow() {
        MobileDevice device = device("Pixel", null);
        Instant posted = Instant.now().minusSeconds(60);
        IngestNotificationsRequest request =
                new IngestNotificationsRequest(
                        List.of(
                                new IngestNotificationsRequest.Item(
                                        "com.whatsapp", "Mum", "call me", posted),
                                new IngestNotificationsRequest.Item(
                                        "com.slack", "#alerts", "deploy done", null)));

        List<DeviceNotification> saved = service.recordNotifications(device, request);

        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getDeviceId()).isEqualTo(device.getId());
        assertThat(saved.get(0).getPackageName()).isEqualTo("com.whatsapp");
        assertThat(saved.get(0).getTitle()).isEqualTo("Mum");
        assertThat(saved.get(0).getText()).isEqualTo("call me");
        assertThat(saved.get(0).getPostedAt()).isEqualTo(posted);
        assertThat(saved.get(0).getReceivedAt()).isNotNull();
        assertThat(saved.get(1).getPackageName()).isEqualTo("com.slack");
        assertThat(saved.get(1).getPostedAt()).isNull();
        verify(notificationRepository).saveAll(anyList());
    }

    @Test
    void recordAppUsageEventsMapsEveryItem() {
        MobileDevice device = device("Pixel", null);
        Instant eventTime = Instant.now().minusSeconds(30);
        IngestAppUsageRequest request =
                new IngestAppUsageRequest(
                        List.of(
                                new IngestAppUsageRequest.Item(
                                        "com.maps", DeviceAppUsageEventType.FOREGROUND, eventTime),
                                new IngestAppUsageRequest.Item(
                                        "com.maps", DeviceAppUsageEventType.BACKGROUND, null)));

        List<DeviceAppUsageEvent> saved = service.recordAppUsageEvents(device, request);

        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getDeviceId()).isEqualTo(device.getId());
        assertThat(saved.get(0).getPackageName()).isEqualTo("com.maps");
        assertThat(saved.get(0).getEventType()).isEqualTo(DeviceAppUsageEventType.FOREGROUND);
        assertThat(saved.get(0).getEventTime()).isEqualTo(eventTime);
        assertThat(saved.get(0).getReceivedAt()).isNotNull();
        assertThat(saved.get(1).getEventType()).isEqualTo(DeviceAppUsageEventType.BACKGROUND);
    }

    @Test
    void recordSmsMessagesMapsEveryItem() {
        MobileDevice device = device("Pixel", null);
        Instant smsTime = Instant.now().minusSeconds(90);
        IngestSmsRequest request =
                new IngestSmsRequest(
                        List.of(
                                new IngestSmsRequest.Item(
                                        "+15550100100", "on my way", DeviceSmsDirection.INBOUND, smsTime),
                                new IngestSmsRequest.Item(
                                        "+15550100100", "ok", DeviceSmsDirection.OUTBOUND, null)));

        List<DeviceSmsMessage> saved = service.recordSmsMessages(device, request);

        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getDeviceId()).isEqualTo(device.getId());
        assertThat(saved.get(0).getAddress()).isEqualTo("+15550100100");
        assertThat(saved.get(0).getBody()).isEqualTo("on my way");
        assertThat(saved.get(0).getDirection()).isEqualTo(DeviceSmsDirection.INBOUND);
        assertThat(saved.get(0).getSmsTimestamp()).isEqualTo(smsTime);
        assertThat(saved.get(0).getReceivedAt()).isNotNull();
        assertThat(saved.get(1).getDirection()).isEqualTo(DeviceSmsDirection.OUTBOUND);
    }

    // ------------------------------------------------------------------ read side

    @Test
    void resolveDevicePrefersAnExplicitUuid() {
        MobileDevice device = device("Pixel", null);
        UUID id = UUID.randomUUID();
        when(deviceRepository.findById(id)).thenReturn(Optional.of(device));

        assertThat(service.resolveDevice(id.toString())).containsSame(device);
    }

    @Test
    void resolveDeviceRejectsAUuidThatResolvesToARevokedDevice() {
        MobileDevice revoked = device("Revoked Phone", null);
        revoked.revoke();
        UUID id = UUID.randomUUID();
        when(deviceRepository.findById(id)).thenReturn(Optional.of(revoked));
        when(deviceRepository.findByOrderByCreatedAtDesc()).thenReturn(List.of(revoked));

        // The id resolves but to a revoked row: the name fallback compares the UUID
        // string against device names, so nothing matches and the answer is empty.
        assertThat(service.resolveDevice(id.toString())).isEmpty();
    }

    @Test
    void resolveDeviceMatchesNamesCaseInsensitivelyAndSkipsRevokedRows() {
        MobileDevice revoked = device("My Phone", null);
        revoked.revoke();
        MobileDevice active = device("My Phone", null);
        when(deviceRepository.findByOrderByCreatedAtDesc()).thenReturn(List.of(revoked, active));

        assertThat(service.resolveDevice("  my phone  ")).containsSame(active);
        assertThat(service.resolveDevice("nothing named this")).isEmpty();
    }

    @Test
    void resolveDeviceDefaultsToTheMostRecentlySeenNonRevokedDevice() {
        MobileDevice stale = new MobileDevice("Stale", DevicePlatform.ANDROID, "t1");
        MobileDevice recent = new MobileDevice("Recent", DevicePlatform.IOS, "t2");
        recent.touch();
        MobileDevice revoked = new MobileDevice("Revoked", DevicePlatform.ANDROID, "t3");
        revoked.revoke();
        when(deviceRepository.findByOrderByCreatedAtDesc())
                .thenReturn(List.of(revoked, stale, recent));

        assertThat(service.resolveDevice(null)).containsSame(recent);
        assertThat(service.resolveDevice("   ")).containsSame(recent);
    }

    @Test
    void resolveDeviceWithNoDevicesAtAllIsEmpty() {
        when(deviceRepository.findByOrderByCreatedAtDesc()).thenReturn(List.of());

        assertThat(service.resolveDevice(null)).isEmpty();
    }

    @Test
    void recentQueriesClampThePageSizeBetweenOneAndOneHundred() {
        UUID deviceId = UUID.randomUUID();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(notificationRepository.findByDeviceIdOrderByReceivedAtDesc(any(), any()))
                .thenReturn(List.of());
        when(appUsageRepository.findByDeviceIdOrderByReceivedAtDesc(any(), any()))
                .thenReturn(List.of());
        when(smsRepository.findByDeviceIdOrderByReceivedAtDesc(any(), any()))
                .thenReturn(List.of());

        service.recentNotifications(deviceId, null);
        verify(notificationRepository).findByDeviceIdOrderByReceivedAtDesc(any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);

        service.recentAppUsage(deviceId, 500);
        verify(appUsageRepository).findByDeviceIdOrderByReceivedAtDesc(any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);

        service.recentSms(deviceId, 0);
        verify(smsRepository).findByDeviceIdOrderByReceivedAtDesc(any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void latestScreenStatePassesThrough() {
        UUID deviceId = UUID.randomUUID();
        DeviceScreenState state = new DeviceScreenState(deviceId, "text", "app", Instant.now());
        when(screenStateRepository.findByDeviceId(deviceId)).thenReturn(Optional.of(state));

        assertThat(service.latestScreenState(deviceId)).containsSame(state);
    }
}
