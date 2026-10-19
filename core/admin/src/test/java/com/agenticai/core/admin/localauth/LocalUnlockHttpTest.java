package com.agenticai.core.admin.localauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.agenticai.core.security.SecurityConfig;
import com.agenticai.core.security.localauth.LocalAuthSecurityConfig;
import com.agenticai.core.security.localauth.LocalJwtService;
import com.agenticai.core.security.localauth.LocalJwtService.AccessTokenRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * {@code /api/auth/local/unlock} through the REAL filter chains: statuses, the {@code {error,
 * message}} shape, {@code no-store}, which paths need a bearer / accept a setup token / ignore a
 * stale {@code Authorization} header, and passkey ceremonies driven by a software authenticator.
 */
class LocalUnlockHttpTest {

    private static final String EMAIL = "owner@example.test";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String PIN = "482916";
    private static final String ORIGIN = "https://console.example.test";
    private static final String RP_ID = "console.example.test";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String U = "/api/auth/local/unlock";

    record Resp(int status, JsonNode body, MockHttpServletResponse raw) {
        String text(String field) {
            return body.path(field).asText();
        }
    }

    @TempDir Path dir;
    AnnotationConfigWebApplicationContext ctx;
    MockMvc mvc;
    InMemoryLocalAccountStore accounts;
    InMemoryLocalCredentialStore credentials;
    LocalAccount owner;

