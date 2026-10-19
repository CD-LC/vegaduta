package com.agenticai.core.devices;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, UUID> {

    Optional<DeviceCommand> findFirstByDeviceIdAndStatusOrderByCreatedAtAsc(UUID deviceId, DeviceCommandStatus status);

    List<DeviceCommand> findByDeviceIdOrderByCreatedAtDesc(UUID deviceId);

    Optional<DeviceCommand> findByIdAndDeviceId(UUID id, UUID deviceId);
}
