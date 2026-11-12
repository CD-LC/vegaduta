package com.agenticai.core.agents.memorystore;

import java.util.Optional;

/**
 * A check on item text before it is stored or approved. The store module may not depend on the
 * knowledge module's {@code MemoryWriteScreen}, so the host contributes one as a bean; every
 * registered screen must pass, in addition to {@link BasicMemoryContentScreen}.
 */
@FunctionalInterface
public interface MemoryContentScreen {

    /** A reason code (letters, digits, underscore) when the text must not be stored, else empty. */
    Optional<String> reject(String text);
}
