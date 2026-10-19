package com.agenticai.core.agents.a2a;

/**
 * A failure of an OUTBOUND A2A call. The message is written for the model/person
 * that will read it (it is relayed verbatim by {@link A2aRemoteAgentToolCallback}),
 * so it never carries stack traces, credentials or raw remote payloads.
 */
public class A2aClientException extends RuntimeException {

    public A2aClientException(String message) {
        super(message);
    }

    public A2aClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
