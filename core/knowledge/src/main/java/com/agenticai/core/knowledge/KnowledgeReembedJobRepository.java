package com.agenticai.core.knowledge;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeReembedJobRepository extends JpaRepository<KnowledgeReembedJob, UUID> {

    /** {@code KnowledgeReembedJobRunner}'s poll query - QUEUED plus any EMBEDDING/SWEEPING job to resume. */
    List<KnowledgeReembedJob> findByStatusInOrderByCreatedAtAsc(Collection<KnowledgeReembedJobStatus> statuses);

    Optional<KnowledgeReembedJob> findFirstByCollectionIdAndStatusIn(UUID collectionId, Collection<KnowledgeReembedJobStatus> statuses);

    Optional<KnowledgeReembedJob> findFirstByCollectionIdOrderByCreatedAtDesc(UUID collectionId);
}
