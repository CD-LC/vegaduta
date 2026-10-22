package com.agenticai.core.agents.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.importer.AgentImportDtos.ConversionReport;
import com.agenticai.core.agents.importer.AgentImportDtos.ImportResponse;
import com.agenticai.core.agents.importer.AgentImportDtos.ImportedAgentView;
import com.agenticai.core.agents.importer.AgentImportDtos.PreviewResponse;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.UUID;

/**
 * The import routes are a thin shell: bounded raw body in, 200 for preview, 201 for the created
 * agent.
 */
class AgentImportControllerTest {

    private final AgentImportService service = mock(AgentImportService.class);
    private final AgentImportController controller = new AgentImportController(service);

    private static ConversionReport emptyReport() {
        return new ConversionReport(List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void previewPassesTheRawBodyThrough() throws Exception {
        PreviewResponse preview =
                new PreviewResponse(
                        "auto",
                        "flowise",
                        new ImportedAgentView(
                                "n", "", "i", "", "", null, null, List.of(), List.of()),
                        emptyReport());
        when(service.readBounded(any())).thenReturn("{\"x\":1}");
        when(service.preview("{\"x\":1}")).thenReturn(preview);

        assertThat(controller.preview(new MockHttpServletRequest())).isSameAs(preview);
    }

    @Test
    void importAnswers201WithTheNewAgentId() throws Exception {
        UUID id = UUID.randomUUID();
        ImportResponse created = new ImportResponse(id, emptyReport());
        when(service.readBounded(any())).thenReturn("{}");
        when(service.importAgent("{}")).thenReturn(created);

        ResponseEntity<ImportResponse> res = controller.importAgent(new MockHttpServletRequest());

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).isSameAs(created);
        verify(service).importAgent("{}");
    }

    @Test
    void responseDtosSerialiseWithTheDocumentedFieldNames() throws Exception {
        PreviewResponse preview =
                new PreviewResponse(
                        "auto",
                        "openai-assistant",
                        new ImportedAgentView(
                                "n",
                                "d",
                                "i",
                                "OPENAI",
                                "gpt-4o",
                                0.5,
                                null,
                                List.of("e2b-code-execution"),
                                List.of("run_code")),
                        emptyReport());

        var json =
                AgentImportFixtures.MAPPER.readTree(
                        AgentImportFixtures.MAPPER.writeValueAsString(preview));

        assertThat(json.propertyNames())
                .containsExactlyInAnyOrder("source", "detectedSource", "agent", "report");
        assertThat(json.get("agent").propertyNames())
                .containsExactlyInAnyOrder(
                        "name",
                        "description",
                        "instructions",
                        "modelProvider",
                        "model",
                        "temperature",
                        "topP",
                        "suggestedCapabilities",
                        "suggestedTools");
        assertThat(json.get("agent").get("topP").isNull()).isTrue();
        assertThat(json.get("report").propertyNames())
                .containsExactlyInAnyOrder("converted", "needsManual", "unsupported", "warnings");
    }
}
