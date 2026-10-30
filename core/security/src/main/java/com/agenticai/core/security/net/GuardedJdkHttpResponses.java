package com.agenticai.core.security.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.ResponseInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.net.ssl.SSLSession;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.ProtocolVersion;

// Package-private response conversion for GuardedJdkHttpClient - split out to keep the
// client under the file-size cap. Apache -> JDK view only; no logic lives here.

final class ResponseInfoImpl implements ResponseInfo {
    private final int status;
    private final HttpHeaders headers;
    private final HttpClient.Version version;

    ResponseInfoImpl(ClassicHttpResponse response) {
        this.status = response.getCode();
        Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header h : response.getHeaders()) {
            map.computeIfAbsent(h.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(h.getValue());
        }
        this.headers = HttpHeaders.of(map, (k, v) -> true);
        ProtocolVersion pv = response.getVersion();
        this.version =
                pv != null && pv.getMajor() >= 2
                        ? HttpClient.Version.HTTP_2
                        : HttpClient.Version.HTTP_1_1;
    }

    @Override
    public int statusCode() {
        return status;
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @Override
    public HttpClient.Version version() {
        return version;
    }
}

final class HttpResponseImpl<T> implements HttpResponse<T> {
    private final HttpRequest request;
    private final ResponseInfo info;
    private final T body;

    HttpResponseImpl(HttpRequest request, ResponseInfo info, T body) {
        this.request = request;
        this.info = info;
        this.body = body;
    }

    @Override
    public int statusCode() {
        return info.statusCode();
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
        return info.headers();
    }

    @Override
    public T body() {
        return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return Optional.empty();
    }

    @Override
    public URI uri() {
        return request.uri();
    }

    @Override
    public HttpClient.Version version() {
        return info.version();
    }
}
