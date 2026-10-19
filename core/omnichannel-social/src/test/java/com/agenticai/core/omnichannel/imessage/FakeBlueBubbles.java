package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for a BlueBubbles server on a loopback port: checks the {@code password} query
 * parameter the way the real one does (401 otherwise), records every request, and answers from a
 * table of canned responses keyed {@code "METHOD /path"} (404 for anything else).
 */
final class FakeBlueBubbles implements AutoCloseable {

    record Req(String method, String path, String query, String contentType, byte[] body) {
        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        String password() {
            for (String pair : query == null ? new String[0] : query.split("&")) {
                if (pair.startsWith("password=")) {
                    return URLDecoder.decode(pair.substring("password=".length()), StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }

    record Resp(int status, String json) {}

    static final String PASSWORD = "hunter2-bb-password";

    private final HttpServer server;
    final List<Req> requests = new CopyOnWriteArrayList<>();
    final Map<String, Resp> responses = new ConcurrentHashMap<>();

    FakeBlueBubbles() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    Req req =
                            new Req(
                                    exchange.getRequestMethod(),
                                    exchange.getRequestURI().getPath(),
                                    exchange.getRequestURI().getRawQuery(),
                                    exchange.getRequestHeaders().getFirst("Content-Type"),
                                    exchange.getRequestBody().readAllBytes());
                    requests.add(req);
                    Resp resp;
                    if (!PASSWORD.equals(req.password())) {
                        resp = new Resp(401, "{\"status\":401,\"message\":\"You are not authorized\"}");
                    } else {
                        resp =
                                responses.getOrDefault(
                                        req.method() + " " + req.path(),
                                        new Resp(404, "{\"status\":404,\"message\":\"not found\"}"));
                    }
                    byte[] out = resp.json().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(resp.status(), out.length);
                    exchange.getResponseBody().write(out);
                    exchange.close();
                });
        server.start();
    }

    FakeBlueBubbles on(String methodAndPath, int status, String json) {
        responses.put(methodAndPath, new Resp(status, json));
        return this;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A guard that lets this loopback server through, exactly as an operator allow-list entry would. */
    SsrfGuard guard() {
        return SsrfGuard.of(Set.of("127.0.0.1:" + server.getAddress().getPort()), InetAddress::getAllByName);
    }

    ImessageApiClient client() {
        SsrfGuard guard = guard();
        return new ImessageApiClient(com.agenticai.core.json.JsonMappers.plain(), () -> guard);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
