package com.agenticai.core.security.apikey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns {@code Authorization: Bearer vdak_...} (a tenant REST API key) into
 * an {@link ApiKeyAuthenticationToken} on the security context. Registered
 * BEFORE {@code BearerTokenAuthenticationFilter} - mirrors {@code
 * DelegationTokenAuthFilter} exactly, including the downstream
 * header-stripping (otherwise the resource-server filter would try the key
 * as a JWT and 401 a request this filter already authenticated).
 *
 * <p>Fails closed and costs nothing on the normal path: non-Bearer requests
 * and Bearer values without the {@code vdak_} prefix pass through to the JWT
 * machinery untouched; an absent SPI bean (the {@code tenancy} module not
 * on the classpath) also passes through - the {@code vdak_} value then dies
 * as a malformed JWT at the resource-server filter, which is the correct
 * "feature not present" behaviour.
 *
 * <p>An invalid {@code vdak_} key is rejected HERE with a clear 401 rather
 * than falling through to a confusing "malformed JWT" - the prefix told us
 * the caller's intent, and "your API key is wrong" beats "your token is
 * broken" as an error.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** Every tenant API key carries this literal prefix ({@code TenantApiKeyHasher}). */
    public static final String KEY_PREFIX = "vdak_";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final RequestAttributeSecurityContextRepository CONTEXT_REPOSITORY =
            new RequestAttributeSecurityContextRepository();

    private final ObjectProvider<TenantApiKeyAuthenticator> authenticator;

    public ApiKeyAuthFilter(ObjectProvider<TenantApiKeyAuthenticator> authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null
                || !header.regionMatches(true, 0, "Bearer ", 0, 7)
                || !header.regionMatches(true, 7, KEY_PREFIX, 0, KEY_PREFIX.length())) {
            filterChain.doFilter(request, response);
            return;
        }
        TenantApiKeyAuthenticator auth = authenticator.getIfAvailable();
        if (auth == null) {
            filterChain.doFilter(request, response);
            return;
        }
        Optional<ApiKeyAuthenticationToken> token;
        try {
            token = auth.authenticate(header.substring(7).trim());
        } catch (RuntimeException e) {
            log.error("API key authentication errored - rejecting", e);
            token = Optional.empty();
        }
        if (token.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/json");
            response.getWriter()
                .write("{\"error\":\"Invalid, disabled or expired API key.\"}");
            return;
        }
        SecurityContext context = SecurityContextHolder.getContext();
        context.setAuthentication(token.get());
        // Persist for the ASYNC dispatch that finishes a streamed response,
        // where this filter does not run again - same contract
        // DelegationTokenAuthFilter documents.
        CONTEXT_REPOSITORY.saveContext(context, request, response);
        filterChain.doFilter(new AuthorizationStrippingRequest(request), response);
    }

    /**
     * Hides the Authorization header downstream of this filter after a
     * successful key authentication - the resource-server bearer filter must
     * never see the {@code vdak_} value. Same wrapper {@code
     * DelegationTokenAuthFilter} uses.
     */
    private static final class AuthorizationStrippingRequest extends HttpServletRequestWrapper {

        private AuthorizationStrippingRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return "Authorization".equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("Authorization".equalsIgnoreCase(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new HashSet<>(Collections.list(super.getHeaderNames()));
            names.removeIf(n -> "Authorization".equalsIgnoreCase(n));
            return Collections.enumeration(names);
        }

        @Override
        public long getDateHeader(String name) {
            return "Authorization".equalsIgnoreCase(name) ? -1 : super.getDateHeader(name);
        }

        @Override
        public int getIntHeader(String name) {
            return "Authorization".equalsIgnoreCase(name) ? -1 : super.getIntHeader(name);
        }
    }
}
