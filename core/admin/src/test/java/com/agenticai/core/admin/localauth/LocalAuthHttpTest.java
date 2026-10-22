package com.agenticai.core.admin.localauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.agenticai.core.security.SecurityConfig;
import com.agenticai.core.security.localauth.LocalAuthSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Local mode end to end through the REAL filter chains (no mocks of security): sign in over HTTP,
 * then use the access token against endpoints guarded by the unchanged main-chain rules. This is
 * the proof that "every existing endpoint works unchanged" for tokens core issued itself.
 */
class LocalAuthHttpTest {

    private static final String EMAIL = "owner@example.test";
    private static final String PASSWORD = "correct horse battery staple";
    private static final ObjectMapper JSON = new ObjectMapper();

    @EnableWebMvc
    @Configuration(proxyBeanMethods = false)
    static class Web {
        @Bean
        InMemoryLocalAccountStore store() {
            return new InMemoryLocalAccountStore();
        }

        @Bean
        LocalPasswords localPasswords() {
            return new LocalPasswords();
        }

        @Bean
        LocalSessionService localSessionService(
                LocalAccountStore store,
                com.agenticai.core.security.localauth.LocalJwtService jwt,
                com.agenticai.core.security.localauth.LocalAuthSettings settings) {
            return new LocalSessionService(store, jwt, settings);
        }

        @Bean
        InMemoryLocalCredentialStore credentialStore() {
            return new InMemoryLocalCredentialStore();
        }

        @Bean
        LocalPins localPins(LocalPasswords passwords) {
            return new LocalPins(passwords);
        }

        @Bean
        LocalDevices localDevices(LocalCredentialStore credentials, LocalPins pins) {
            return new LocalDevices(credentials, pins);
        }

        @Bean
        LocalRecoveryCodes localRecoveryCodes(LocalCredentialStore credentials) {
            return new LocalRecoveryCodes(credentials);
        }

        @Bean
        LocalPasskeyService localPasskeyService(LocalCredentialStore credentials) {
            return new LocalPasskeyService(credentials, "");
        }

        @Bean
        LocalPasswordVerifier localPasswordVerifier(
                LocalAccountStore store, LocalPasswords passwords) {
            return new LocalPasswordVerifier(store, passwords);
        }

        @Bean
        LocalPinVerifier localPinVerifier(
                LocalCredentialStore credentials, LocalAccountStore store, LocalPins pins) {
            return new LocalPinVerifier(credentials, store, pins);
        }

        @Bean
        LocalPasskeyVerifier localPasskeyVerifier(
                LocalPasskeyService passkeys, LocalAccountStore store) {
            return new LocalPasskeyVerifier(passkeys, store);
        }

        @Bean
        LocalRecoveryVerifier localRecoveryVerifier(
                LocalRecoveryCodes codes, LocalAccountStore store) {
            return new LocalRecoveryVerifier(codes, store);
        }

        @Bean
        LocalAuthService localAuthService(
                LocalAccountStore store,
                LocalSessionService sessions,
                LocalPasswords passwords,
                java.util.List<com.agenticai.core.security.localauth.LocalCredentialVerifier>
                        verifiers,
                com.agenticai.core.security.localauth.LocalAuthSettings settings,
                LocalDevices devices,
                LocalRecoveryCodes codes) {
            return new LocalAuthService(
                    store,
                    sessions,
                    passwords,
                    new com.agenticai.core.security.localauth.LoginThrottle(
                            java.time.Clock.systemUTC()),
                    settings,
                    verifiers,
                    devices,
                    codes);
        }

        @Bean
        LocalAuthController localAuthController(
                LocalAuthService auth, LocalSessionService sessions) {
            return new LocalAuthController(auth, sessions);
        }

        @Bean
        LocalUnlockService localUnlockService(
                LocalAccountStore accounts,
                LocalCredentialStore credentials,
                LocalPasskeyService passkeys,
                LocalDevices devices,
                LocalRecoveryCodes codes,
                LocalAuthService auth) {
            return new LocalUnlockService(accounts, credentials, passkeys, devices, codes, auth);
        }

