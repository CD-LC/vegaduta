package com.agenticai.core.sdlc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.sdlc.dto.RunCodeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Every request to the sandbox host carries the {@code e2b-traffic-access-token} header from the
 * create call ({@code SandboxHandle.trafficAccessToken}); a handle without one (rollback flag off)
 * sends no header.
 */
class E2bCodeExecutionClientTrafficTokenTest {

    private static final String RUN_URL = "https://8080-sb1.e2b.app/run";
    private static final String HEADER = "e2b-traffic-access-token";
    private static final String RUN_RESPONSE =
            "{\"stdout\":\"hi\",\"stderr\":\"\",\"exitCode\":0,\"timedOut\":false}";

    private static E2bSandboxClient.SandboxHandle handle(String token) {
        return new E2bSandboxClient.SandboxHandle("sb1", "tpl", "1", null, token);
    }

    private static E2bCodeExecutionClient client(
            MockRestServiceServer[] serverOut, E2bSandboxClient sandboxClient) {
        RestClient.Builder builder = RestClient.builder();
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        return new E2bCodeExecutionClient(builder, sandboxClient);
    }

    @Test
    void runCodeSendsTheTokenHeaderToTheSandboxHostAndKillsTheSandbox() {
        E2bSandboxClient sandboxClient = mock(E2bSandboxClient.class);
        when(sandboxClient.createSandbox(eq("tpl"), anyInt(), isNull(), anyMap()))
                .thenReturn(handle("tok-123"));
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        E2bCodeExecutionClient client = client(server, sandboxClient);
        server[0]
                .expect(requestTo(RUN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HEADER, "tok-123"))
                .andRespond(withSuccess(RUN_RESPONSE, MediaType.APPLICATION_JSON));

        RunCodeResponse response = client.runCode("tpl", "print('hi')", "python", 10);

        assertThat(response.stdout()).isEqualTo("hi");
        assertThat(response.sandboxId()).isEqualTo("sb1");
        server[0].verify();
        verify(sandboxClient).killSandbox("sb1");
    }

    @Test
    void aSessionSendsTheTokenHeaderOnEveryRun() {
        E2bSandboxClient sandboxClient = mock(E2bSandboxClient.class);
        when(sandboxClient.createSandbox(eq("key"), eq("tpl"), anyInt(), isNull(), anyMap()))
                .thenReturn(handle("tok-456"));
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        E2bCodeExecutionClient client = client(server, sandboxClient);
        server[0]
                .expect(times(2), requestTo(RUN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HEADER, "tok-456"))
                .andRespond(withSuccess(RUN_RESPONSE, MediaType.APPLICATION_JSON));

        E2bAccess access = new E2bAccess("key", "tpl", E2bAccess.Source.TENANT);
        try (E2bCodeExecutionClient.Session session = client.openSession(access, 300)) {
            assertThat(session.run("echo hi", "bash", 10, null).stdout()).isEqualTo("hi");
            assertThat(session.run("echo hi", "bash", 10, null).stdout()).isEqualTo("hi");
        }

        server[0].verify();
        verify(sandboxClient).killSandbox("key", "sb1");
    }

    @Test
    void aHandleWithoutATokenSendsNoHeader() {
        E2bSandboxClient sandboxClient = mock(E2bSandboxClient.class);
        when(sandboxClient.createSandbox(eq("tpl"), anyInt(), isNull(), anyMap()))
                .thenReturn(handle(null));
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        E2bCodeExecutionClient client = client(server, sandboxClient);
        server[0]
                .expect(requestTo(RUN_URL))
                .andExpect(headerDoesNotExist(HEADER))
                .andRespond(withSuccess(RUN_RESPONSE, MediaType.APPLICATION_JSON));

        assertThat(client.runCode("tpl", "print('hi')", "python", 10).stdout()).isEqualTo("hi");
        server[0].verify();
    }
}
