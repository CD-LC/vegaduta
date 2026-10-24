package com.agenticai.core.agents.tools.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.agents.tools.code.dto.SandboxValidateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The load-bearing distinction this client exists to preserve: a non-2xx is a SERVICE fault ({@link
 * ToolSandboxUnavailableException} -> 502), while a 200 carrying {@code valid:false} is a VERDICT
 * about the tenant's code. Collapsing the two would let a deploy blip silently mark good tools
 * REJECTED.
 */
class ToolSandboxClientTest {

    private static final String BASE_URL = "http://tool-sandbox:8095";

    @Test
    void mapsNonSuccessStatusToToolSandboxUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/v1/validate"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body("compiler pool exhausted"));

        ToolSandboxClient client = ToolSandboxClient.usingBuilderTransport(builder, properties());

        assertThatThrownBy(
                        () -> client.validate(CustomCodeToolLanguage.JAVA, "my_tool", "class X {}"))
                .isInstanceOf(ToolSandboxUnavailableException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageContaining("compiler pool exhausted");
        server.verify();
    }

    @Test
    void mapsUnauthorizedToToolSandboxUnavailableToo() {
        // A bad/missing internal token is an operator problem, never a verdict
        // about the tenant's source.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/v1/validate"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ToolSandboxClient client = ToolSandboxClient.usingBuilderTransport(builder, properties());

        assertThatThrownBy(
                        () -> client.validate(CustomCodeToolLanguage.PYTHON, "my_tool", "print(1)"))
                .isInstanceOf(ToolSandboxUnavailableException.class)
                .hasMessageContaining("HTTP 401");
    }

    @Test
    void a200InvalidVerdictIsReturnedNotThrown() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/v1/validate"))
                // Both auth headers go out on every call.
                .andExpect(header("X-Internal-Token", "s3cret"))
                .andExpect(header("X-Tenant-Id", ""))
                .andRespond(
                        withSuccess(
                                "{\"valid\":false,\"sourceSha256\":\"abc\",\"errors\":[{\"line\":2,\"message\":\"cannot find symbol\"}],\"warnings\":[]}",
                                MediaType.APPLICATION_JSON));

        ToolSandboxClient client = ToolSandboxClient.usingBuilderTransport(builder, properties());
        SandboxValidateResponse verdict =
                client.validate(CustomCodeToolLanguage.JAVA, "my_tool", "class X {}");

        assertThat(verdict.valid()).isFalse();
        assertThat(verdict.sourceSha256()).isEqualTo("abc");
        assertThat(verdict.errorsOrEmpty())
                .singleElement()
                .satisfies(
                        e -> {
                            assertThat(e.line()).isEqualTo(2);
                            assertThat(e.message()).isEqualTo("cannot find symbol");
                        });
    }

    @Test
    void unknownResponseFieldsDoNotBreakDeserialization() {
        // The sandbox is versioned independently; a field this build has never
        // heard of must not turn a good verdict into a 502.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/v1/validate"))
                .andRespond(
                        withSuccess(
                                "{\"valid\":true,\"sourceSha256\":\"abc\",\"warnings\":[\"unused import\"],\"futureField\":123}",
                                MediaType.APPLICATION_JSON));

        SandboxValidateResponse verdict =
                ToolSandboxClient.usingBuilderTransport(builder, properties())
                        .validate(CustomCodeToolLanguage.JAVA, "my_tool", "class X {}");

        assertThat(verdict.valid()).isTrue();
        assertThat(verdict.warningsOrEmpty()).containsExactly("unused import");
        assertThat(verdict.errorsOrEmpty()).isEmpty();
    }

    private static ToolSandboxProperties properties() {
        ToolSandboxProperties properties = new ToolSandboxProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(BASE_URL);
        properties.setInternalToken("s3cret");
        return properties;
    }
}
