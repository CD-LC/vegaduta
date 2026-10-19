package com.agenticai.core.providers.addons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.agenticai.core.security.SecurityConfig;
import jakarta.servlet.Filter;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * The real {@code SecurityConfig} chains plus the add-on host chain: the installer's shared secret
 * (not a JWT) reaches the controller on {@code /api/addons/host/**}, while every person-facing path
 * stays behind the main chain. This is the property that would otherwise be lost to the resource
 * server's "malformed token" 401 (see {@link AddonHostSecurityConfig}).
 */
class AddonHostSecurityChainTest {

    @EnableWebMvc
    @Configuration(proxyBeanMethods = false)
    static class MinimalWeb {}

    @Configuration(proxyBeanMethods = false)
    static class Stubs {
        @org.springframework.context.annotation.Bean
        AddonService addonService() {
            return mock(AddonService.class);
        }

        @org.springframework.context.annotation.Bean
        AddonHostAuth addonHostAuth() {
            return new AddonHostAuth(AddonKit.TOKEN);
        }

        @org.springframework.context.annotation.Bean
        AddonAccess addonAccess() {
            return new AddonAccess(new org.springframework.mock.env.MockEnvironment());
        }
    }

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;

    @BeforeAll
    static void boot() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("t", Map.of(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://keycloak.invalid/realms/test",
                "app.keycloak.jwk-set-uri", "http://keycloak.invalid/realms/test/protocol/openid-connect/certs")));
        context.register(MinimalWeb.class, Stubs.class, SecurityConfig.class, AddonHostSecurityConfig.class, AddonController.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @AfterAll
    static void close() {
        context.close();
    }

    @Test
    void theInstallersSecretReachesTheControllerInsteadOfBeingRejectedAsAMalformedJwt() throws Exception {
        MvcResult wrong = mvc.perform(get("/api/addons/host/wanted").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andReturn();
        assertEquals(401, wrong.getResponse().getStatus());
        assertTrue(wrong.getResponse().getContentAsString().contains("installer token"),
                "the controller answered, not the resource server: " + wrong.getResponse().getContentAsString());
        assertEquals(null, wrong.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE));

        MvcResult right = mvc.perform(get("/api/addons/host/wanted").header(HttpHeaders.AUTHORIZATION, "Bearer " + AddonKit.TOKEN))
                .andReturn();
        // the stub service returns null: what matters is that auth passed and the handler ran
        assertEquals(200, right.getResponse().getStatus());

        MvcResult report = mvc.perform(post("/api/addons/host/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertTrue(report.getResponse().getContentAsString().contains("installer token"));
    }

    @Test
    void thePersonFacingPathsStayOnTheMainChain() throws Exception {
        for (MockMvc request : new MockMvc[] {mvc}) {
            MvcResult list = request.perform(get("/api/addons")).andReturn();
            assertEquals(401, list.getResponse().getStatus());
            assertFalse(list.getResponse().getContentAsString().contains("installer token"),
                    "the main chain refuses, not the installer's handler");
            MvcResult one = request.perform(get("/api/addons/search").header(HttpHeaders.AUTHORIZATION, "Bearer " + AddonKit.TOKEN)).andReturn();
            assertEquals(401, one.getResponse().getStatus(), "the installer's secret is not a person's token");
            MvcResult change = request.perform(post("/api/addons/search/enable").header(HttpHeaders.AUTHORIZATION, "Bearer " + AddonKit.TOKEN)).andReturn();
            assertEquals(401, change.getResponse().getStatus());
        }
    }
}
