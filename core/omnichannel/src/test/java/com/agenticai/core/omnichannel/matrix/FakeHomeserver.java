package com.agenticai.core.omnichannel.matrix;

import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A tiny in-process Matrix homeserver for tests: records every request and answers from handlers
 * keyed by {@code "METHOD /path"} (decoded path, longest registered prefix wins). The SSRF guard
 * for it is the real {@link SsrfGuard} with the fake's address on the operator allow-list, so the
 * tests exercise the same code path as a homeserver on a LAN.
 */
final class FakeHomeserver implements AutoCloseable {

    record Request(
            String method,
            String path,
            String rawPath,
            String query,
            Map<String, String> headers,
            byte[] body) {
        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    record Response(int status, String contentType, byte[] body) {
        static Response json(int status, String json) {
            return new Response(status, "application/json", json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private final HttpServer server;
    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Request, Response>> handlers = new ConcurrentHashMap<>();

    FakeHomeserver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String raw = exchange.getRequestURI().getRawPath();
                    Request request =
                            new Request(
                                    exchange.getRequestMethod(),
                                    exchange.getRequestURI().getPath(),
                                    raw,
                                    exchange.getRequestURI().getRawQuery(),
                                    firstHeaders(exchange.getRequestHeaders()),
                                    exchange.getRequestBody().readAllBytes());
                    requests.add(request);
                    Response response = handle(request);
                    exchange.getResponseHeaders().add("Content-Type", response.contentType());
                    exchange.sendResponseHeaders(
                            response.status(),
                            response.body().length == 0 ? -1 : response.body().length);
                    if (response.body().length > 0) {
                        exchange.getResponseBody().write(response.body());
                    }
                    exchange.close();
                });
        server.start();
    }

    private static Map<String, String> firstHeaders(com.sun.net.httpserver.Headers headers) {
        Map<String, String> out = new ConcurrentHashMap<>();
        headers.forEach((k, v) -> out.put(k.toLowerCase(), v.get(0)));
        return out;
    }

    private Response handle(Request request) {
        String key = request.method() + " " + request.path();
        String best = null;
        for (String registered : handlers.keySet()) {
            if (key.startsWith(registered)
                    && (best == null || registered.length() > best.length())) {
                best = registered;
            }
        }
        if (best == null) {
            return Response.json(404, "{\"errcode\":\"M_UNRECOGNIZED\",\"error\":\"no handler\"}");
        }
        return handlers.get(best).apply(request);
    }

    FakeHomeserver on(String methodAndPathPrefix, Function<Request, Response> handler) {
        handlers.put(methodAndPathPrefix, handler);
        return this;
    }

    FakeHomeserver on(String methodAndPathPrefix, int status, String json) {
        return on(methodAndPathPrefix, r -> Response.json(status, json));
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The real guard, told that this one private address is the operator's homeserver. */
    SsrfGuard guard() {
        return SsrfGuard.of(
                Set.of("127.0.0.1:" + server.getAddress().getPort()), InetAddress::getAllByName);
    }

    MatrixApiClient client() {
        SsrfGuard guard = guard();
        return new MatrixApiClient(new com.fasterxml.jackson.databind.ObjectMapper(), () -> guard);
    }

    MatrixApiClient.Endpoint endpoint(String token) {
        return new MatrixApiClient.Endpoint(url(), token);
    }

    List<Request> requestsTo(String method, String pathPrefix) {
        return requests.stream()
                .filter(r -> r.method().equals(method) && r.path().startsWith(pathPrefix))
                .toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
