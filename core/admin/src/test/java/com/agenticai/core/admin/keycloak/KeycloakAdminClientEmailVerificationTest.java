package com.agenticai.core.admin.keycloak;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

/**
 * A self-hosted install with no SMTP (the lite tier) must not make the first sign-up end on "Failed
 * to send email": with {@code app.keycloak.require-email-verification=false} a new user gets no
 * {@code VERIFY_EMAIL} required action; by default they still do.
 */
class KeycloakAdminClientEmailVerificationTest {

    private static final String BASE_URL = "https://auth.vegaduta.test";
    private static final String REALM = "agentic-ai";
    private static final String USERS = BASE_URL + "/admin/realms/" + REALM + "/users";

    private void signUp(boolean requireVerification, String expectedActionsJsonPath) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        KeycloakAdminClient client =
                new KeycloakAdminClient(builder, BASE_URL, REALM, "agentic-ai-core", "secret");
        ReflectionTestUtils.setField(client, "requireEmailVerification", requireVerification);

        server.expect(requestTo(BASE_URL + "/realms/" + REALM + "/protocol/openid-connect/token"))
                .andRespond(withSuccess("{\"access_token\":\"t\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.requiredActions" + expectedActionsJsonPath).exists())
                .andRespond(withStatus(HttpStatus.CREATED));
        server.expect(requestTo(startsWith(USERS + "?username=")))
                .andRespond(withSuccess("[{\"id\":\"u1\",\"username\":\"new@example.com\"}]", MediaType.APPLICATION_JSON));
        ResponseCreator ok = withNoContent();
        server.expect(requestTo(USERS + "/u1/reset-password")).andRespond(ok);
        server.expect(requestTo(USERS + "/u1")).andExpect(method(HttpMethod.PUT)).andRespond(ok);

        client.createUserForSignup("new@example.com", "new@example.com", "Ada", "Admin", "Password1!", Map.of("tenant_id", "t_1"));
        server.verify();
    }

    @Test
    void byDefaultANewUserMustVerifyTheirEmail() {
        signUp(true, "[?(@ == 'VERIFY_EMAIL')]");
    }

    @Test
    void withVerificationOffANewUserHasNoRequiredAction() {
        // An empty array: no element matches, and the field is present but empty.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        KeycloakAdminClient client =
                new KeycloakAdminClient(builder, BASE_URL, REALM, "agentic-ai-core", "secret");
        ReflectionTestUtils.setField(client, "requireEmailVerification", false);

        server.expect(requestTo(BASE_URL + "/realms/" + REALM + "/protocol/openid-connect/token"))
                .andRespond(withSuccess("{\"access_token\":\"t\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.requiredActions").isEmpty())
                .andRespond(withStatus(HttpStatus.CREATED));
        server.expect(requestTo(startsWith(USERS + "?username=")))
                .andRespond(withSuccess("[{\"id\":\"u1\",\"username\":\"new@example.com\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS + "/u1/reset-password")).andRespond(withNoContent());
        server.expect(requestTo(USERS + "/u1")).andExpect(method(HttpMethod.PUT)).andRespond(withNoContent());

        client.createUserForSignup("new@example.com", "new@example.com", "Ada", "Admin", "Password1!", Map.of("tenant_id", "t_1"));
        server.verify();
    }
}
