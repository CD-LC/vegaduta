package com.agenticai.core.mcp.oauth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Canned {@link McpOAuthHttp}: responses keyed "METHOD url"; unmapped requests get a 404. Records every request. */
class FakeMcpOAuthHttp implements McpOAuthHttp {

    record Sent(String method, String url, Map<String, String> headers, String body) {
    }

    final Map<String, Response> responses = new HashMap<>();
    final List<Sent> sent = new ArrayList<>();

    FakeMcpOAuthHttp on(String method, String url, int status, String body) {
        return on(method, url, status, Map.of(), body);
    }

    FakeMcpOAuthHttp on(String method, String url, int status, Map<String, String> headers, String body) {
        responses.put(method + " " + url, new Response(status, headers, body));
        return this;
    }

    @Override
    public Response send(String method, String url, Map<String, String> headers, String body) {
        sent.add(new Sent(method, url, headers, body));
        return responses.getOrDefault(method + " " + url, new Response(404, Map.of(), ""));
    }

    long count(String method, String url) {
        return sent.stream().filter(s -> s.method().equals(method) && s.url().equals(url)).count();
    }
}
