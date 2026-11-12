package com.agenticai.core.knowledge;

import com.agenticai.core.sharing.ResourceOwnerLookup;
import com.agenticai.core.sharing.ResourceType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The {@code core/knowledge} adapter for core/sharing's {@link ResourceOwnerLookup} seam, so {@code
 * ShareController} ({@code /api/knowledge/collections/{id}/grants}) can resolve "who owns this
 * collection" without core/sharing depending on this module. {@link Optional#empty()} for both an
 * unknown collection and a legacy one with no owner; either way only an administrator can then
 * manage its grants.
 */
@Component
@Profile("knowledge")
public class KnowledgeCollectionOwnerLookup implements ResourceOwnerLookup {

    private final KnowledgeCollectionRepository repository;

    public KnowledgeCollectionOwnerLookup(KnowledgeCollectionRepository repository) {
        this.repository = repository;
    }

    @Override
    public ResourceType supportedType() {
        return ResourceType.KNOWLEDGE_COLLECTION;
    }

    @Override
    public Optional<String> findCreatedBy(UUID resourceId) {
        return repository.findById(resourceId).map(KnowledgeCollection::getCreatedBy);
    }
}
