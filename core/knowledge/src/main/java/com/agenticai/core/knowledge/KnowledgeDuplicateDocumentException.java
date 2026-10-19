package com.agenticai.core.knowledge;

/**
 * Thrown when an upload's content hash matches a non-failed document already in the target
 * collection. Mapped to HTTP 409 with the stable problem type {@code knowledge-duplicate-document}
 * (derived from this class name). The message carries the existing file name only - no document id
 * and no endpoint path - so clients can show it as-is.
 */
public class KnowledgeDuplicateDocumentException extends RuntimeException {

    public KnowledgeDuplicateDocumentException(String existingFilename) {
        super("This file's content is already in this collection as \"" + existingFilename
                + "\". Delete that file first if you want to replace it.");
    }
}
