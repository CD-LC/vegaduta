package com.agenticai.core.storage;

import org.jspecify.annotations.Nullable;

/** Wraps any underlying S3-client failure (network, auth, missing bucket, etc.) as an unchecked exception - callers already have their own error-translation layers (e.g. GlobalExceptionHandler) and shouldn't need to know this is backed by the AWS SDK specifically. */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
