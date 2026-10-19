package com.agenticai.core.platform;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformAlertWebhookEventRepository extends JpaRepository<PlatformAlertWebhookEvent, java.util.UUID> {

    Optional<PlatformAlertWebhookEvent> findByFingerprint(String fingerprint);

    List<PlatformAlertWebhookEvent> findTop200ByOrderByLastSeenAtDesc();
}
