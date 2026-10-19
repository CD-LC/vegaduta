package com.agenticai.core.common.apicontract;

import com.agenticai.core.common.ErrorResponseFactory;
import com.agenticai.core.security.http.ResourceVersionSource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Optimistic concurrency for resources that publish a {@link ResourceVersionSource} (agents and
 * workflows today), with RFC 9110 conditional requests:
 *
 * <ul>
 *   <li>{@code GET /api/agents/{id}} carries {@code ETag: "<version>"}, a strong, opaque tag that
 *       changes on every write to the resource.
 *   <li>A {@code PUT}, {@code PATCH} or {@code DELETE} of the resource, or a {@code PATCH} of one of
 *       its settings ({@code PATCH /api/agents/{id}/prompt}), that sends {@code If-Match} is
 *       refused with 412 {@code precondition-failed} when no listed tag is current, so a stale tab
 *       or a second integration cannot overwrite a change it never saw. {@code If-Match: *} passes.
 * </ul>
 *
 * Requests without {@code If-Match} behave exactly as before. The check runs before the handler, so
 * it narrows the lost-update window to the time between check and write; the entity's
 * {@code @Version} column still turns a truly concurrent write into 409. Unknown ids pass through
 * to the handler, which answers 404 (or 403) as usual. It runs after {@code IdempotencyKeyFilter},
 * so a retried conditional write replays its first answer instead of failing on the version its
 * own first attempt moved.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class ConditionalRequestFilter extends OncePerRequestFilter {

    private final List<ResourceVersionSource> sources;
    private final ErrorResponseFactory errors;

    public ConditionalRequestFilter(
            ObjectProvider<ResourceVersionSource> sources, ErrorResponseFactory errors) {
        this.sources = sources.orderedStream().toList();
        this.errors = errors;
    }

    /** Which resource a request addresses, and what follows its id ("" for the resource itself). */
    record Target(ResourceVersionSource source, UUID id, String rest) {}

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return sources.isEmpty() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Target target = target(request.getRequestURI());
        String method = request.getMethod();
        if (target == null) {
            chain.doFilter(request, response);
            return;
        }
        boolean read = "GET".equals(method) && target.rest().isEmpty();
        String ifMatch = request.getHeader("If-Match");
        boolean conditionalWrite = ifMatch != null && guardsWrite(method, target.rest());
        if (!read && !conditionalWrite) {
            chain.doFilter(request, response);
            return;
        }
        Optional<String> current =
                target.source().lastModified(target.id()).map(ConditionalRequestFilter::etag);
        if (current.isPresent()) {
            response.setHeader("ETag", current.get());
            if (conditionalWrite && !matches(ifMatch, current.get())) {
                ProblemResponses.write(
                        errors,
                        response,
                        HttpStatus.PRECONDITION_FAILED,
                        "The resource changed since the version in If-Match; fetch it again and"
                                + " reapply the change",
                        "precondition-failed",
                        null);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    Target target(String uri) {
        for (ResourceVersionSource s : sources) {
            String prefix = s.collectionPath() + "/";
            if (!uri.startsWith(prefix)) {
                continue;
            }
            String tail = uri.substring(prefix.length());
            int slash = tail.indexOf('/');
            String idPart = slash < 0 ? tail : tail.substring(0, slash);
            try {
                UUID id = UUID.fromString(idPart);
                return new Target(s, id, slash < 0 ? "" : tail.substring(slash));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * For the OpenAPI generator: given a path template and the collection paths of the registered
     * {@link ResourceVersionSource}s, whether the operation carries an {@code ETag} (the resource
     * GET) or honours {@code If-Match} (a guarded write). {@code {var}} stands in for the id.
     */
    public static boolean sendsEtag(String httpMethod, String path, List<String> collections) {
        String rest = restOf(path, collections);
        return "GET".equalsIgnoreCase(httpMethod) && "".equals(rest);
    }

    public static boolean honoursIfMatch(String httpMethod, String path, List<String> collections) {
        String rest = restOf(path, collections);
        return rest != null && guardsWrite(httpMethod.toUpperCase(java.util.Locale.ROOT), rest);
    }

    private static String restOf(String path, List<String> collections) {
        for (String c : collections) {
            String prefix = c + "/{";
            if (path.startsWith(prefix)) {
                int close = path.indexOf('}', prefix.length());
                return close < 0 ? null : path.substring(close + 1);
            }
        }
        return null;
    }

    /** The resource itself, or a single-segment settings PATCH such as /prompt or /model. */
    static boolean guardsWrite(String method, String rest) {
        if (rest.isEmpty()) {
            return "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
        }
        return "PATCH".equals(method) && rest.lastIndexOf('/') == 0 && rest.length() > 1;
    }

    /** Strong, opaque tag from the microsecond timestamp (Postgres keeps microseconds). */
    static String etag(Instant updatedAt) {
        long micros =
                Math.addExact(
                        Math.multiplyExact(updatedAt.getEpochSecond(), 1_000_000L),
                        updatedAt.getNano() / 1_000L);
        return "\"" + Long.toString(micros, 36) + "\"";
    }

    /** RFC 9110 If-Match: "*" or a list of strong tags; weak tags never match. */
    static boolean matches(String ifMatch, String current) {
        for (String raw : ifMatch.split(",")) {
            String tag = raw.strip();
            if ("*".equals(tag) || tag.equals(current)) {
                return true;
            }
        }
        return false;
    }
}
