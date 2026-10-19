package com.agenticai.core.knowledge.connector;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeConnectorRepository extends JpaRepository<KnowledgeConnector, UUID> {

    List<KnowledgeConnector> findByCollectionId(UUID collectionId);
}
