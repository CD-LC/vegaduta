package com.agenticai.core.devices;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DevicePairingCodeRepository extends JpaRepository<DevicePairingCode, UUID> {

    Optional<DevicePairingCode> findByCodeAndConsumedFalse(String code);
}
