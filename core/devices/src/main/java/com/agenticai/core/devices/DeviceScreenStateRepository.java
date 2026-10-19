package com.agenticai.core.devices;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceScreenStateRepository extends JpaRepository<DeviceScreenState, UUID> {

    Optional<DeviceScreenState> findByDeviceId(UUID deviceId);
}
