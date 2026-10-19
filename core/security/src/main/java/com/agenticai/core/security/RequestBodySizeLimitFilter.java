package com.agenticai.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the size of every non-multipart request body before anything parses it
 * (bug-bounty report 2026-10: "application-level DoS on signup").
 *
 * <p>Tomcat's {@code maxPostSize} only covers form-urlencoded bodies, and
 * multipart is bounded by {@code spring.servlet.multipart.*}; a JSON
 * {@code @RequestBody} had no limit at all, so a single request could make
 * Jackson buffer gigabytes - on any endpoint, authenticated or not, since the
 * body is read before {@code @Valid} or any rate limiter runs. Per-field
 * {@code @Size} bounds still matter (they stop expensive work like password
 * hashing); this is the backstop for every field nobody remembered to bound.
 *
 * <p>Two tiers: a global cap sized above the largest legitimate JSON body
 * (chat inline attachments: 10 MB decoded = ~14 MB base64), and a small cap
 * for public form endpoints whose real bodies are a few hundred bytes.
 * A declared {@code Content-Length} over the cap is refused with 413 up front;
 * a chunked body is counted as it streams and fails the read once it crosses
 * the cap (surfacing as a 400 from the message converter).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;
    private final long smallBodyMaxBytes;
    private final List<String> smallBodyPaths;

    public RequestBodySizeLimitFilter(
        @Value("${app.http.max-body-bytes:16777216}") long maxBodyBytes,
        @Value("${app.http.small-body-max-bytes:65536}") long smallBodyMaxBytes,
        @Value("${app.http.small-body-paths:/api/signup,/api/devices/claim,/api/public/landing-event}")
        List<String> smallBodyPaths
    ) {
        this.maxBodyBytes = maxBodyBytes;
        this.smallBodyMaxBytes = smallBodyMaxBytes;
        this.smallBodyPaths = smallBodyPaths;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        // Multipart has its own limits (spring.servlet.multipart.max-request-size).
        return contentType != null && contentType.toLowerCase().startsWith("multipart/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        long limit = limitFor(request.getRequestURI());
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.setHeader("Connection", "close");
            response.getWriter().write("{\"status\":413,\"error\":\"Payload Too Large\",\"message\":\"Request body exceeds "
                + limit + " bytes\"}");
            return;
        }
        chain.doFilter(new LimitedBodyRequest(request, limit), response);
    }

    long limitFor(String path) {
        if (path != null) {
            for (String prefix : smallBodyPaths) {
                String p = prefix.trim();
                if (!p.isEmpty() && (path.equals(p) || path.startsWith(p + "/"))) {
                    return smallBodyMaxBytes;
                }
            }
        }
        return maxBodyBytes;
    }

    /** Thrown mid-read when a body without (or with a lying) Content-Length crosses the cap. */
    public static final class BodyTooLargeException extends IOException {
        BodyTooLargeException(long limit) {
            super("Request body exceeds " + limit + " bytes");
        }
    }

    static final class LimitedBodyRequest extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream stream;

        LimitedBodyRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingInputStream(super.getInputStream(), limit);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String enc = getCharacterEncoding();
            Charset charset = enc != null ? Charset.forName(enc) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    static final class CountingInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        CountingInputStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        private void add(long n) throws IOException {
            if (n > 0) {
                count += n;
                if (count > limit) {
                    throw new BodyTooLargeException(limit);
                }
            }
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = delegate.read(buf, off, len);
            add(n);
            return n;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
