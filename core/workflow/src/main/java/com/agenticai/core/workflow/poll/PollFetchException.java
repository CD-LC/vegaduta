package com.agenticai.core.workflow.poll;

/**
 * One poll attempt failed (connect refused, non-2xx, body too large, not
 * JSON). Distinguished from "no new items" so the trigger can record ERROR
 * and back off rather than silently swallowing a permanently broken endpoint.
 */
public class PollFetchException extends RuntimeException {

    public PollFetchException(String message) {
        super(message);
    }

    public PollFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
