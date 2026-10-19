package com.agenticai.core.knowledge;

/** Knowledge Stage F3c - {@link KnowledgeIngestionJob}'s lifecycle. No retry state (see that class's javadoc). */
public enum KnowledgeIngestionJobStatus {
    QUEUED,
    PROCESSING,
    COMPLETE,
    FAILED
}
