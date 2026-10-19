package com.agenticai.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** SE-80: the 401 entry point carries a problem+json body, not an empty one. */
class SecurityConfigUnauthorizedProblemTest {

    @Test
    void writesProblemJsonBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workflows");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(401);

        SecurityConfig.writeUnauthorizedProblem(request, response);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/problem+json"));
        String body = response.getContentAsString();
        assertTrue(body.contains("\"status\":401"), body);
        assertTrue(body.contains("\"title\":\"Unauthorized\""), body);
        assertTrue(body.contains("\"instance\":\"/api/workflows\""), body);
    }
}
