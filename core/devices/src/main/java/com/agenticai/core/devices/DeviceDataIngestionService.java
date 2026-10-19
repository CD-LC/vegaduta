package com.agenticai.core.devices;

import com.agenticai.core.devices.dto.IngestAppUsageRequest;
import com.agenticai.core.devices.dto.IngestNotificationsRequest;
import com.agenticai.core.devices.dto.IngestScreenStateRequest;
import com.agenticai.core.devices.dto.IngestSmsRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * #157 follow-up ("send all data into agentic platform") - the write side
 * (called from {@link DeviceGatewayController}, device-token-authenticated,
 * one {@code device} already resolved via {@link DeviceAuthResolver}) and
 * the read side (called from the {@code read_device_*} tool callbacks in
 * {@code core/agents}, where a tenant's own {@code TenantContext} is already
 * set by the authenticated chat request - see {@link MobileDeviceRepository},
 * an ordinary tenant-scoped repository, for why no explicit tenant filter is
 * needed on any query here).
 *
 * <p>Every {@code recordX} method also calls {@link MobileDevice#touch()} -
 * data ingestion is itself evidence the device is alive and online, the same
 * signal {@link DeviceCommandService#nextPendingCommand} already updates on
 * every poll.
 */
@Service
public class DeviceDataIngestionService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final MobileDeviceRepository deviceRepository;
    private final DeviceScreenStateRepository screenStateRepository;
    private final DeviceNotificationRepository notificationRepository;
    private final DeviceAppUsageEventRepository appUsageRepository;
    private final DeviceSmsMessageRepository smsRepository;

    public DeviceDataIngestionService(
        MobileDeviceRepository deviceRepository,
        DeviceScreenStateRepository screenStateRepository,
        DeviceNotificationRepository notificationRepository,
        DeviceAppUsageEventRepository appUsageRepository,
        DeviceSmsMessageRepository smsRepository
    ) {
        this.deviceRepository = deviceRepository;
        this.screenStateRepository = screenStateRepository;
        this.notificationRepository = notificationRepository;
        this.appUsageRepository = appUsageRepository;
        this.smsRepository = smsRepository;
    }

    // ---- Write side (device-token authenticated) ----

    @Transactional
    public DeviceScreenState recordScreenState(MobileDevice device, IngestScreenStateRequest request) {
        touch(device);
        DeviceScreenState state = screenStateRepository.findByDeviceId(device.getId())
            .orElseGet(() -> new DeviceScreenState(device.getId(), null, null, null));
        state.update(request.screenText(), request.foregroundApp(), request.capturedAt());
        return screenStateRepository.save(state);
    }

    @Transactional
    public List<DeviceNotification> recordNotifications(MobileDevice device, IngestNotificationsRequest request) {
        touch(device);
        List<DeviceNotification> saved = request.notifications().stream()
            .map(item -> new DeviceNotification(device.getId(), item.packageName(), item.title(), item.text(), item.postedAt()))
            .toList();
        return notificationRepository.saveAll(saved);
    }

    @Transactional
    public List<DeviceAppUsageEvent> recordAppUsageEvents(MobileDevice device, IngestAppUsageRequest request) {
        touch(device);
        List<DeviceAppUsageEvent> saved = request.events().stream()
            .map(item -> new DeviceAppUsageEvent(device.getId(), item.packageName(), item.eventType(), item.eventTime()))
            .toList();
        return appUsageRepository.saveAll(saved);
    }

    @Transactional
    public List<DeviceSmsMessage> recordSmsMessages(MobileDevice device, IngestSmsRequest request) {
        touch(device);
        List<DeviceSmsMessage> saved = request.messages().stream()
            .map(item -> new DeviceSmsMessage(device.getId(), item.address(), item.body(), item.direction(), item.smsTimestamp()))
            .toList();
        return smsRepository.saveAll(saved);
    }

    private void touch(MobileDevice device) {
        device.touch();
        deviceRepository.save(device);
    }

    // ---- Read side (agent tool callbacks) ----

    /**
     * Resolves which paired device a tool call should act on: an explicit
     * {@code deviceIdOrName} (tried first as a UUID, then as a
     * case-insensitive {@code deviceName} match) if given, otherwise the
     * tenant's own most-recently-active non-revoked device - the same
     * "no manual selection needed for the common case" default {@code
     * AgentToolProvider}'s Google/Telegram tools already rely on (there's
     * usually exactly one phone paired per tenant).
     */
    @Transactional(readOnly = true)
    public Optional<MobileDevice> resolveDevice(String deviceIdOrName) {
        if (deviceIdOrName != null && !deviceIdOrName.isBlank()) {
            try {
                UUID id = UUID.fromString(deviceIdOrName.trim());
                Optional<MobileDevice> byId = deviceRepository.findById(id).filter(d -> !d.isRevoked());
                if (byId.isPresent()) {
                    return byId;
                }
            } catch (IllegalArgumentException notAUuid) {
                // fall through to name match below
            }
            return deviceRepository.findByOrderByCreatedAtDesc().stream()
                .filter(d -> !d.isRevoked() && d.getDeviceName().equalsIgnoreCase(deviceIdOrName.trim()))
                .findFirst();
        }
        return deviceRepository.findByOrderByCreatedAtDesc().stream()
            .filter(d -> !d.isRevoked())
            .max(java.util.Comparator.comparing(d -> Optional.ofNullable(d.getLastSeenAt()).orElse(java.time.Instant.EPOCH)));
    }

    @Transactional(readOnly = true)
    public Optional<DeviceScreenState> latestScreenState(UUID deviceId) {
        return screenStateRepository.findByDeviceId(deviceId);
    }

    @Transactional(readOnly = true)
    public List<DeviceNotification> recentNotifications(UUID deviceId, Integer limit) {
        return notificationRepository.findByDeviceIdOrderByReceivedAtDesc(deviceId, PageRequest.of(0, clamp(limit)));
    }

    @Transactional(readOnly = true)
    public List<DeviceAppUsageEvent> recentAppUsage(UUID deviceId, Integer limit) {
        return appUsageRepository.findByDeviceIdOrderByReceivedAtDesc(deviceId, PageRequest.of(0, clamp(limit)));
    }

    @Transactional(readOnly = true)
    public List<DeviceSmsMessage> recentSms(UUID deviceId, Integer limit) {
        return smsRepository.findByDeviceIdOrderByReceivedAtDesc(deviceId, PageRequest.of(0, clamp(limit)));
    }

    private static int clamp(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }
}
