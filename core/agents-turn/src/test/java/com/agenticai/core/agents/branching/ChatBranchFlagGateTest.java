package com.agenticai.core.agents.branching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

/** With the flag off the branching routes are 404 before any body is bound or validated. */
class ChatBranchFlagGateTest {

    private final MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/agents/a/sessions/s/messages/m/edit");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void flagOffAnswers404() {
        ChatBranchFlagGate gate = new ChatBranchFlagGate(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> gate.preHandle(request, response, new Object()));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void flagOnLetsTheRequestThrough() {
        assertTrue(new ChatBranchFlagGate(true).preHandle(request, response, new Object()));
    }

    /** The thread list reads the same tables, so it is behind the same gate; the discovery call is not. */
    @Test
    void theGateCoversTheBranchingAndThreadRoutesOnly() {
        ExposedRegistry registry = new ExposedRegistry();
        new ChatBranchFlagGate(false).addInterceptors(registry);
        List<MappedInterceptor> mapped = new ArrayList<>();
        for (Object o : registry.interceptors()) {
            mapped.add((MappedInterceptor) o);
        }
        assertEquals(1, mapped.size());

        assertTrue(gated(mapped, "GET", "/api/chat/threads"));
        assertTrue(gated(mapped, "DELETE", "/api/chat/threads/a/s"));
        assertTrue(gated(mapped, "GET", "/api/agents/a/sessions/s/messages"));
        assertTrue(gated(mapped, "PUT", "/api/agents/a/sessions/s/messages/active-leaf"));
        assertFalse(gated(mapped, "GET", "/api/agents/a/chat-branching/capabilities"));
        assertFalse(gated(mapped, "POST", "/api/chat/turns"));
    }

    private static boolean gated(List<MappedInterceptor> mapped, String method, String path) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRequestURI(path);
        ServletRequestPathUtils.parseAndCache(req);
        return mapped.get(0).matches(req);
    }

    /** {@link InterceptorRegistry#getInterceptors()} is protected. */
    private static final class ExposedRegistry extends InterceptorRegistry {
        List<Object> interceptors() {
            return getInterceptors();
        }
    }
}
