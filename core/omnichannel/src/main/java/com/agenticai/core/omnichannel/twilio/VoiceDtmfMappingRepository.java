package com.agenticai.core.omnichannel.twilio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceDtmfMappingRepository extends JpaRepository<VoiceDtmfMapping, UUID> {

    List<VoiceDtmfMapping> findAllByOrderByDigitAsc();

    Optional<VoiceDtmfMapping> findByDigit(String digit);

    void deleteByDigit(String digit);
}
