package com.agenticai.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

/** Preflight for the read-only view-as header from the web app's origin. */
class WebConfigCorsTest {

    private static final class ExposedRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configs() {
            return getCorsConfigurations();
        }
    }

    private static CorsConfiguration apiConfig() {
        ExposedRegistry registry = new ExposedRegistry();
        new WebConfig("https://app.vegaduta.ai,https://admin.vegaduta.ai")
                .addCorsMappings(registry);
        CorsConfiguration config = registry.configs().get("/api/**");
        assertNotNull(config);
        return config;
    }

    @Test
    void preflightAllowsXViewAsFromTheAppOrigin() throws Exception {
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/agents");
        preflight.addHeader("Origin", "https://app.vegaduta.ai");
        preflight.addHeader("Access-Control-Request-Method", "GET");
        preflight.addHeader("Access-Control-Request-Headers", "authorization, x-view-as");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean ok = new DefaultCorsProcessor().processRequest(apiConfig(), preflight, response);

        assertTrue(ok);
        assertEquals(200, response.getStatus());
        assertEquals("https://app.vegaduta.ai", response.getHeader("Access-Control-Allow-Origin"));
        assertTrue(
                response.getHeader("Access-Control-Allow-Headers")
                        .toLowerCase()
                        .contains("x-view-as"));
    }

    @Test
    void preflightFromAnUnlistedOriginIsRejected() throws Exception {
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/agents");
        preflight.addHeader("Origin", "https://evil.example");
        preflight.addHeader("Access-Control-Request-Method", "GET");
        preflight.addHeader("Access-Control-Request-Headers", "x-view-as");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new DefaultCorsProcessor().processRequest(apiConfig(), preflight, response);

        assertEquals(403, response.getStatus());
    }
}
