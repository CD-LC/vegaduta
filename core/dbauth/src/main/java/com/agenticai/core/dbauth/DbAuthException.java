package com.agenticai.core.dbauth;

/** A database login token could not be obtained. The message never contains the token. */
public class DbAuthException extends RuntimeException {
    public DbAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
