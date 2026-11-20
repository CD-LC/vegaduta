package com.agenticai.core.documentgen.model;

/**
 * What the download endpoint needs from a job beyond {@link DocumentGenerationJobResponse}: the
 * status (to tell "not ready" from "ready"), storage key, MIME type and download filename.
 */
public record DocumentDownloadInfo(
        DocumentGenerationStatus status, String storageKey, String mimeType, String filename) {}
