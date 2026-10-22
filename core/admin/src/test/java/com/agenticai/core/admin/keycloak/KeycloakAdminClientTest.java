package com.agenticai.core.admin.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Regression coverage for the data loss in {@link KeycloakAdminClient#setUserAttributes} - the bug
 * that broke sign-in for every social-login user who created an organization.
 *
 * <p>That method used to PUT an attributes-ONLY body, on the (wrong, and wrongly documented)
 * assumption that omitted root fields are left alone. Since Keycloak 24.0.4 a representation
 * carrying an {@code attributes} map is the user's FULL attribute set, so email/firstName/lastName
 * were wiped - which fails the realm's User Profile required-field check on every subsequent
 * authentication and pins the user to the VERIFY_PROFILE screen. The shape of the outgoing PUT is
 * therefore the whole fix, and asserting on that request body is the only way to pin it shut.
 *
 * <p>Uses {@link MockRestServiceServer} bound to a real {@link RestClient.Builder} - the same
 * pattern {@code ProviderModelCatalogServiceTest} established for {@code RestClient}-consuming
 * classes, and a direct fit here because {@link KeycloakAdminClient} already takes an injected
 * builder (it was changed to do so by the 2026-07-29 timeout audit - see its constructor javadoc).
 * Every public method on this client fetches an admin token first, so each test stubs the token
 * endpoint before the call under test.
 */
class KeycloakAdminClientTest {

    private static final String BASE_URL = "https://auth.vegaduta.test";
    private static final String REALM = "agentic-ai";
    private static final String USER_ID = "kc-federated-1";
    private static final String USERS_URL =
            BASE_URL + "/admin/realms/" + REALM + "/users/" + USER_ID;
    private static final String TOKEN_URL =
            BASE_URL + "/realms/" + REALM + "/protocol/openid-connect/token";
    private static final String ADMIN_TOKEN = "admin-token-1";

    private MockRestServiceServer mockServer;
    private KeycloakAdminClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client =
                new KeycloakAdminClient(
                        builder, BASE_URL, REALM, "agentic-ai-core", "client-secret");
    }

    private void expectAdminTokenRequest() {
        mockServer
                .expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withSuccess(
                                "{\"access_token\":\"" + ADMIN_TOKEN + "\"}",
                                MediaType.APPLICATION_JSON));
    }

    @Test
    void setUserAttributesReAssertsRootFieldsAndMergesInsteadOfReplacingAttributes() {
        expectAdminTokenRequest();
        // The user as Keycloak holds them today: a healthy federated identity
        // with a pre-existing attribute that has nothing to do with tenancy.
        mockServer
                .expect(requestTo(USERS_URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "id": "kc-federated-1",
                                  "username": "person@gmail.com",
                                  "email": "person@gmail.com",
                                  "firstName": "Ada",
                                  "lastName": "Admin",
                                  "enabled": true,
                                  "emailVerified": true,
                                  "attributes": { "locale": ["en"], "tenant_id": ["stale_schema"] },
                                  "requiredActions": ["VERIFY_EMAIL"]
                                }
                                """,
                                MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(USERS_URL))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("Authorization", "Bearer " + ADMIN_TOKEN))
                // THE assertions this suite exists for: the three User Profile
                // required fields must be present in the body, because a body
                // carrying "attributes" without them deletes them.
                .andExpect(jsonPath("$.email").value("person@gmail.com"))
                .andExpect(jsonPath("$.firstName").value("Ada"))
                .andExpect(jsonPath("$.lastName").value("Admin"))
                .andExpect(jsonPath("$.username").value("person@gmail.com"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                // Merge, not replace: an unrelated existing attribute survives...
                .andExpect(jsonPath("$.attributes.locale[0]").value("en"))
                // ...the new keys are added...
                .andExpect(jsonPath("$.attributes.organization_name[0]").value("Acme"))
                // ...and on a key collision the new value wins.
                .andExpect(jsonPath("$.attributes.tenant_id[0]").value("acme"))
                // requiredActions is deliberately never sent - re-asserting it
                // would risk clearing a legitimately pending VERIFY_EMAIL.
                .andExpect(jsonPath("$.requiredActions").doesNotExist())
                .andRespond(withNoContent());

        client.setUserAttributes(USER_ID, Map.of("tenant_id", "acme", "organization_name", "Acme"));

        mockServer.verify();
    }

    @Test
    void
            setUserAttributesFailsLoudlyWhenTheUserCannotBeReadRatherThanFallingBackToADestructivePut() {
        expectAdminTokenRequest();
        mockServer
                .expect(requestTo(USERS_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThrows(
                KeycloakAdminException.class,
                () -> client.setUserAttributes(USER_ID, Map.of("tenant_id", "acme")));

        // No PUT is expected, so MockRestServiceServer fails this test if one
        // was sent - which is the point. A fallback to the old attributes-only
        // PUT when the read fails would quietly reintroduce the data loss.
        mockServer.verify();
    }

    @Test
    void setUserEnabledSendsNoAttributesMapSoItCannotClearTheUsersAttributes() {
        expectAdminTokenRequest();
        mockServer
                .expect(requestTo(USERS_URL))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.enabled").value(false))
                // Keycloak only replaces a user's attributes when the request body
                // carries an "attributes" key at all. This partial PUT is safe
                // precisely because it does not - pinned here so it stays that way.
                .andExpect(jsonPath("$.attributes").doesNotExist())
                .andRespond(withNoContent());

        client.setUserEnabled(USER_ID, false);

        mockServer.verify();
    }

    @Test
    void logoutUserPostsToTheUsersLogoutEndpoint() {
        expectAdminTokenRequest();
        mockServer
                .expect(requestTo(USERS_URL + "/logout"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());

        client.logoutUser(USER_ID);

        mockServer.verify();
    }

    // The two routine, admin-actionable create-user rejections observed in
    // prod (2026-10-01, POST /api/admin/users): Keycloak answers 409 on a
    // duplicate email/username and 400 on User Profile validation. Both used
    // to surface as a generic 502 carrying the raw upstream JSON plus an
    // internal service-account hint; they must translate to typed exceptions
    // with clean messages instead.
    @Test
    void createUser409TranslatesToConflictException() {
        expectAdminTokenRequest();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withStatus(HttpStatus.CONFLICT)
                                .body("{\"errorMessage\":\"User exists with same email\"}")
                                .contentType(MediaType.APPLICATION_JSON));

        KeycloakUserConflictException e =
                assertThrows(
                        KeycloakUserConflictException.class,
                        () ->
                                client.createUser(
                                        "newuser",
                                        "taken@example.com",
                                        "New",
                                        "User",
                                        "TempPass123",
                                        "acme"));
        assertEquals("A user with this email already exists.", e.getMessage());

        mockServer.verify();
    }

    @Test
    void createUser409OnUsernameTranslatesToConflictException() {
        expectAdminTokenRequest();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withStatus(HttpStatus.CONFLICT)
                                .body("{\"errorMessage\":\"User exists with same username\"}")
                                .contentType(MediaType.APPLICATION_JSON));

        KeycloakUserConflictException e =
                assertThrows(
                        KeycloakUserConflictException.class,
                        () ->
                                client.createUserForInvite(
                                        "taken-name", "free@example.com", "New", "User", "acme"));
        assertEquals("A user with this username already exists.", e.getMessage());

        mockServer.verify();
    }

    @Test
    void createUser400TranslatesToValidationException() {
        expectAdminTokenRequest();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withBadRequest()
                                .body(
                                        "{\"field\":\"username\",\"errorMessage\":\"error-invalid-length\",\"params\":[\"username\",3,255]}")
                                .contentType(MediaType.APPLICATION_JSON));

        KeycloakUserValidationException e =
                assertThrows(
                        KeycloakUserValidationException.class,
                        () ->
                                client.createUser(
                                        "ab",
                                        "new@example.com",
                                        "New",
                                        "User",
                                        "TempPass123",
                                        "acme"));
        assertEquals("Username must be between 3 and 255 characters.", e.getMessage());

        mockServer.verify();
    }

    @Test
    void createUserUnexpectedFailureStaysAGenericKeycloakAdminException() {
        expectAdminTokenRequest();
        mockServer
                .expect(requestTo(BASE_URL + "/admin/realms/" + REALM + "/users"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        KeycloakAdminException e =
                assertThrows(
                        KeycloakAdminException.class,
                        () ->
                                client.createUser(
                                        "newuser",
                                        "new@example.com",
                                        "New",
                                        "User",
                                        "TempPass123",
                                        "acme"));
        assertTrue(e.getMessage().contains("create user"), e.getMessage());

        mockServer.verify();
    }

    // --- B3 / B4: the password is applied exactly once, and a failed create leaves no user behind
    // ----------

    private static final String USERS_COLLECTION = BASE_URL + "/admin/realms/" + REALM + "/users";
    private static final String NEW_USER_ID = "kc-new-1";
    private static final String NEW_USER_JSON =
            "[{\"id\":\"kc-new-1\",\"username\":\"new@example.com\"}]";

    @Test
    void signupSetsThePasswordExactlyOnce_notInTheCreateBodyAndAgain() {
        // Realm passwordHistory policy: the same password set twice is rejected
        // (invalidPasswordHistoryMessage),
        // which made every signup on a freshly imported realm fail with 502.
        expectAdminTokenRequest();
        mockServer
                .expect(requestTo(USERS_COLLECTION))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.credentials").isEmpty())
                .andRespond(withStatus(HttpStatus.CREATED));
        mockServer
                .expect(requestTo(USERS_COLLECTION + "?username=new%40example.com&exact=true"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(NEW_USER_JSON, MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(USERS_COLLECTION + "/" + NEW_USER_ID + "/reset-password"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.value").value("Str0ngPassw0rd!"))
                .andExpect(jsonPath("$.temporary").value(false))
                .andRespond(withNoContent());
        mockServer
                .expect(requestTo(USERS_COLLECTION + "/" + NEW_USER_ID))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withNoContent());

        KeycloakUserRepresentation created =
                client.createUserForSignup(
                        "new@example.com",
                        "new@example.com",
                        "New",
                        "User",
                        "Str0ngPassw0rd!",
                        Map.of("tenant_id", "acme"));

        assertEquals(NEW_USER_ID, created.id());
        mockServer.verify(); // exactly ONE reset-password call (an extra one would fail the strict
        // ordering above)
    }

    @Test
    void aFailingPasswordStepDeletesTheJustCreatedUserSoNothingIsLeftBehind() {
        expectAdminTokenRequest();
        mockServer
                .expect(requestTo(USERS_COLLECTION))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED));
        mockServer
                .expect(requestTo(USERS_COLLECTION + "?username=new%40example.com&exact=true"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(NEW_USER_JSON, MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(USERS_COLLECTION + "/" + NEW_USER_ID + "/reset-password"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(
                        withBadRequest()
                                .body("{\"error\":\"invalidPasswordHistoryMessage\"}")
                                .contentType(MediaType.APPLICATION_JSON));
        // compensation: a fresh admin token, then DELETE of the user that was just created
        expectAdminTokenRequest();
        mockServer
                .expect(requestTo(USERS_COLLECTION + "/" + NEW_USER_ID))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());

        assertThrows(
                KeycloakAdminException.class,
                () ->
                        client.createUserForSignup(
                                "new@example.com",
                                "new@example.com",
                                "New",
                                "User",
                                "Str0ngPassw0rd!",
                                Map.of("tenant_id", "acme")));

        mockServer.verify();
    }

    // --- listUsers paging (2026-10-09): Keycloak silently caps an unpaged list at 100 ----------

    private static final String LIST_URL =
            BASE_URL + "/admin/realms/" + REALM + "/users?briefRepresentation=false";

    private static String usersJson(int from, int count, String tenant) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"u")
                    .append(from + i)
                    .append("\",\"username\":\"user")
                    .append(from + i)
                    .append(
                            "\",\"createdTimestamp\":1759999999000,\"attributes\":{\"tenant_id\":[\"")
                    .append(tenant)
                    .append("\"]}}");
        }
        return sb.append(']').toString();
    }

    @Test
    void listUsersPagesUntilAShortPageSoRealmsPastOneHundredUsersAreComplete() {
        expectAdminTokenRequest();
        mockServer
                .expect(requestTo(LIST_URL + "&first=0&max=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(usersJson(0, 100, "acme"), MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(LIST_URL + "&first=100&max=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(usersJson(100, 1, "globex"), MediaType.APPLICATION_JSON));

        List<KeycloakUserRepresentation> users = client.listUsers();

        assertEquals(101, users.size());
        assertEquals("u100", users.get(100).id());
        // createdTimestamp now deserialises (record + legacy 10-arg constructor coexist).
        assertEquals(1759999999000L, users.get(0).createdTimestamp());
        mockServer.verify();
    }

    @Test
    void listUsersStopsOnAnEmptyPageAfterAnExactlyFullOne() {
        expectAdminTokenRequest();
        mockServer
                .expect(requestTo(LIST_URL + "&first=0&max=100"))
                .andRespond(withSuccess(usersJson(0, 100, "acme"), MediaType.APPLICATION_JSON));
        mockServer
                .expect(requestTo(LIST_URL + "&first=100&max=100"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertEquals(100, client.listUsers().size());
        mockServer.verify();
    }

    @Test
    void listUsersGroupedByTenantGroupsOnTheTenantIdAttributeFromOnePagedRead() {
        expectAdminTokenRequest();
        mockServer
                .expect(requestTo(LIST_URL + "&first=0&max=100"))
                .andRespond(
                        withSuccess(
                                "["
                                        + usersJson(0, 2, "acme")
                                                .substring(1, usersJson(0, 2, "acme").length() - 1)
                                        + ","
                                        + usersJson(2, 1, "globex")
                                                .substring(
                                                        1, usersJson(2, 1, "globex").length() - 1)
                                        + ",{\"id\":\"orphan\"}]",
                                MediaType.APPLICATION_JSON));

        Map<String, List<KeycloakUserRepresentation>> grouped = client.listUsersGroupedByTenant();

        assertEquals(2, grouped.get("acme").size());
        assertEquals(1, grouped.get("globex").size());
        assertEquals(2, grouped.size());
        mockServer.verify();
    }

    @Test
    void anOutboundRepresentationWithoutCreatedTimestampOmitsTheField() throws Exception {
        KeycloakUserRepresentation outbound =
                new KeycloakUserRepresentation(
                        null,
                        "new@example.com",
                        "new@example.com",
                        "N",
                        "U",
                        true,
                        false,
                        List.of(),
                        Map.of(),
                        List.of());
        String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(outbound);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("createdTimestamp"), json);
    }
}
