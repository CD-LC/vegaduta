package com.agenticai.core.documentgen.model;

/**
 * Lifecycle of a {@code DocumentGenerationJob}, same shape as {@code
 * VideoGenerationJobStatus}: {@code REQUESTED} (row saved, event published,
 * not yet picked up), {@code PROCESSING} (the async listener is running the
 * generator), {@code COMPLETE} (bytes written to object storage, {@code
 * storageKey} set), {@code FAILED} (generation threw, {@code errorMessage} set).
 */
public enum DocumentGenerationStatus {
    REQUESTED,
    PROCESSING,
    COMPLETE,
    FAILED
}
