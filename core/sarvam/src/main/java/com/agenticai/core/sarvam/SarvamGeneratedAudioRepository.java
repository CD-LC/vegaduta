package com.agenticai.core.sarvam;

import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SarvamGeneratedAudioRepository extends JpaRepository<SarvamGeneratedAudio, UUID> {

    /**
     * Count of audio rows generated after {@code since} in the CURRENT tenant's
     * schema (schema-per-tenant makes this naturally tenant-scoped, same as
     * every other repo call here). Backs {@code AudioGenerationQuotaService}'s
     * per-tenant daily/monthly cap on platform-subsidised voice generation.
     */
    long countByCreatedAtAfter(Instant since);

    /** Backs the Files page's "Generated speech (Sarvam)" listing - schema-per-tenant already scopes this to the caller's own rows. */
    List<SarvamGeneratedAudio> findAllByOrderByCreatedAtDesc();

    /** One window of findAllByOrderByCreatedAtDesc(), with id as tiebreaker so offset pages never repeat or skip equal timestamps. */
    List<SarvamGeneratedAudio> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
}
