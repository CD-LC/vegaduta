package com.agenticai.core.sarvam;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SarvamCallLogRepository extends JpaRepository<SarvamCallLog, UUID> {

    List<SarvamCallLog> findTop200ByOrderByStartedAtDesc();

    /** Calls of one capability that started after {@code since} - the WhatsApp voice-note daily cap counts these. */
    long countByCapabilityAndStartedAtAfter(SarvamCallCapability capability, java.time.Instant since);
}
