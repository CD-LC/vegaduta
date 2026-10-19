package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.security.net.SsrfGuard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A stand-in for bbernhard/signal-cli-rest-api on a raw socket, so one fake serves both the REST
 * endpoints and the json-rpc mode receive websocket (which the JDK's own HttpServer cannot
 * upgrade). Only what the client calls is implemented.
 */
final class FakeSignalCli implements AutoCloseable {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    final ServerSocket server;
    /** Reported by /v1/about: "normal", "native" or "json-rpc". */
    volatile String mode = "normal";
    volatile List<String> accounts = List.of("+4915112345678");
    /** When set, every request without {@code Authorization: Bearer <token>} gets 401. */
    volatile String requiredToken;
    volatile int sendStatus = 201;
    /** Each entry is returned (once) as the body of one receive poll. */
    final BlockingQueue<String> receiveBatches = new LinkedBlockingQueue<>();
    /** Each entry is pushed as one websocket text frame. */
    final BlockingQueue<String> wsFrames = new LinkedBlockingQueue<>();
    final List<String> sentBodies = new CopyOnWriteArrayList<>();
    final List<String> typingBodies = new CopyOnWriteArrayList<>();
    final List<String> requestLines = new CopyOnWriteArrayList<>();
    final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    FakeSignalCli() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(this::acceptLoop, "fake-signal-cli");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    /** A guard that lets the client reach this fake the way an operator allow-list entry would. */
    SsrfGuard guard() {
        return SsrfGuard.of(Set.of("127.0.0.1:" + port()), InetAddress::getAllByName);
    }

    /** Drops every open connection, as a restarting signal-cli would. */
    void dropConnections() {
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        dropConnections();
        server.close();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                sockets.add(socket);
                Thread t = new Thread(() -> handle(socket), "fake-signal-cli-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            requestLines.add(requestLine);
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                }
            }
            String auth = headers.get("authorization");
            authHeaders.add(auth == null ? "" : auth);
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            String body = new String(in.readNBytes(length), StandardCharsets.UTF_8);

            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts[1];
            if (requiredToken != null && !("Bearer " + requiredToken).equals(auth)) {
                respond(out, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            if (path.startsWith("/v1/about")) {
                respond(out, 200, "{\"mode\":\"" + mode + "\",\"version\":\"0.13.0\"}");
            } else if (path.startsWith("/v1/accounts")) {
                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < accounts.size(); i++) {
                    json.append(i > 0 ? "," : "").append('"').append(accounts.get(i)).append('"');
                }
                respond(out, 200, json.append(']').toString());
            } else if (path.startsWith("/v1/receive/")) {
                if ("websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                    serveWebSocket(socket, headers.get("sec-websocket-key"));
                } else {
                    String batch = receiveBatches.poll(150, TimeUnit.MILLISECONDS);
                    respond(out, 200, batch == null ? "[]" : batch);
                }
            } else if (path.startsWith("/v2/send") && "POST".equals(method)) {
                sentBodies.add(body);
                respond(
                        out,
                        sendStatus,
                        sendStatus < 300 ? "{\"timestamp\":\"1\"}" : "{\"error\":\"Unregistered user\"}");
            } else if (path.startsWith("/v1/typing-indicator/")) {
                typingBodies.add(body);
                respond(out, 204, "");
            } else {
                respond(out, 404, "{\"error\":\"not found\"}");
            }
        } catch (IOException | InterruptedException e) {
            // connection dropped or test finished
        }
    }

    private void serveWebSocket(Socket socket, String key) throws IOException, InterruptedException {
        OutputStream out = socket.getOutputStream();
        String accept;
        try {
            accept =
                    Base64.getEncoder()
                            .encodeToString(
                                    MessageDigest.getInstance("SHA-1")
                                            .digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        out.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        while (!closed && !socket.isClosed()) {
            String frame = wsFrames.poll(100, TimeUnit.MILLISECONDS);
            if (frame != null) {
                writeTextFrame(out, frame);
            }
        }
    }

    private static void writeTextFrame(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81); // FIN + text, unmasked (server to client)
        if (payload.length < 126) {
            frame.write(payload.length);
        } else if (payload.length < 65_536) {
            frame.write(126);
            frame.write(payload.length >> 8);
            frame.write(payload.length & 0xff);
        } else {
            frame.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame.write((int) ((long) payload.length >> shift) & 0xff);
            }
        }
        frame.write(payload);
        out.write(frame.toByteArray());
        out.flush();
    }

    private static void respond(OutputStream out, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head =
                "HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: "
                        + bytes.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                line.write(b);
            }
        }
        return b == -1 && line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
    }
}
