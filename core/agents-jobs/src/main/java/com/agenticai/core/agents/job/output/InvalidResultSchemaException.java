package com.agenticai.core.agents.job.output;

/**
 * The schema itself is unusable - malformed JSON, not a schema object, larger
 * than the configured ceiling, or reaching out to a remote {@code $ref}. Thrown
 * at CONFIGURATION time (setting an agent's result schema, or submitting a job
 * with a per-job one), never at execution time: a bad schema is the caller's
 * mistake and must be a 400 the moment they make it, not a job that is accepted
 * with a 202 and then dies ten minutes later for a reason that points at the
 * agent rather than at the schema.
 *
 * <p><b>Extends {@link IllegalArgumentException} on purpose</b>, rather than
 * being a standalone {@code RuntimeException} plus a new {@code
 * GlobalExceptionHandler} entry. That handler already maps {@code
 * IllegalArgumentException} to a 400 carrying {@code getMessage()}, and it also
 * declares an {@code @ExceptionHandler(Exception.class)} catch-all that runs
 * BEFORE Spring's own {@code ResponseStatusExceptionResolver} - so a {@code
 * @ResponseStatus}-annotated exception would be swallowed into an opaque 500 AND
 * recorded as a spurious PlatformIssue. Subclassing an already-mapped type gets
 * the right status with no edit to a file this workstream does not own; same
 * reasoning {@code AgentJobNotFoundException} documents for extending {@code
 * NoSuchElementException}.
 *
 * <p>The message is written to be shown verbatim to whoever authored the schema:
 * it names the specific defect, because "invalid schema" sends a tenant admin
 * hunting through a 200-line document with no idea what to look for.
 */
public class InvalidResultSchemaException extends IllegalArgumentException {

    public InvalidResultSchemaException(String message) {
        super(message);
    }

    public InvalidResultSchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
