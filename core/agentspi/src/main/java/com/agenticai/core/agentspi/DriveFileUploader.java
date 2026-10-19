package com.agenticai.core.agentspi;

/**
 * Creates a text file in the tenant's connected cloud drive - the workflow Google Drive Output
 * node's one operation. Kernel-owned (ADR-0011 rule a); implemented by {@code
 * google.agenttools.GoogleDriveFileUploader}.
 */
public interface DriveFileUploader {

    record UploadedFile(String id, String name) {}

    /**
     * @param folderId optional parent folder; blank means the drive's root
     * @throws NotConnectedException when no drive account is connected
     * @throws UploadFailedException when the drive's API rejects the call
     */
    UploadedFile createTextFile(String name, String content, String folderId);

    class NotConnectedException extends RuntimeException {
        public NotConnectedException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    class UploadFailedException extends RuntimeException {
        public UploadFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
