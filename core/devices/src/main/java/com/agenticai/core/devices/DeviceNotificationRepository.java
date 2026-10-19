package com.agenticai.core.devices;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceNotificationRepository extends JpaRepository<DeviceNotification, UUID> {

    List<DeviceNotification> findByDeviceIdOrderByReceivedAtDesc(UUID deviceId, Pageable pageable);
}
