package com.agenticai.core.devices;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MobileDeviceRepository extends JpaRepository<MobileDevice, UUID> {

    Optional<MobileDevice> findByDeviceToken(String deviceToken);

    List<MobileDevice> findByOrderByCreatedAtDesc();
}
