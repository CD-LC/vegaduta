package com.agenticai.core.sdlc;

/**
 * E2B's create-sandbox call itself failed, so NO sandbox ever existed for this run. Thrown only
 * by {@link E2bCodeExecutionClient} around {@code createSandbox}; any failure after the sandbox
 * was created (runner unreachable, runner error status) stays a plain {@link E2bApiException}.
 * The platform-paid {@code run_code} meter refunds a counted run on this type only - a failure
 * after creation has already cost platform sandbox time and must stay counted.
 */
public class E2bSandboxCreateException extends E2bApiException {

    public E2bSandboxCreateException(E2bApiException cause) {
        super(cause.getUpstreamStatus(), cause.getMessage());
        initCause(cause);
    }
}
