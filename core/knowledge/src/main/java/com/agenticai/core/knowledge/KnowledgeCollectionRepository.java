package com.agenticai.core.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeCollectionRepository extends JpaRepository<KnowledgeCollection, UUID> {

    Optional<KnowledgeCollection> findByIsDefaultTrue();

    List<KnowledgeCollection> findByParentCollectionId(UUID parentCollectionId);
}
