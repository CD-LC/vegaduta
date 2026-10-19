package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.providers.ToolCallCapabilityProbe.Result;
import com.agenticai.core.providers.ToolCallCapabilityProbe.ServerKind;
import com.agenticai.core.providers.ToolCallGrade;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HostedToolCallGradeProbeTest {

    private static final String BASE = "http://ollama.test:11434";
    private static final String STRUCTURED_REPLY =
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"c1\","
            + "\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}]}}]}";
    private static final String TEXT_REPLY =
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
            + "\"{\\\"name\\\": \\\"get_weather\\\", \\\"parameters\\\": {\\\"city\\\": \\\"Paris\\\"}}\"}}]}";
    private static final String PROSE_REPLY =
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"I would call get_weather for Paris.\"}}]}";

    private MockRestServiceServer server;
    private HostedToolCallGradeProbe probe;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        probe = new HostedToolCallGradeProbe(builder, JsonMappers.plain(), true);
    }

    private void ollamaShow(String capabilities, String parameterSize) {
        server.expect(requestTo(BASE + "/api/show")).andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.model").value("llama3.2:3b"))
            .andRespond(withSuccess(
                "{\"details\":{\"parameter_size\":\"" + parameterSize + "\"},\"capabilities\":" + capabilities + "}",
                MediaType.APPLICATION_JSON));
    }

    @Test
    void ollamaWithoutToolsCapabilityIsNoneWithoutAModelCall() {
        ollamaShow("[\"completion\"]", "3.2B");
        Optional<Result> result = probe.probe(BASE, null, "llama3.2:3b", null);
        server.verify();
        assertEquals(ToolCallGrade.NONE, result.orElseThrow().grade());
        assertEquals(ServerKind.OLLAMA, result.get().serverKind());
        assertEquals(3.2d, result.get().parameterBillions(), 0.001);
    }

    @Test
    void structuredToolCallsGradeStructured() {
        ollamaShow("[\"completion\",\"tools\"]", "3.2B");
        server.expect(requestTo(BASE + "/v1/chat/completions")).andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.tools", Matchers.hasSize(2)))
            .andExpect(jsonPath("$.tool_choice").value("auto"))
            .andExpect(jsonPath("$.stream").value(false))
            .andRespond(withSuccess(STRUCTURED_REPLY, MediaType.APPLICATION_JSON));
        assertEquals(ToolCallGrade.STRUCTURED, probe.probe(BASE, null, "llama3.2:3b", "").orElseThrow().grade());
        server.verify();
    }

    @Test
    void textFormCallToAnOfferedToolGradesTextAndChecksTheSchemaSupport() {
        ollamaShow("[\"completion\",\"tools\"]", "3.2B");
        server.expect(requestTo(BASE + "/v1/chat/completions"))
            .andRespond(withSuccess(TEXT_REPLY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/chat/completions"))
            .andExpect(jsonPath("$.response_format.type").value("json_schema"))
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}", MediaType.APPLICATION_JSON));
        Result result = probe.probe(BASE, null, "llama3.2:3b", null).orElseThrow();
        assertEquals(ToolCallGrade.TEXT, result.grade());
        assertEquals(ServerKind.OLLAMA, result.serverKind());
        server.verify();
    }

    @Test
    void honoursJsonSchemaIsFalseOnlyWhenTheServerRefusesTheFormat() {
        server.expect(requestTo(BASE + "/v1/chat/completions"))
            .andRespond(withBadRequest().body("{\"error\":{\"message\":\"structured output is unavailable\"}}")
                .contentType(MediaType.APPLICATION_JSON));
        assertEquals(false, probe.honoursJsonSchema(BASE, null, "qwen3.5:4b", null));
        server.verify();
        server.reset();
        server.expect(requestTo(BASE + "/v1/chat/completions"))
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}", MediaType.APPLICATION_JSON));
        assertEquals(true, probe.honoursJsonSchema(BASE, null, "qwen3.5:4b", null));
        server.verify();
    }

    @Test
    void proseReplyGradesNoneAndARefusedSchemaMarksTheServerWithoutFormat() {
        ollamaShow("[\"completion\",\"tools\"]", "3.2B");
        server.expect(requestTo(BASE + "/v1/chat/completions"))
            .andRespond(withSuccess(PROSE_REPLY, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/chat/completions"))
            .andRespond(withBadRequest().body("{\"error\":{\"message\":\"structured output is unavailable\"}}")
                .contentType(MediaType.APPLICATION_JSON));
        Result result = probe.probe(BASE, null, "llama3.2:3b", null).orElseThrow();
        assertEquals(ToolCallGrade.NONE, result.grade());
        assertEquals(ServerKind.OLLAMA_WITHOUT_FORMAT, result.serverKind());
    }

    @Test
    void nonOllamaServerSkipsShowDetectsVllmAndSendsTheBearerKey() {
        server.expect(requestTo(BASE + "/api/show")).andRespond(withResourceNotFound());
        server.expect(requestTo(BASE + "/version")).andRespond(withSuccess("{\"version\":\"0.9\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/chat/completions"))
            .andExpect(header("Authorization", "Bearer k-1"))
            .andRespond(withSuccess(STRUCTURED_REPLY, MediaType.APPLICATION_JSON));
        Result result = probe.probe(BASE, null, "llama3.2:3b", "k-1").orElseThrow();
        assertEquals(ToolCallGrade.STRUCTURED, result.grade());
        assertEquals(ServerKind.VLLM, result.serverKind());
        assertEquals(null, result.parameterBillions());
    }

    @Test
    void customCompletionsPathIsUsedAndAToolsRejectionIsNone() {
        server.expect(requestTo(BASE + "/api/show")).andRespond(withResourceNotFound());
        server.expect(requestTo(BASE + "/version")).andRespond(withResourceNotFound());
        server.expect(requestTo(BASE + "/props")).andRespond(withResourceNotFound());
        server.expect(requestTo(BASE + "/v1beta/openai/chat/completions"))
            .andRespond(withBadRequest().body("{\"error\":\"tools are not supported\"}").contentType(MediaType.APPLICATION_JSON));
        Result result = probe.probe(BASE, "/v1beta/openai/chat/completions", "llama3.2:3b", null).orElseThrow();
        assertEquals(ToolCallGrade.NONE, result.grade());
        assertEquals(ServerKind.OTHER, result.serverKind());
    }

    @Test
    void unreachableServerGradesNothing() {
        server.expect(requestTo(BASE + "/api/show")).andRespond(withResourceNotFound());
        server.expect(requestTo(BASE + "/version")).andRespond(withResourceNotFound());
        server.expect(requestTo(BASE + "/props")).andRespond(withResourceNotFound());
        server.expect(requestTo(BASE + "/v1/chat/completions")).andRespond(request -> {
            throw new java.io.IOException("connection refused");
        });
        assertTrue(probe.probe(BASE, null, "llama3.2:3b", null).isEmpty());
    }

    @Test
    void disabledProbeMakesNoCall() {
        HostedToolCallGradeProbe off = new HostedToolCallGradeProbe(RestClient.builder(), JsonMappers.plain(), false);
        assertTrue(off.probe(BASE, null, "llama3.2:3b", null).isEmpty());
    }

    @Test
    void parameterCountFallsBackToModelInfo() throws Exception {
        var show = JsonMappers.plain().readTree("{\"details\":{\"parameter_size\":\"\"},\"model_info\":{\"general.parameter_count\":4538986496}}");
        assertEquals(4.539d, HostedToolCallGradeProbe.parameterBillions(show), 0.001);
    }

    @Test
    void tenantServerProbeNeverUsesTheUnguardedClient() {
        // The test seam has no guarded transport: a tenant URL must not fall back to the plain client.
        assertTrue(probe.probeTenantServer(BASE, null, "llama3.2:3b", null).isEmpty());
        server.verify();
    }

    @Test
    void tenantServerProbeRunsOverTheGuardedClientOfTheWiredConstructor() {
        RestClient.Builder plain = RestClient.builder();
        MockRestServiceServer plainServer = MockRestServiceServer.bindTo(plain).build();
        RestClient.Builder guarded = RestClient.builder();
        MockRestServiceServer guardedServer = MockRestServiceServer.bindTo(guarded).build();
        guardedServer.expect(requestTo(BASE + "/api/show")).andRespond(withServerError());
        guardedServer.expect(requestTo(BASE + "/version")).andRespond(withSuccess("{\"version\":\"0.9\"}", MediaType.APPLICATION_JSON));
        guardedServer.expect(requestTo(BASE + "/v1/chat/completions")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(STRUCTURED_REPLY, MediaType.APPLICATION_JSON));
        LlmHttpClientFactory http = org.mockito.Mockito.mock(LlmHttpClientFactory.class);
        org.mockito.Mockito.when(http.restClientBuilder()).thenReturn(plain);
        org.mockito.Mockito.when(http.guardedRestClientBuilder()).thenReturn(guarded);
        HostedToolCallGradeProbe wired = new HostedToolCallGradeProbe(http, JsonMappers.plain(), true);

        Result result = wired.probeTenantServer(BASE, null, "qwen3-8b", null).orElseThrow();

        assertEquals(ToolCallGrade.STRUCTURED, result.grade());
        assertEquals(ServerKind.VLLM, result.serverKind());
        guardedServer.verify();
        plainServer.verify();
    }
}
