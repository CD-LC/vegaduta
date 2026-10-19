package com.agenticai.core.devices;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceSmsMessageRepository extends JpaRepository<DeviceSmsMessage, UUID> {

    List<DeviceSmsMessage> findByDeviceIdOrderByReceivedAtDesc(UUID deviceId, Pageable pageable);
}
