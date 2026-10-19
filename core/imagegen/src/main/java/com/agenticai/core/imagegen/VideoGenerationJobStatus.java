package com.agenticai.core.imagegen;

/** Mirrors {@code KnowledgeIngestionJobStatus} - the same enqueue/poll shape, applied to a slow video-generation call instead of ingestion. */
public enum VideoGenerationJobStatus {
    QUEUED,
    PROCESSING,
    COMPLETE,
    FAILED
}
