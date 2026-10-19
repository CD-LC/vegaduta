package com.agenticai.core.knowledge;

import java.util.UUID;

public class KnowledgeCollectionNotFoundException extends RuntimeException {

    public KnowledgeCollectionNotFoundException(UUID collectionId) {
        super("No knowledge collection found with id " + collectionId);
    }
}
