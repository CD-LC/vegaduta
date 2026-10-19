package com.agenticai.core.imagegen;

import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeneratedImageRepository extends JpaRepository<GeneratedImage, UUID> {

    /** The tenant's own generated-image rows double as the usage ledger for {@link ImageGenerationQuotaService} - no separate counter table needed. */
    long countByCreatedAtAfter(Instant after);

    /** Backs the Files page's "Generated images" listing - schema-per-tenant already scopes this to the caller's own rows. */
    List<GeneratedImage> findAllByOrderByCreatedAtDesc();

    /** One window of findAllByOrderByCreatedAtDesc(), with id as tiebreaker so offset pages never repeat or skip equal timestamps. */
    List<GeneratedImage> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
}
