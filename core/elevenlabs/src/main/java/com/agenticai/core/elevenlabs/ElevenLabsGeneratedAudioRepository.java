package com.agenticai.core.elevenlabs;

import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ElevenLabsGeneratedAudioRepository extends JpaRepository<ElevenLabsGeneratedAudio, UUID> {

    /** The tenant's own generated-audio rows double as the usage ledger for {@link VoiceQuotaService} - no separate counter table needed, same reasoning {@code VideoGenerationJobRepository#countByCreatedAtAfterAndStatusNot} already documents. */
    long countByCreatedAtAfter(Instant after);

    /** Backs the Files page's "Generated speech (ElevenLabs)" listing - schema-per-tenant already scopes this to the caller's own rows. */
    List<ElevenLabsGeneratedAudio> findAllByOrderByCreatedAtDesc();

    /** One window of findAllByOrderByCreatedAtDesc(), with id as tiebreaker so offset pages never repeat or skip equal timestamps. */
    List<ElevenLabsGeneratedAudio> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
}
