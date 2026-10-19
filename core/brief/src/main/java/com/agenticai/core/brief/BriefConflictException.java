package com.agenticai.core.brief;

/** The requested change is not allowed in the brief's current state (e.g. editing an applied brief). HTTP 409. */
public class BriefConflictException extends RuntimeException {

    public BriefConflictException(String message) {
        super(message);
    }
}
