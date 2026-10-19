package com.agenticai.core.security;

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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a valid delegation-token {@code Authorization: Bearer} into a {@link
 * DelegationAuthenticationToken} on the security context. Registered BEFORE {@code
 * BearerTokenAuthenticationFilter} and, on a hit, the wrapper strips the header from the downstream
 * view - otherwise the OAuth2 resource-server filter would try the same (Ed25519, non-Keycloak)
 * token as a JWT and 401 a request we already authenticated.
 *
 * <p>Everything here fails closed and costs nothing on the normal path: non-Bearer requests and
 * tokens the {@link DelegatedTokenAuthenticator} doesn't claim fall straight through to the JWT
 * machinery unchanged.
 */
public class DelegationTokenAuthFilter extends OncePerRequestFilter {

    private static final RequestAttributeSecurityContextRepository CONTEXT_REPOSITORY =
            new RequestAttributeSecurityContextRepository();

    private final ObjectProvider<DelegatedTokenAuthenticator> authenticator;

    public DelegationTokenAuthFilter(ObjectProvider<DelegatedTokenAuthenticator> authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            filterChain.doFilter(request, response);
            return;
        }
        DelegatedTokenAuthenticator auth = authenticator.getIfAvailable();
        if (auth == null) {
            filterChain.doFilter(request, response);
            return;
        }
        Optional<Authentication> delegated;
        try {
            delegated = auth.authenticate(header.substring(7).trim());
        } catch (RuntimeException e) {
            delegated = Optional.empty();
        }
        if (delegated.isEmpty()) {
            // Not ours (or failed verification) - the JWT path decides.
            filterChain.doFilter(request, response);
            return;
        }
        SecurityContext context = SecurityContextHolder.getContext();
        context.setAuthentication(delegated.get());
        // Persist it for this request: a streamed response is finished on an ASYNC dispatch, where
        // SecurityContextHolderFilter reloads the context from the request attribute (this filter
        // does not run again). Without the save that dispatch is anonymous and TenantContextFilter
        // sets no TenantContext. The JWT path gets the same from BearerTokenAuthenticationFilter.
        CONTEXT_REPOSITORY.saveContext(context, request, response);
        filterChain.doFilter(new AuthorizationStrippingRequest(request), response);
    }

    /**
     * Hides the Authorization header downstream of this filter. Only used after a successful
     * delegation authentication - a Keycloak JWT never passes through this wrapper, so ordinary
     * requests are untouched.
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
