package com.agenticai.core.sdlc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sandboxes are created with {@code network.allowPublicTraffic=false} and the {@code
 * trafficAccessToken} E2B returns is carried on the handle; a create that comes back without the
 * token fails closed (sandbox killed, exception) instead of running an open sandbox.
 */
class E2bSandboxClientPublicTrafficTest {

    private static final String CREATE_URL = "https://api.e2b.app/sandboxes";
    private static final String KILL_URL = "https://api.e2b.app/sandboxes/sb1";
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private static E2bSandboxClient client(MockRestServiceServer[] serverOut, Boolean restrict) {
        RestClient.Builder builder = RestClient.builder();
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        E2bCredentialService credentials = mock(E2bCredentialService.class);
        return restrict == null
                ? new E2bSandboxClient(builder, credentials)
                : new E2bSandboxClient(builder, credentials, restrict);
    }

    private static RequestMatcher jsonBody(Consumer<JsonNode> check) {
        return request ->
                check.accept(MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString()));
    }

    @Test
    void createSendsTheRestrictionByDefaultAndTheHandleCarriesTheToken() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        E2bSandboxClient client = client(server, null);
        server[0]
                .expect(requestTo(CREATE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(
                        jsonBody(
                                body -> {
                                    assertThat(body.path("templateID").asString()).isEqualTo("tpl");
                                    assertThat(
                                                    body.path("network")
                                                            .path("allowPublicTraffic")
                                                            .isBoolean())
                                            .isTrue();
                                    assertThat(
                                                    body.path("network")
                                                            .path("allowPublicTraffic")
                                                            .asBoolean())
                                            .isFalse();
                                }))
                .andRespond(
                        withSuccess(
                                "{\"sandboxID\":\"sb1\",\"templateID\":\"tpl\",\"envdVersion\":\"1\","
                                        + "\"envdAccessToken\":null,\"trafficAccessToken\":\"tok-123\"}",
                                MediaType.APPLICATION_JSON));

        E2bSandboxClient.SandboxHandle handle = client.createSandbox("key", "tpl", 60, null, null);

        assertThat(handle.sandboxId()).isEqualTo("sb1");
        assertThat(handle.trafficAccessToken()).isEqualTo("tok-123");
        assertThat(handle.envdAccessToken()).isNull();
        server[0].verify();
    }

    @Test
    void aCreateWithoutTheTokenFailsClosedAndKillsTheSandbox() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        E2bSandboxClient client = client(server, true);
        server[0]
                .expect(requestTo(CREATE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withSuccess(
                                "{\"sandboxID\":\"sb1\",\"templateID\":\"tpl\",\"envdVersion\":\"1\"}",
                                MediaType.APPLICATION_JSON));
        server[0]
                .expect(requestTo(KILL_URL))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());

        assertThatThrownBy(() -> client.createSandbox("key", "tpl", 60, null, null))
                .isInstanceOf(E2bApiException.class)
                .hasMessageContaining("trafficAccessToken")
                .hasMessageContaining("sb1");
        server[0].verify();
    }

    @Test
    void aJsonNullTokenFailsClosedEvenWhenTheKillItselfFails() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        E2bSandboxClient client = client(server, true);
        server[0]
                .expect(requestTo(CREATE_URL))
                .andRespond(
                        withSuccess(
                                "{\"sandboxID\":\"sb1\",\"templateID\":\"tpl\",\"envdVersion\":\"1\","
                                        + "\"trafficAccessToken\":null}",
                                MediaType.APPLICATION_JSON));
        server[0]
                .expect(requestTo(KILL_URL))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.createSandbox("key", "tpl", 60, null, null))
                .isInstanceOf(E2bApiException.class)
                .hasMessageContaining("trafficAccessToken");
        server[0].verify();
    }

    @Test
    void rollbackFlagOffSendsNoRestrictionAndAcceptsAHandleWithoutAToken() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        E2bSandboxClient client = client(server, false);
        server[0]
                .expect(requestTo(CREATE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonBody(body -> assertThat(body.has("network")).isFalse()))
                .andRespond(
                        withSuccess(
                                "{\"sandboxID\":\"sb1\",\"templateID\":\"tpl\",\"envdVersion\":\"1\"}",
                                MediaType.APPLICATION_JSON));

        E2bSandboxClient.SandboxHandle handle = client.createSandbox("key", "tpl", 60, null, null);

        assertThat(handle.sandboxId()).isEqualTo("sb1");
        assertThat(handle.trafficAccessToken()).isNull();
        server[0].verify(); // no kill: an open sandbox was asked for explicitly
    }
}
