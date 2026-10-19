package com.agenticai.core.voice.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * java.net.http.WebSocket rejects a send issued while the previous one is
 * still in flight; the queue must never overlap sends and must not stall
 * after a failed one.
 */
class WebSocketSendQueueTest {

    /** Records sends and completes each only when the test says so; fails an overlapping send like the JDK does. */
    static final class ManualSocket implements WebSocket {
        final List<String> sent = new ArrayList<>();
        final List<CompletableFuture<WebSocket>> inFlight = new ArrayList<>();
        int overlaps;

        private CompletableFuture<WebSocket> record(String what) {
            if (!inFlight.isEmpty() && !inFlight.get(inFlight.size() - 1).isDone()) {
                overlaps++;
                return CompletableFuture.failedFuture(new IllegalStateException("send pending"));
            }
            sent.add(what);
            CompletableFuture<WebSocket> f = new CompletableFuture<>();
            inFlight.add(f);
            return f;
        }

        void completeLast() {
            inFlight.get(inFlight.size() - 1).complete(this);
        }

        void failLast() {
            inFlight.get(inFlight.size() - 1).completeExceptionally(new RuntimeException("boom"));
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return record(data.toString());
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return record("binary");
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return record("ping");
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return record("pong");
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return record("close:" + statusCode);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
        }
    }

    @Test
    void sendsNeverOverlap_eachWaitsForThePreviousOne() {
        ManualSocket socket = new ManualSocket();
        WebSocketSendQueue queue = new WebSocketSendQueue(socket, "test");

        queue.sendText("a");
        queue.sendText("b");
        queue.sendClose();
        assertEquals(List.of("a"), socket.sent, "b and close must wait for a");
        assertEquals(3, queue.pending());

        socket.completeLast();
        assertEquals(List.of("a", "b"), socket.sent);
        socket.completeLast();
        assertEquals(List.of("a", "b", "close:" + WebSocket.NORMAL_CLOSURE), socket.sent);
        socket.completeLast();

        assertEquals(0, socket.overlaps);
        assertEquals(0, queue.pending());
    }

    @Test
    void aFailedSend_doesNotStallTheFramesBehindIt() {
        ManualSocket socket = new ManualSocket();
        WebSocketSendQueue queue = new WebSocketSendQueue(socket, "test");

        queue.sendText("a");
        queue.sendText("b");
        socket.failLast();

        assertEquals(List.of("a", "b"), socket.sent);
        socket.completeLast();
        assertEquals(0, queue.pending());
    }

    @Test
    void aStalledSocket_dropsFramesPastTheBound_insteadOfBufferingForever() {
        ManualSocket socket = new ManualSocket();
        WebSocketSendQueue queue = new WebSocketSendQueue(socket, "test");

        for (int i = 0; i < WebSocketSendQueue.MAX_PENDING + 10; i++) {
            queue.sendText("f" + i);
        }
        assertEquals(WebSocketSendQueue.MAX_PENDING, queue.pending());
        assertTrue(socket.sent.size() == 1);
    }
}
