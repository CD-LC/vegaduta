package com.agenticai.core.agents.branching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

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
}
