package com.agenticai.core.admin.auth0;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.admin.keycloak.KeycloakAdminException;
import com.agenticai.core.admin.keycloak.KeycloakUserConflictException;
import com.agenticai.core.admin.keycloak.KeycloakUserRepresentation;
import com.agenticai.core.admin.keycloak.KeycloakUserValidationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * HTTP-contract coverage for {@link Auth0UserAdminClient}, mirroring {@code
 * KeycloakAdminClientTest}'s approach: {@link MockRestServiceServer} bound to a real {@link
 * RestClient.Builder}, asserting on the exact Management API request shapes the adapter sends.
 *
 * <p>Auth0 user ids are {@code "auth0|..."} - the {@code |} must reach the wire percent-encoded
 * ({@code %7C}), which is why every per-user URL below is asserted in its encoded form.
 */
class Auth0UserAdminClientTest {

    private static final String DOMAIN = "acme-tenant.auth0.test";
    private static final String BASE_URL = "https://" + DOMAIN;
    private static final String CLIENT_ID = "m2m-client";
    private static final String CLIENT_SECRET = "m2m-secret";
    private static final String CONNECTION = "Username-Password-Authentication";
    private static final String USER_ID = "auth0|abc123";
    private static final String USER_ID_ENCODED = "auth0%7Cabc123";
    private static final String USER_URL = BASE_URL + "/api/v2/users/" + USER_ID_ENCODED;
    private static final String TOKEN_URL = BASE_URL + "/oauth/token";
    private static final String MGMT_TOKEN = "mgmt-token-1";
    private static final String TOKEN_RESPONSE =
            "{\"access_token\":\""
                    + MGMT_TOKEN
                    + "\",\"expires_in\":86400,\"token_type\":\"Bearer\"}";

