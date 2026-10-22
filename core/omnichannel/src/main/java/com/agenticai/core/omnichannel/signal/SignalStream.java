package com.agenticai.core.omnichannel.signal;

import java.time.Duration;
import tools.jackson.databind.JsonNode;

/**
 * An open websocket receive stream from signal-cli-rest-api in json-rpc mode ({@code GET
 * /v1/receive/{number}}). Messages are queued as they arrive; the owner thread drains them with
 * {@link #poll}, so everything that follows runs on the owner's thread with its tenant context.
 */
public interface SignalStream extends AutoCloseable {

    /**
     * The next message, or null if none arrived within {@code timeout}.
     *
     * @throws SignalApiException once the stream is closed and drained
     */
    JsonNode poll(Duration timeout) throws InterruptedException;

    boolean isOpen();

    @Override
    void close();
}
