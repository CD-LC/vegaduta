package com.agenticai.core.knowledge;

/** Thrown for a bad .md upload (wrong extension, empty file, unreadable content). */
public class InvalidKnowledgeUploadException extends RuntimeException {

    public InvalidKnowledgeUploadException(String message) {
        super(message);
    }
}
