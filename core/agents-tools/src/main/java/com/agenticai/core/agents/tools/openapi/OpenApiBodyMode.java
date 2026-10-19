package com.agenticai.core.agents.tools.openapi;

/**
 * How an operation's request body maps onto the tool's JSON input - decided once by {@link
 * OpenApiSpecParser}, replayed by {@link OpenApiToolExecutor}.
 */
public enum OpenApiBodyMode {
    /** The operation has no request body. */
    NONE,
    /**
     * The body schema is an object whose properties became top-level input fields (no name clashed
     * with a path/query/header parameter). At call time every input field NOT claimed by a
     * parameter is the body.
     */
    FLATTENED,
    /**
     * The body travels under a single {@code body} input field - used when the body is not an
     * object (array, string, ...) or when flattening would collide with a parameter name.
     */
    WRAPPED
}
