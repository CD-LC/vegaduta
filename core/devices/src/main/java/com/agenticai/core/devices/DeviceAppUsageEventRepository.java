package com.agenticai.core.devices;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceAppUsageEventRepository extends JpaRepository<DeviceAppUsageEvent, UUID> {

    List<DeviceAppUsageEvent> findByDeviceIdOrderByReceivedAtDesc(UUID deviceId, Pageable pageable);
}