    @BeforeEach
    void boot() {
        ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("t", Map.of("app.auth.mode", "local", "app.auth.local.data-dir", dir.toString())));
        ctx.register(LocalAuthHttpTest.Web.class, SecurityConfig.class, LocalAuthSecurityConfig.class);
        ctx.refresh();
        accounts = ctx.getBean(InMemoryLocalAccountStore.class);
        credentials = ctx.getBean(InMemoryLocalCredentialStore.class);
        owner = InMemoryLocalAccountStore.account(EMAIL, PASSWORD, ctx.getBean(LocalPasswords.class));
        accounts.insert(owner);
        mvc = MockMvcBuilders.webAppContextSetup(ctx).addFilters(ctx.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterEach
    void close() {
        ctx.close();
    }

    // ------------------------------------------------------------------ helpers

    private Resp send(MockHttpServletRequestBuilder request, String bearer, Object body, String... headers) throws Exception {
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : JSON.writeValueAsString(body));
        }
        MockHttpServletResponse raw = mvc.perform(request).andReturn().getResponse();
        String text = raw.getContentAsString();
        JsonNode parsed = JSON.nullNode();
        if (!text.isBlank() && (text.startsWith("{") || text.startsWith("["))) {
            parsed = JSON.readTree(text);
        }
        return new Resp(raw.getStatus(), parsed, raw);
    }

    private Resp postJson(String path, String bearer, Object body, String... headers) throws Exception {
        return send(post(path), bearer, body, headers);
    }

    /** A password sign-in: a token whose auth_time is now. */
    private String bearer() throws Exception {
        Resp r = postJson("/api/auth/local/login", null, Map.of("identifier", EMAIL, "password", PASSWORD));
        assertThat(r.status()).isEqualTo(200);
        return r.text("accessToken");
    }

    /** A validly signed token whose sign-in is two hours old. */
    private String staleBearer() {
        LocalJwtService tokens = ctx.getBean(LocalJwtService.class);
        return tokens
                .issue(new AccessTokenRequest(owner.id(), owner.email(), "Ada", "Lovelace", "t_abc", Set.of("tenant-admin"), UUID.randomUUID().toString(), Instant.now().minus(Duration.ofHours(2)), "pwd"))
                .token();
    }

    private static void assertRefusal(Resp r, int status, String code) {
        assertThat(r.status()).isEqualTo(status);
        assertThat(r.text("error")).isEqualTo(code);
        assertThat(r.text("message")).isNotBlank();
        assertThat(r.raw().getHeader("Cache-Control")).contains("no-store");
    }

    private String pendingSetupToken() {
        LocalAccount fresh = InMemoryLocalAccountStore.account("first@example.test", null, ctx.getBean(LocalPasswords.class));
        LocalAccount pending = fresh.withPassword(null, false, true);
        accounts.insert(pending);
        return ctx.getBean(LocalAuthService.class).issueSetupToken(pending);
    }

    // ------------------------------------------------------------------ config and caching

    @Test
    void theConfigListsEveryCredentialTypeInADocumentedOrder() throws Exception {
        Resp r = send(get("/api/auth/local/config"), null, null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("credentialTypes")).extracting(JsonNode::asText).containsExactly("password", "pin", "passkey", "recovery");
    }

    @Test
    void everyResponseIsNoStore() throws Exception {
        String token = bearer();
        List<Resp> responses =
                List.of(
                        send(get(U + "/methods"), token, null),
                        postJson(U + "/login", null, Map.of("type", "pin", "identifier", UUID.randomUUID().toString(), "credentials", Map.of("pin", PIN, "deviceSecret", "x"))),
                        postJson(U + "/passkey/login-options", null, null, "X-Vegaduta-Origin", ORIGIN),
                        postJson(U + "/devices", token, Map.of("pin", PIN, "label", "x")),
                        postJson(U + "/recovery-codes", token, null));

        assertThat(responses).allSatisfy(r -> assertThat(r.raw().getHeader("Cache-Control")).contains("no-store"));
    }

    // ------------------------------------------------------------------ bearer required

    @Test
    void theManagementEndpointsNeedAValidBearer() throws Exception {
        String id = UUID.randomUUID().toString();
        List<Resp> anonymous =
                List.of(
                        send(get(U + "/methods"), null, null),
                        postJson(U + "/devices", null, Map.of("pin", PIN)),
                        send(delete(U + "/devices/" + id), null, null),
                        send(delete(U + "/passkeys/" + id), null, null),
                        postJson(U + "/recovery-codes", null, null));
        assertThat(anonymous).allSatisfy(r -> assertThat(r.status()).isEqualTo(401));

        String tampered = bearer() + "x";
        assertThat(send(get(U + "/methods"), tampered, null).status()).isEqualTo(401);
        assertThat(send(get(U + "/methods"), "eyJhbGciOiJub25lIn0.e30.", null).status()).isEqualTo(401);
    }

    @Test
    void methodsReportsWhatTheAccountHas() throws Exception {
        Resp empty = send(get(U + "/methods"), bearer(), null);

        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.body().get("hasPassword").asBoolean()).isTrue();
        assertThat(empty.body().get("passkeys")).isEmpty();
        assertThat(empty.body().get("devices")).isEmpty();
        assertThat(empty.body().get("recoveryCodesRemaining").asInt()).isZero();
    }

    @Test
    void enrollingADevicedThenSigningInWithItsPinAndSecret() throws Exception {
        String token = bearer();

        Resp enrolled = postJson(U + "/devices", token, Map.of("pin", PIN, "label", "Kitchen laptop"));
        assertThat(enrolled.status()).isEqualTo(200);
        assertThat(enrolled.text("deviceId")).isNotBlank();
        assertThat(enrolled.text("deviceSecret")).hasSize(43);

        Resp methods = send(get(U + "/methods"), token, null);
        assertThat(methods.body().get("devices").get(0).get("label").asText()).isEqualTo("Kitchen laptop");
        assertThat(methods.body().toString()).doesNotContain(enrolled.text("deviceSecret"));

        Resp login = postJson(U + "/login", null, Map.of("type", "pin", "identifier", enrolled.text("deviceId"), "credentials", Map.of("pin", PIN, "deviceSecret", enrolled.text("deviceSecret"))));
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.text("tokenType")).isEqualTo("Bearer");
        assertThat(login.body().get("user").get("email").asText()).isEqualTo(EMAIL);
        assertThat(ctx.getBean(LocalJwtService.class).decoder().decode(login.text("accessToken")).getClaimAsStringList("amr")).containsExactly("pin");
        assertThat(send(get("/api/probe/any"), login.text("accessToken"), null).status()).as("the token opens the rest of the API").isEqualTo(200);
    }

    @Test
    void aWeakPinIsA400WithItsOwnCode() throws Exception {
        assertRefusal(postJson(U + "/devices", bearer(), Map.of("pin", "123456", "label", "x")), 400, "weak_pin");
        assertRefusal(postJson(U + "/devices", bearer(), Map.of("pin", "12ab56", "label", "x")), 400, "weak_pin");
    }

    @Test
    void aStaleSignInIsA403ReauthRequiredForEveryChangeButNotForReading() throws Exception {
        String stale = staleBearer();
        String id = UUID.randomUUID().toString();

        assertRefusal(postJson(U + "/devices", stale, Map.of("pin", PIN, "label", "x")), 403, "reauth_required");
        assertRefusal(postJson(U + "/recovery-codes", stale, null), 403, "reauth_required");
        assertRefusal(send(delete(U + "/devices/" + id), stale, null), 403, "reauth_required");
        assertRefusal(send(delete(U + "/passkeys/" + id), stale, null), 403, "reauth_required");
        assertRefusal(postJson(U + "/passkey/register-options", stale, null, "X-Vegaduta-Origin", ORIGIN), 403, "reauth_required");
        assertThat(send(get(U + "/methods"), stale, null).status()).isEqualTo(200);
    }

    @Test
    void removingDevicesAndPasskeysAnswers204_404_or409() throws Exception {
        String token = bearer();
        Resp device = postJson(U + "/devices", token, Map.of("pin", PIN, "label", "x"));

        assertThat(send(delete(U + "/devices/" + device.text("deviceId")), token, null).status()).isEqualTo(204);
        assertRefusal(send(delete(U + "/devices/" + device.text("deviceId")), token, null), 404, "not_found");
        assertRefusal(send(delete(U + "/passkeys/not-a-uuid"), token, null), 404, "not_found");

        // A passwordless account cannot remove its only way in.
        accounts.update(owner.withPassword(null, false, false));
        Resp only = postJson(U + "/devices", token, Map.of("pin", PIN, "label", "only"));
        assertRefusal(send(delete(U + "/devices/" + only.text("deviceId")), token, null), 409, "last_credential");
    }

    @Test
    void recoveryCodesAreEightAndWorkOnceOverHttp() throws Exception {
        Resp made = postJson(U + "/recovery-codes", bearer(), null);
        assertThat(made.status()).isEqualTo(200);
        assertThat(made.body().get("codes")).hasSize(8);
        String code = made.body().get("codes").get(0).asText();
        assertThat(code).matches("[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){3}");
        assertThat(send(get(U + "/methods"), bearer(), null).body().get("recoveryCodesRemaining").asInt()).isEqualTo(8);

        Resp used = postJson(U + "/login", null, Map.of("type", "recovery", "identifier", "recovery", "credentials", Map.of("code", code)));
        assertThat(used.status()).isEqualTo(200);
        assertThat(ctx.getBean(LocalJwtService.class).decoder().decode(used.text("accessToken")).getClaimAsStringList("amr")).containsExactly("rec");
        assertRefusal(postJson(U + "/login", null, Map.of("type", "recovery", "identifier", "recovery", "credentials", Map.of("code", code))), 401, "invalid_credentials");
    }

    // ------------------------------------------------------------------ public paths ignore a stale Authorization header

    @Test
    void aStaleAuthorizationHeaderDoesNotBreakAPublicSignIn() throws Exception {
        Resp enrolled = postJson(U + "/devices", bearer(), Map.of("pin", PIN, "label", "x"));
        Map<String, Object> pinLogin = Map.of("type", "pin", "identifier", enrolled.text("deviceId"), "credentials", Map.of("pin", PIN, "deviceSecret", enrolled.text("deviceSecret")));

        Resp login = postJson(U + "/login", "expired.token.value", pinLogin);
        Resp options = postJson(U + "/passkey/login-options", "expired.token.value", null, "X-Vegaduta-Origin", ORIGIN);
        Resp setup = postJson(U + "/setup/pin", "expired.token.value", Map.of("token", pendingSetupToken(), "pin", "739105"));

        assertThat(login.status()).isEqualTo(200);
        assertThat(options.status()).isEqualTo(200);
        assertThat(setup.status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ validation and errors

    @Test
    void theLoginBodyIsValidated() throws Exception {
        assertThat(postJson(U + "/login", null, "{}").status()).isEqualTo(400);
        assertThat(postJson(U + "/login", null, Map.of("type", "password", "identifier", EMAIL, "credentials", Map.of("password", PASSWORD))).status())
                .as("passwords have their own endpoint")
                .isEqualTo(400);
        assertThat(postJson(U + "/login", null, Map.of("type", "pin", "identifier", "x")).status()).isEqualTo(400);
    }

    @Test
    void wrongCredentialsAreA401WithAStableCodeAndNoToken() throws Exception {
        Resp r = postJson(U + "/login", null, Map.of("type", "pin", "identifier", UUID.randomUUID().toString(), "credentials", Map.of("pin", PIN, "deviceSecret", "abc")));

        assertRefusal(r, 401, "invalid_credentials");
        assertThat(r.body().has("accessToken")).isFalse();
    }

    @Test
    void aRevokedDeviceWithTheRightPinAndSecretIsToldDeviceRevoked() throws Exception {
        String token = bearer();
        Resp device = postJson(U + "/devices", token, Map.of("pin", PIN, "label", "x"));
        Resp second = postJson(U + "/devices", token, Map.of("pin", PIN, "label", "y"));
        assertThat(send(delete(U + "/devices/" + device.text("deviceId")), token, null).status()).isEqualTo(204);

        assertRefusal(
                postJson(U + "/login", null, Map.of("type", "pin", "identifier", device.text("deviceId"), "credentials", Map.of("pin", PIN, "deviceSecret", device.text("deviceSecret")))),
                401,
                "device_revoked");
        assertThat(second.text("deviceId")).isNotEqualTo(device.text("deviceId"));
    }

    // ------------------------------------------------------------------ passkey options

    @Test
    void loginOptionsComeFromTheForwardedOriginOrTheOriginHeader() throws Exception {
        Resp forwarded = postJson(U + "/passkey/login-options", null, null, "X-Vegaduta-Origin", ORIGIN, "Origin", "http://core.internal:8080");
        Resp direct = postJson(U + "/passkey/login-options", null, null, "Origin", ORIGIN);

        for (Resp r : List.of(forwarded, direct)) {
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.text("challengeId")).matches("[0-9a-f]{32}");
            assertThat(r.body().get("publicKey").get("rpId").asText()).isEqualTo(RP_ID);
            assertThat(r.body().get("publicKey").get("userVerification").asText()).isEqualTo("required");
            assertThat(r.body().get("publicKey").get("allowCredentials")).isEmpty();
            assertThat(r.body().get("publicKey").get("challenge").asText()).hasSize(43);
        }
    }

    @Test
    void anIpAddressOrMissingOriginIsA400PasskeyOriginUnsupported() throws Exception {
        assertRefusal(postJson(U + "/passkey/login-options", null, null, "X-Vegaduta-Origin", "https://192.168.1.20:3000"), 400, "passkey_origin_unsupported");
        assertRefusal(postJson(U + "/passkey/login-options", null, null), 400, "passkey_origin_unsupported");
        assertRefusal(postJson(U + "/passkey/register-options", bearer(), null, "X-Vegaduta-Origin", "http://10.0.0.5"), 400, "passkey_origin_unsupported");
    }

    // ------------------------------------------------------------------ registration: bearer or setup token

    @Test
    void registrationOptionsWithoutABearerNeedAValidSetupToken() throws Exception {
        assertRefusal(postJson(U + "/passkey/register-options", null, null, "X-Vegaduta-Origin", ORIGIN), 401, "invalid_setup_token");
        assertRefusal(postJson(U + "/passkey/register-options", null, Map.of("setupToken", "vdls_forged"), "X-Vegaduta-Origin", ORIGIN), 401, "invalid_setup_token");

        Resp ok = postJson(U + "/passkey/register-options", null, Map.of("setupToken", pendingSetupToken()), "X-Vegaduta-Origin", ORIGIN);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("publicKey").get("rp").get("id").asText()).isEqualTo(RP_ID);
        assertThat(ok.body().get("publicKey").get("authenticatorSelection").get("residentKey").asText()).isEqualTo("required");
        assertThat(ok.body().get("publicKey").get("attestation").asText()).isEqualTo("none");
    }

    @Test
    void registrationOptionsWithABearerNameTheSignedInUser() throws Exception {
        Resp r = postJson(U + "/passkey/register-options", bearer(), null, "X-Vegaduta-Origin", ORIGIN);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("publicKey").get("user").get("name").asText()).isEqualTo(EMAIL);
        assertThat(r.body().get("publicKey").get("excludeCredentials")).isEmpty();
        assertThat(postJson(U + "/passkey/register-options", bearer() + "x", null, "X-Vegaduta-Origin", ORIGIN).status())
                .as("a bearer, when sent, must be valid")
                .isEqualTo(401);
    }

    private String registrationJson(SoftAuthenticator key, Resp options, String origin) {
        return key.registration(options.body().get("publicKey").get("challenge").asText(), origin, RP_ID, SoftAuthenticator.UP | SoftAuthenticator.UV | SoftAuthenticator.AT);
    }

    @Test
    void aSignedInPersonRegistersAPasskeyThenSignsInWithIt() throws Exception {
        SoftAuthenticator key = new SoftAuthenticator();
        String token = bearer();
        Resp options = postJson(U + "/passkey/register-options", token, null, "X-Vegaduta-Origin", ORIGIN);

        Resp registered = postJson(U + "/passkey/register", token, Map.of("challengeId", options.text("challengeId"), "credential", registrationJson(key, options, ORIGIN), "label", "MacBook"));
        assertThat(registered.status()).isEqualTo(200);
        assertThat(registered.body().get("passkey").get("label").asText()).isEqualTo("MacBook");
        assertThat(registered.body().get("passkey").get("backedUp").asBoolean()).isFalse();
        assertThat(registered.body().has("session")).as("a signed-in person already has a session").isFalse();
        assertThat(registered.body().has("recoveryCodes")).isFalse();

        Resp loginOptions = postJson(U + "/passkey/login-options", null, null, "X-Vegaduta-Origin", ORIGIN);
        String assertion = key.assertion(loginOptions.body().get("publicKey").get("challenge").asText(), ORIGIN, RP_ID, SoftAuthenticator.UP | SoftAuthenticator.UV, 1, null, false);
        Resp login = postJson(U + "/login", null, Map.of("type", "passkey", "identifier", loginOptions.text("challengeId"), "credentials", Map.of("assertion", assertion)));

        assertThat(login.status()).isEqualTo(200);
        assertThat(ctx.getBean(LocalJwtService.class).decoder().decode(login.text("accessToken")).getClaimAsStringList("amr")).containsExactly("hwk");
        assertThat(send(get(U + "/methods"), token, null).body().get("passkeys")).hasSize(1);
    }

    @Test
    void aBadRegistrationIsA400PasskeyInvalid() throws Exception {
        String token = bearer();
        Resp options = postJson(U + "/passkey/register-options", token, null, "X-Vegaduta-Origin", ORIGIN);

        assertRefusal(
                postJson(U + "/passkey/register", token, Map.of("challengeId", options.text("challengeId"), "credential", registrationJson(new SoftAuthenticator(), options, "https://evil.example.test"))),
                400,
                "passkey_invalid");
        assertThat(credentials.passkeys).isEmpty();
    }

    @Test
    void firstRunSetupWithAPasskeyNeedsNoBearerAndReturnsASessionAndRecoveryCodes() throws Exception {
        SoftAuthenticator key = new SoftAuthenticator();
        String setup = pendingSetupToken();
        Resp options = postJson(U + "/passkey/register-options", null, Map.of("setupToken", setup), "X-Vegaduta-Origin", ORIGIN);

        Resp done = postJson(U + "/passkey/register", null, Map.of("challengeId", options.text("challengeId"), "credential", registrationJson(key, options, ORIGIN), "setupToken", setup));

        assertThat(done.status()).isEqualTo(200);
        assertThat(done.body().get("session").get("accessToken").asText()).isNotBlank();
        assertThat(done.body().get("session").get("user").get("email").asText()).isEqualTo("first@example.test");
        assertThat(done.body().get("recoveryCodes")).hasSize(8);
        assertThat(done.body().get("passkey").get("id").asText()).isNotBlank();
        assertRefusal(postJson(U + "/passkey/register-options", null, Map.of("setupToken", setup), "X-Vegaduta-Origin", ORIGIN), 401, "invalid_setup_token");
    }

    // ------------------------------------------------------------------ setup with a PIN

    @Test
    void setupWithAPinReturnsTheSessionTheDeviceAndRecoveryCodes() throws Exception {
        String setup = pendingSetupToken();

        Resp done = postJson(U + "/setup/pin", null, Map.of("token", setup, "pin", "739105", "label", "Study"));

        assertThat(done.status()).isEqualTo(200);
        assertThat(done.body().get("session").get("accessToken").asText()).isNotBlank();
        assertThat(done.body().get("session").get("refreshToken").asText()).startsWith(LocalTokens.REFRESH_PREFIX);
        assertThat(done.text("deviceId")).isNotBlank();
        assertThat(done.text("deviceSecret")).hasSize(43);
        assertThat(done.body().get("recoveryCodes")).hasSize(8);
        assertThat(send(get("/api/probe/any"), done.body().get("session").get("accessToken").asText(), null).status()).isEqualTo(200);
        assertRefusal(postJson(U + "/setup/pin", null, Map.of("token", setup, "pin", "739105")), 401, "invalid_setup_token");
    }

    @Test
    void setupWithAWeakPinIsA400AndKeepsTheLink() throws Exception {
        String setup = pendingSetupToken();

        assertRefusal(postJson(U + "/setup/pin", null, Map.of("token", setup, "pin", "111111")), 400, "weak_pin");

        assertThat(postJson(U + "/setup/pin", null, Map.of("token", setup, "pin", "739105")).status()).isEqualTo(200);
    }

    @Test
    void setupWithAPinNeedsAToken() throws Exception {
        assertRefusal(postJson(U + "/setup/pin", null, Map.of("token", "vdls_forged", "pin", "739105")), 401, "invalid_setup_token");
        assertThat(postJson(U + "/setup/pin", null, Map.of("pin", "739105")).status()).isEqualTo(400);
    }
}
