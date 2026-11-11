package com.agenticai.core.agents.a2a;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Function;

/**
 * The parts of an inbound HTTP request the message-security checks need, taken once at the
 * controller entry so the checks themselves stay free of the servlet API (and of any ThreadLocal
 * request holder).
 *
 * @param httpMethod the HTTP method, e.g. {@code POST}
 * @param path the raw request path as the server saw it (context path included, no query)
 * @param header header lookup; returns null when absent
 */
public record A2aInboundMeta(String httpMethod, String path, Function<String, String> header) {

    /** No request information: what a caller that predates message security looks like. */
    public static final A2aInboundMeta NONE = new A2aInboundMeta("POST", "", name -> null);

    public static A2aInboundMeta of(HttpServletRequest request) {
        if (request == null) {
            return NONE;
        }
        return new A2aInboundMeta(request.getMethod(), request.getRequestURI(), request::getHeader);
    }

    String get(String name) {
        String value = header == null ? null : header.apply(name);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