        @Bean
        LocalUnlockController localUnlockController(
                LocalAuthService auth, LocalUnlockService unlock) {
            return new LocalUnlockController(auth, unlock);
        }

        /** Stand-in for the app's role-to-permission mapping: tenant-admin may manage users. */
        @Bean
        com.agenticai.core.security.PermissionAuthoritySource permissions() {
            return (tenantId, subject, roles) ->
                    roles.contains("tenant-admin") ? Set.of("MANAGE_USERS") : Set.of();
        }

        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    @RestController
    static class Probe {
        @GetMapping("/api/probe/any")
        String any() {
            return "ok";
        }

        @GetMapping("/api/admin/users")
        String adminUsers() {
            return "users";
        }

        @GetMapping("/api/platform/probe")
        String platform() {
            return "platform";
        }
    }

    @TempDir Path dir;
    AnnotationConfigWebApplicationContext ctx;
    MockMvc mvc;
    InMemoryLocalAccountStore store;

    @BeforeEach
    void boot() {
        ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "t",
                                Map.of(
                                        "app.auth.mode",
                                        "local",
                                        "app.auth.local.data-dir",
                                        dir.toString())));
        ctx.register(Web.class, SecurityConfig.class, LocalAuthSecurityConfig.class);
        ctx.refresh();
        store = ctx.getBean(InMemoryLocalAccountStore.class);
        LocalPasswords passwords = ctx.getBean(LocalPasswords.class);
        store.insert(InMemoryLocalAccountStore.account(EMAIL, PASSWORD, passwords));
        mvc =
                MockMvcBuilders.webAppContextSetup(ctx)
                        .addFilters(ctx.getBean("springSecurityFilterChain", Filter.class))
                        .build();
    }

    @AfterEach
    void close() {
        ctx.close();
    }

    private JsonNode login(String password, int expectedStatus) throws Exception {
        MvcResult result =
                mvc.perform(
                                post("/api/auth/local/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                JSON.writeValueAsString(
                                                        Map.of(
                                                                "identifier",
                                                                EMAIL,
                                                                "password",
                                                                password))))
                        .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void theConfigEndpointIsPublicAndSaysLocal() throws Exception {
        MvcResult r = mvc.perform(get("/api/auth/local/config")).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(r.getResponse().getContentAsString()).get("mode").asText())
                .isEqualTo("local");
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void loginIssuesATokenThatOpensTheRestOfTheApi() throws Exception {
        JsonNode session = login(PASSWORD, 200);
        String token = session.get("accessToken").asText();

        assertThat(session.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(session.get("user").get("roles").toString()).contains("tenant-admin");
        assertThat(mvc.perform(get("/api/probe/any")).andReturn().getResponse().getStatus())
                .as("no token")
                .isEqualTo(401);
        assertThat(
                        mvc.perform(
                                        get("/api/probe/any")
                                                .header("Authorization", "Bearer " + token))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
        assertThat(
                        mvc.perform(
                                        get("/api/admin/users")
                                                .header("Authorization", "Bearer " + token))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("tenant-admin reaches the role-gated admin rule")
                .isEqualTo(200);
        assertThat(
                        mvc.perform(
                                        get("/api/platform/probe")
                                                .header("Authorization", "Bearer " + token))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("tenant-admin is not a platform-admin")
                .isEqualTo(403);
    }

    @Test
    void aTokenWithoutTheRoleIsRefusedByTheUnchangedRules() throws Exception {
        LocalAccount member =
                InMemoryLocalAccountStore.account(
                        "member@example.test", PASSWORD, ctx.getBean(LocalPasswords.class));
        store.insert(member.withRoles(Set.of("end-customer")));
        MvcResult r =
                mvc.perform(
                                post("/api/auth/local/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                JSON.writeValueAsString(
                                                        Map.of(
                                                                "identifier",
                                                                "member@example.test",
                                                                "password",
                                                                PASSWORD))))
                        .andReturn();
        String token =
                JSON.readTree(r.getResponse().getContentAsString()).get("accessToken").asText();

        assertThat(
                        mvc.perform(
                                        get("/api/admin/users")
                                                .header("Authorization", "Bearer " + token))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
    }

    @Test
    void aWrongPasswordIsA401WithAStableCodeAndNoToken() throws Exception {
        JsonNode body = login("definitely not the password", 401);

        assertThat(body.get("error").asText()).isEqualTo("invalid_credentials");
        assertThat(body.has("accessToken")).isFalse();
    }

    @Test
    void aTamperedOrForeignBearerIsRefusedOnTheMainChain() throws Exception {
        String token = login(PASSWORD, 200).get("accessToken").asText();
        String[] p = token.split("\\.");
        String tampered = p[0] + "." + p[1] + "x." + p[2];

        assertThat(
                        mvc.perform(
                                        get("/api/probe/any")
                                                .header("Authorization", "Bearer " + tampered))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
        assertThat(
                        mvc.perform(
                                        get("/api/probe/any")
                                                .header(
                                                        "Authorization",
                                                        "Bearer eyJhbGciOiJub25lIn0.e30."))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
    }

    @Test
    void refreshRotatesAndLogoutEndsTheSession() throws Exception {
        String refresh = login(PASSWORD, 200).get("refreshToken").asText();

        MvcResult rotated =
                mvc.perform(
                                post("/api/auth/local/refresh")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                JSON.writeValueAsString(
                                                        Map.of("refreshToken", refresh))))
                        .andReturn();
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        String next =
                JSON.readTree(rotated.getResponse().getContentAsString())
                        .get("refreshToken")
                        .asText();
        assertThat(next).isNotEqualTo(refresh);

        assertThat(
                        mvc.perform(
                                        post("/api/auth/local/logout")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        JSON.writeValueAsString(
                                                                Map.of("refreshToken", next))))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(204);
        assertThat(
                        mvc.perform(
                                        post("/api/auth/local/refresh")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        JSON.writeValueAsString(
                                                                Map.of("refreshToken", next))))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
    }

    @Test
    void changePasswordNeedsAValidBearerButLoginIgnoresAStaleOne() throws Exception {
        String token = login(PASSWORD, 200).get("accessToken").asText();
        String body =
                JSON.writeValueAsString(
                        Map.of(
                                "currentPassword",
                                PASSWORD,
                                "newPassword",
                                "a brand new long passphrase"));

        assertThat(
                        mvc.perform(
                                        post("/api/auth/local/change-password")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(body))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
        MvcResult changed =
                mvc.perform(
                                post("/api/auth/local/change-password")
                                        .header("Authorization", "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andReturn();
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);

        // A stale/expired Authorization header must not break a fresh sign-in.
        MvcResult relogin =
                mvc.perform(
                                post("/api/auth/local/login")
                                        .header("Authorization", "Bearer expired.token.value")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                JSON.writeValueAsString(
                                                        Map.of(
                                                                "identifier",
                                                                EMAIL,
                                                                "password",
                                                                "a brand new long passphrase"))))
                        .andReturn();
        assertThat(relogin.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void validationErrorsOnTheBodyAre400NotAServerError() throws Exception {
        MvcResult r =
                mvc.perform(
                                post("/api/auth/local/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{}"))
                        .andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void publicSignUpIsClosedOnASingleOwnerInstall() throws Exception {
        String body =
                JSON.writeValueAsString(
                        Map.of("orgName", "Intruder", "adminEmail", "x@example.test"));
        String token = login(PASSWORD, 200).get("accessToken").asText();

        assertThat(
                        mvc.perform(
                                        post("/api/signup")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(body))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("anonymous caller")
                .isEqualTo(403);
        assertThat(
                        mvc.perform(
                                        post("/api/signup")
                                                .header("Authorization", "Bearer " + token)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(body))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("even a signed-in caller")
                .isEqualTo(403);
    }
}
