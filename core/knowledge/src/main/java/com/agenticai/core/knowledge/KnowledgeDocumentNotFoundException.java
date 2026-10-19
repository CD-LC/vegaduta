package com.agenticai.core.knowledge;

import java.util.UUID;

public class KnowledgeDocumentNotFoundException extends RuntimeException {

    public KnowledgeDocumentNotFoundException(UUID documentId) {
        super("No knowledge document found with id " + documentId);
    }
}
