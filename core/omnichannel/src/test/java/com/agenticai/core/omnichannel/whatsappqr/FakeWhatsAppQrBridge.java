package com.agenticai.core.omnichannel.whatsappqr;

import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for {@code services/whatsapp-qr} on the JDK's HttpServer: the same JSON endpoints the
 * client calls, driven by test-controlled state. Session state lives in {@link #sessions} so a
 * test can move a session through QR_PENDING -> CONNECTED -> LOGGED_OUT by hand.
 */
final class FakeWhatsAppQrBridge implements AutoCloseable {

    static final class Session {
        volatile String status = "QR_PENDING";
        volatile String qr = "2@test-qr-payload";
        volatile String phoneNumber = null;
        volatile String events = "[]";
    }

    final HttpServer server;
    final Map<String, Session> sessions = new ConcurrentHashMap<>();
    final List<String> requestLines = new CopyOnWriteArrayList<>();
    final List<String> sentBodies = new CopyOnWriteArrayList<>();
    final List<String> authHeaders = new CopyOnWriteArrayList<>();
    /** When set, requests without {@code Authorization: Bearer <secret>} get 401. */
    volatile String requiredSecret;

    FakeWhatsAppQrBridge() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    /** A guard that lets the client reach this fake the way an operator allow-list entry would. */
    SsrfGuard guard() {
        return SsrfGuard.of(Set.of("127.0.0.1:" + port()), InetAddress::getAllByName);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String requestLine = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                + (exchange.getRequestURI().getRawQuery() == null
                        ? ""
                        : "?" + exchange.getRequestURI().getRawQuery());
        requestLines.add(requestLine);
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        authHeaders.add(auth == null ? "" : auth);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");

        if ("/health".equals(path)) {
            respond(exchange, 200, "{\"status\":\"ok\"}");
            return;
        }
        if (requiredSecret != null && !("Bearer " + requiredSecret).equals(auth)) {
            respond(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }

        if ("POST".equals(exchange.getRequestMethod()) && "/sessions".equals(path)) {
            String key = body.replaceAll(".*\"sessionKey\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            Session session = sessions.computeIfAbsent(key, k -> new Session());
            respond(exchange, 200, view(session));
            return;
        }
        if (parts.length >= 3 && "sessions".equals(parts[1])) {
            String key = parts[2];
            Session session = sessions.get(key);
            if (session == null) {
                respond(exchange, 404, "{\"error\":\"unknown session\"}");
                return;
            }
            if (parts.length == 3 && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, view(session));
                return;
            }
            if (parts.length == 3 && "DELETE".equals(exchange.getRequestMethod())) {
                sessions.remove(key);
                respond(exchange, 204, "");
                return;
            }
            if (parts.length == 4 && "events".equals(parts[3])) {
                respond(exchange, 200, "{\"status\":\"" + session.status + "\",\"events\":" + session.events + "}");
                return;
            }
            if (parts.length == 4 && "send".equals(parts[3])) {
                if (!"CONNECTED".equals(session.status)) {
                    respond(exchange, 409, "{\"error\":\"session is not connected\"}");
                    return;
                }
                sentBodies.add(body);
                respond(exchange, 200, "{\"ok\":true}");
                return;
            }
        }
        respond(exchange, 404, "{\"error\":\"not found\"}");
    }

    private static String view(Session session) {
        return "{\"status\":\"" + session.status + "\",\"qr\":"
                + (session.qr == null ? "null" : "\"" + session.qr + "\"")
                + ",\"phoneNumber\":"
                + (session.phoneNumber == null ? "null" : "\"" + session.phoneNumber + "\"")
                + "}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