    private MockRestServiceServer mockServer;
    private Auth0UserAdminClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client =
                new Auth0UserAdminClient(
                        builder, DOMAIN, CLIENT_ID, CLIENT_SECRET, CONNECTION, "");
    }

    private void expectTokenRequest() {
        mockServer
                .expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.grant_type").value("client_credentials"))
                .andExpect(jsonPath("$.client_id").value(CLIENT_ID))
                .andExpect(jsonPath("$.client_secret").value(CLIENT_SECRET))
                .andExpect(jsonPath("$.audience").value(BASE_URL + "/api/v2/"))
                .andRespond(withSuccess(TOKEN_RESPONSE, MediaType.APPLICATION_JSON));
    }

    // --- token acquisition + caching -----------------------------------------------------------

    @Test
    void managementTokenIsFetchedOnceAndCachedAcrossCalls() {
        expectTokenRequest();
        for (int i = 0; i < 2; i++) {
            mockServer
                    .expect(requestTo(USER_URL))
                    .andExpect(method(HttpMethod.PATCH))
                    .andExpect(header("Authorization", "Bearer " + MGMT_TOKEN))
                    .andExpect(jsonPath("$.blocked").value(true))
                    .andRespond(withNoContent());
        }

        client.setUserEnabled(USER_ID, false);
        client.setUserEnabled(USER_ID, false);

        // A second token request would fail the strict expectation ordering - the cache is
        // what makes this pass.
        mockServer.verify();
    }

    // --- reads ---------------------------------------------------------------------------------

    @Test
    void listUsersByTenantSendsTheLuceneAppMetadataQuery() {
        expectTokenRequest();
        mockServer
                .expect(
                        requestTo(
                                BASE_URL
                                        + "/api/v2/users?q=app_metadata.tenant_id%3A%22acme%22"
                                        + "&search_engine=v3&page=0&per_page=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + MGMT_TOKEN))
                .andRespond(
                        withSuccess(
                                """
                                [{
                                  "user_id": "auth0|abc123",
                                  "email": "ada@acme.com",
                                  "given_name": "Ada",
                                  "family_name": "Admin",
                                  "blocked": false,
                                  "email_verified": true,
                                  "app_metadata": {"tenant_id": "acme"},
                                  "created_at": "2026-01-01T00:00:00.000Z"
                                }]
                                """,
                                MediaType.APPLICATION_JSON));

        List<KeycloakUserRepresentation> users = client.listUsersByTenant("acme");

        assertEquals(1, users.size());
        KeycloakUserRepresentation user = users.get(0);
        assertEquals(USER_ID, user.id());
        assertEquals("ada@acme.com", user.username());
        assertEquals("Ada", user.firstName());
        assertEquals("Admin", user.lastName());
        assertEquals(Boolean.TRUE, user.enabled());
        assertEquals(Boolean.TRUE, user.emailVerified());
        assertEquals(List.of("acme"), user.attributes().get("tenant_id"));
        assertEquals(1767225600000L, user.createdTimestamp());
        mockServer.verify();
    }

    @Test
    void listUsersPagesUntilAShortPageAndGroupsByTenant() {
        expectTokenRequest();
        StringBuilder full = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) {
                full.append(',');
            }
            full.append("{\"user_id\":\"auth0|u")
                    .append(i)
                    .append("\",\"app_metadata\":{\"tenant_id\":\"acme\"}}");
        }
        full.append(']');
        mockServer
                .expect(requestTo(BASE_URL + "/api/v2/users?page=0&per_page=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(full.toString(), MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + "/api/v2/users?page=1&per_page=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "[{\"user_id\":\"auth0|u100\","
                                        + "\"app_metadata\":{\"tenant_id\":\"globex\"}},"
                                        + "{\"user_id\":\"auth0|orphan\"}]",
                                MediaType.APPLICATION_JSON));

        Map<String, List<KeycloakUserRepresentation>> grouped = client.listUsersGroupedByTenant();

        assertEquals(100, grouped.get("acme").size());
        assertEquals(1, grouped.get("globex").size());
        assertEquals(2, grouped.size()); // the metadata-less user is omitted
        mockServer.verify();
    }

    @Test
    void findByEmailUsesTheLuceneEmailQueryAndMapsEmptyToEmpty() {
        expectTokenRequest();
        mockServer
                .expect(
                        requestTo(
                                BASE_URL
                                        + "/api/v2/users?q=email%3A%22ada%40example.com%22"
                                        + "&search_engine=v3&per_page=1&page=0"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "[{\"user_id\":\"auth0|abc123\",\"email\":\"ada@example.com\"}]",
                                MediaType.APPLICATION_JSON));

        Optional<KeycloakUserRepresentation> found = client.findByEmail("ada@example.com");

        assertTrue(found.isPresent());
        assertEquals(USER_ID, found.get().id());
        mockServer.verify();
    }

    @Test
    void findByEmailReturnsEmptyWhenNothingMatches() {
        expectTokenRequest();
        mockServer
                .expect(
                        requestTo(
                                BASE_URL
                                        + "/api/v2/users?q=email%3A%22nobody%40example.com%22"
                                        + "&search_engine=v3&per_page=1&page=0"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertFalse(client.findByEmail("nobody@example.com").isPresent());
        mockServer.verify();
    }

    @Test
    void getUserByIdPercentEncodesThePipeInTheAuth0Id() {
        expectTokenRequest();
        mockServer
                .expect(requestTo(USER_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "{\"user_id\":\"auth0|abc123\",\"email\":\"ada@acme.com\"}",
                                MediaType.APPLICATION_JSON));

        assertEquals(USER_ID, client.getUserById(USER_ID).id());
        mockServer.verify();
    }

    // --- writes --------------------------------------------------------------------------------

    @Test
    void createUserPostsTheAuth0BodyThenSendsTheResetEmail() {
        expectTokenRequest();
        mockServer
                .expect(requestTo(BASE_URL + "/api/v2/users"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + MGMT_TOKEN))
                .andExpect(jsonPath("$.connection").value(CONNECTION))
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.password").value("TempPass123"))
                .andExpect(jsonPath("$.username").value("newuser"))
                .andExpect(jsonPath("$.given_name").value("New"))
                .andExpect(jsonPath("$.family_name").value("User"))
                .andExpect(jsonPath("$.email_verified").value(false))
                .andExpect(jsonPath("$.verify_email").value(true))
                .andExpect(jsonPath("$.app_metadata.tenant_id").value("acme"))
                .andRespond(
                        withStatus(HttpStatus.CREATED)
                                .body(
                                        "{\"user_id\":\"auth0|new1\",\"email\":\"new@example.com\"}")
                                .contentType(MediaType.APPLICATION_JSON));
        // The temporary-password approximation: fetch the user for their email...
        mockServer
                .expect(requestTo(BASE_URL + "/api/v2/users/auth0%7Cnew1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "{\"user_id\":\"auth0|new1\",\"email\":\"new@example.com\"}",
                                MediaType.APPLICATION_JSON));
        // ...then Auth0 itself emails the reset link (Authentication API, no bearer token).
        mockServer
                .expect(requestTo(BASE_URL + "/dbconnections/change_password"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.client_id").value(CLIENT_ID))
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.connection").value(CONNECTION))
                .andRespond(withSuccess("\"ok\"", MediaType.APPLICATION_JSON));

        KeycloakUserRepresentation created =
                client.createUser(
                        "newuser", "new@example.com", "New", "User", "TempPass123", "acme");

        assertEquals("auth0|new1", created.id());
        mockServer.verify();
    }

    @Test
    void createUserForSignupCarriesAllAttributesAndNoResetEmail() {
        expectTokenRequest();
        mockServer
                .expect(requestTo(BASE_URL + "/api/v2/users"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.password").value("Str0ngPassw0rd!"))
                .andExpect(jsonPath("$.app_metadata.tenant_id").value("acme"))
                .andExpect(jsonPath("$.app_metadata.organization_name").value("Acme"))
                // username duplicates the email here, so it must not be sent: the field is
                // only valid on connections with requires_username enabled.
                .andExpect(jsonPath("$.username").doesNotExist())
                .andRespond(
                        withStatus(HttpStatus.CREATED)
                                .body("{\"user_id\":\"auth0|su1\"}")
                                .contentType(MediaType.APPLICATION_JSON));

        KeycloakUserRepresentation created =
                client.createUserForSignup(
                        "new@example.com",
                        "new@example.com",
                        "New",
                        "User",
                        "Str0ngPassw0rd!",
                        Map.of("tenant_id", "acme", "organization_name", "Acme"));

        assertEquals("auth0|su1", created.id());
        mockServer.verify(); // no follow-up calls - the strict ordering would catch a stray one
    }

    @Test
    void createUserForInviteMintsAPasswordTheCallerNeverSees() {
        expectTokenRequest();
        mockServer
                .expect(requestTo(BASE_URL + "/api/v2/users"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.password").exists())
                .andExpect(jsonPath("$.verify_email").value(false))
                .andRespond(
                        withStatus(HttpStatus.CREATED)
                                .body("{\"user_id\":\"auth0|inv1\"}")
                                .contentType(MediaType.APPLICATION_JSON));

        KeycloakUserRepresentation created =
                client.createUserForInvite(
                        "invited@example.com", "invited@example.com", "In", "Vited", "acme");

        assertEquals("auth0|inv1", created.id());
        mockServer.verify();
    }

    // --- emails --------------------------------------------------------------------------------

    @Test
    void sendActionsEmailMapsUpdatePasswordAndVerifyEmailToTheAuth0Endpoints() {
        expectTokenRequest();
        mockServer
                .expect(requestTo(USER_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "{\"user_id\":\"auth0|abc123\",\"email\":\"ada@acme.com\"}",
                                MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + "/dbconnections/change_password"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.email").value("ada@acme.com"))
                .andExpect(jsonPath("$.connection").value(CONNECTION))
                .andRespond(withSuccess("\"ok\"", MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(BASE_URL + "/api/v2/jobs/verification-email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.user_id").value(USER_ID))
                .andExpect(jsonPath("$.client_id").value(CLIENT_ID))
                .andRespond(withStatus(HttpStatus.CREATED));

        client.sendActionsEmail(USER_ID, List.of("UPDATE_PASSWORD", "VERIFY_EMAIL"));

        mockServer.verify();
    }

    @Test
    void sendActionsEmailRejectsAnActionAuth0CannotDeliver() {
        expectTokenRequest();

        assertThrows(
                KeycloakUserValidationException.class,
                () -> client.sendActionsEmail(USER_ID, List.of("CONFIGURE_TOTP")));

        mockServer.verify();
    }

    // --- attributes / enabled / logout / delete -------------------------------------------------

    @Test
    void setUserAttributesPatchesAppMetadataWhichAuth0Merges() {
        expectTokenRequest();
        mockServer
                .expect(requestTo(USER_URL))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.app_metadata.tenant_id").value("acme"))
                .andExpect(jsonPath("$.app_metadata.organization_name").value("Acme"))
                // Auth0 merges app_metadata at the top level - no read-modify-write needed,
                // and no other root field is touched.
                .andExpect(jsonPath("$.email").doesNotExist())
                .andRespond(withNoContent());

        client.setUserAttributes(USER_ID, Map.of("tenant_id", "acme", "organization_name", "Acme"));

        mockServer.verify();
    }

    @Test
    void setUserAttributesWithAnEmptyMapIsANoOpSoAppMetadataIsNeverWiped() {
        // Auth0 treats PATCH {"app_metadata": {}} as "delete all app metadata" - the guard must
        // stop that call from ever being made. Any request at all fails this test.
        client.setUserAttributes(USER_ID, Map.of());
        mockServer.verify();
    }

    @Test
    void logoutUserDeletesSessionsAndRefreshTokens() {
        expectTokenRequest();
        mockServer
                .expect(requestTo(USER_URL + "/sessions"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer " + MGMT_TOKEN))
                .andRespond(withStatus(HttpStatus.ACCEPTED));
        mockServer
                .expect(requestTo(USER_URL + "/refresh-tokens"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        client.logoutUser(USER_ID);

        mockServer.verify();
    }

    @Test
    void deleteUserCallsTheAuth0DeleteEndpoint() {
        expectTokenRequest();
        mockServer
                .expect(requestTo(USER_URL))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.deleteUser(USER_ID);

        mockServer.verify();
    }

    // --- roles -----------------------------------------------------------------------------------

    @Test
    void replaceUserRealmRolesDiffsAndSendsRoleIds() {
        expectTokenRequest();
        // Currently assigned: tenant-admin + agent-builder.
        mockServer
                .expect(requestTo(USER_URL + "/roles?per_page=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "[{\"id\":\"rol_1\",\"name\":\"tenant-admin\"},"
                                        + "{\"id\":\"rol_2\",\"name\":\"agent-builder\"}]",
                                MediaType.APPLICATION_JSON));
        // All roles in the tenant - the name -> id resolution source.
        mockServer
                .expect(requestTo(BASE_URL + "/api/v2/roles?page=0&per_page=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "[{\"id\":\"rol_1\",\"name\":\"tenant-admin\"},"
                                        + "{\"id\":\"rol_2\",\"name\":\"agent-builder\"},"
                                        + "{\"id\":\"rol_3\",\"name\":\"end-customer\"}]",
                                MediaType.APPLICATION_JSON));
        // Desired = {tenant-admin, end-customer}: add rol_3, remove rol_2.
        mockServer
                .expect(requestTo(USER_URL + "/roles"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.roles[0]").value("rol_3"))
                .andRespond(withNoContent());
        mockServer
                .expect(requestTo(USER_URL + "/roles"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(jsonPath("$.roles[0]").value("rol_2"))
                .andRespond(withNoContent());

        client.replaceUserRealmRoles(USER_ID, Set.of("tenant-admin", "end-customer"));

        mockServer.verify();
    }

    @Test
    void replaceUserRealmRolesKeepsThePrivilegeEscalationGuard() {
        // platform-admin is rejected before any HTTP call - no token request is even expected.
        assertThrows(
                IllegalArgumentException.class,
                () -> client.replaceUserRealmRoles(USER_ID, Set.of("platform-admin")));
        mockServer.verify();
    }

    // --- error mapping ---------------------------------------------------------------------------

    @Test
    void createUser409TranslatesToConflictException() {
        expectTokenRequest();
        mockServer
                .expect(requestTo(BASE_URL + "/api/v2/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withStatus(HttpStatus.CONFLICT)
                                .body(
                                        "{\"statusCode\":409,\"error\":\"Conflict\","
                                                + "\"message\":\"The user already exists.\"}")
                                .contentType(MediaType.APPLICATION_JSON));

        KeycloakUserConflictException e =
                assertThrows(
                        KeycloakUserConflictException.class,
                        () ->
                                client.createUserForInvite(
                                        "taken", "taken@example.com", "New", "User", "acme"));
        assertEquals("The user already exists.", e.getMessage());
        mockServer.verify();
    }

    @Test
    void createUser400TranslatesToValidationExceptionWithTheAuth0Message() {
        expectTokenRequest();
        mockServer
                .expect(requestTo(BASE_URL + "/api/v2/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withBadRequest()
                                .body(
                                        "{\"statusCode\":400,\"error\":\"Bad Request\","
                                                + "\"message\":\"PasswordStrengthError: Password is"
                                                + " too weak\"}")
                                .contentType(MediaType.APPLICATION_JSON));

        KeycloakUserValidationException e =
                assertThrows(
                        KeycloakUserValidationException.class,
                        () ->
                                client.createUser(
                                        "newuser",
                                        "new@example.com",
                                        "New",
                                        "User",
                                        "weak",
                                        "acme"));
        assertEquals("PasswordStrengthError: Password is too weak", e.getMessage());
        mockServer.verify();
    }

    @Test
    void upstreamFailureStaysAGenericKeycloakAdminException() {
        expectTokenRequest();
        mockServer
                .expect(requestTo(USER_URL))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        KeycloakAdminException e =
                assertThrows(KeycloakAdminException.class, () -> client.deleteUser(USER_ID));
        assertTrue(e.getMessage().contains("delete user"), e.getMessage());
        mockServer.verify();
    }

    @Test
    void aBareDomainIsNormalizedToAnHttpsBaseUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Auth0UserAdminClient bareDomain =
                new Auth0UserAdminClient(
                        builder,
                        "plain-tenant.auth0.test",
                        CLIENT_ID,
                        CLIENT_SECRET,
                        CONNECTION,
                        "");
        server.expect(requestTo("https://plain-tenant.auth0.test/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_RESPONSE, MediaType.APPLICATION_JSON));
        server.expect(
                        requestTo(
                                "https://plain-tenant.auth0.test/api/v2/users/"
                                        + USER_ID_ENCODED))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "{\"user_id\":\"auth0|abc123\"}", MediaType.APPLICATION_JSON));

        assertEquals(USER_ID, bareDomain.getUserById(USER_ID).id());
        server.verify();
    }
}
