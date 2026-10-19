package com.agenticai.core.knowledge;

import java.util.UUID;

public class KnowledgeIngestionJobNotFoundException extends RuntimeException {

    public KnowledgeIngestionJobNotFoundException(UUID jobId) {
        super("No knowledge ingestion job found with id " + jobId);
    }
}
