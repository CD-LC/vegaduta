package com.agenticai.core.security;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

class DelegationTokenAuthFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDelegatedAuthenticationIsSavedSoTheAsyncDispatchCanReloadIt() throws Exception {
        Authentication delegated = new TestingAuthenticationToken("user-1", "n/a", List.of());
        DelegatedTokenAuthenticator authenticator = mock(DelegatedTokenAuthenticator.class);
        when(authenticator.authenticate("tok")).thenReturn(Optional.of(delegated));
        ObjectProvider<DelegatedTokenAuthenticator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(authenticator);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents/x/chat");
        request.addHeader("Authorization", "Bearer tok");

        new DelegationTokenAuthFilter(provider)
                .doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        // What SecurityContextHolderFilter does on the ASYNC dispatch of the same request.
        SecurityContext reloaded =
                new RequestAttributeSecurityContextRepository().loadDeferredContext(request).get();
        assertNotNull(reloaded.getAuthentication(), "context was saved on the request");
        assertSame(delegated, reloaded.getAuthentication());
    }
}
