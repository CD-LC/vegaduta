package com.agenticai.core.imagegen;

import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoGenerationJobRepository extends JpaRepository<VideoGenerationJob, UUID> {

    List<VideoGenerationJob> findByStatus(VideoGenerationJobStatus status);

    /** The tenant's own job rows double as the usage ledger for {@link VideoGenerationQuotaService} - no separate counter table needed. */
    long countByCreatedAtAfterAndStatusNot(Instant after, VideoGenerationJobStatus excludedStatus);

    /** Backs the Files page's "Generated video" listing - schema-per-tenant already scopes this to the caller's own rows. */
    List<VideoGenerationJob> findAllByOrderByCreatedAtDesc();

    /** One window of findAllByOrderByCreatedAtDesc(), with id as tiebreaker so offset pages never repeat or skip equal timestamps. */
    List<VideoGenerationJob> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
}
