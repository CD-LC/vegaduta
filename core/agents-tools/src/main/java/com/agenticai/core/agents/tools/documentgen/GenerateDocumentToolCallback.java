package com.agenticai.core.agents.tools.documentgen;

import com.agenticai.core.documentgen.DocumentGenerationQuotaExceededException;
import com.agenticai.core.documentgen.model.DocumentContent;
import com.agenticai.core.documentgen.model.DocumentGenerationJobResponse;
import com.agenticai.core.documentgen.model.DocumentGenerationRequest;
import com.agenticai.core.documentgen.model.DocumentGenerationStatus;
import com.agenticai.core.documentgen.model.DocumentType;
import com.agenticai.core.documentgen.service.DocumentGenerationService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * Exposes {@link DocumentGenerationService} as a native Spring AI tool - generates a
 * DOCX/PDF/XLSX/PPTX/CSV/TXT/MD/JSON file and returns a downloadable reference, the exact same "a
 * tool result can only return text, never raw binary" shape {@code
 * com.agenticai.core.imagegen.agenttools.GenerateImageToolCallback} already established (mirrored
 * deliberately - see that class's javadoc).
 *
 * <p>Unlike image/video generation, {@link DocumentGenerationService#requestGeneration} only ever
 * enqueues (the real rendering happens off-thread in {@code DocumentGenerationEventListener} after
 * the enqueuing transaction commits - see that service's own javadoc), so this callback polls
 * {@link DocumentGenerationService#getStatus} in a short bounded loop afterward. Document rendering
 * is local/CPU-bound (no third-party API round trip like video), so a short ~8s poll window is
 * enough for it to reach a terminal state in the overwhelming majority of calls; a still-PROCESSING
 * job after that window is reported as "still in progress" rather than treated as a failure - the
 * job keeps running and will show up in Files/history-listing endpoints once it finishes.
 */
public class GenerateDocumentToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GenerateDocumentToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private static final long POLL_INTERVAL_MS = 250;
    private static final long POLL_TIMEOUT_MS = 8_000;

    private final DocumentGenerationService documentGenerationService;

    public GenerateDocumentToolCallback(DocumentGenerationService documentGenerationService) {
        this.documentGenerationService = documentGenerationService;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("generate_document")
                .description(
                        "Generates a downloadable document file (Word/DOCX, PDF, Excel/XLSX, PowerPoint/PPTX, CSV, plain "
                                + "text, Markdown, or JSON) from structured content and returns a downloadUrl. A section may "
                                + "carry imageUrl - a public http(s) image (png/jpeg) downloaded and embedded into the deck. "
                                + "This tool cannot "
                                + "return file bytes directly, so to give the user the file you MUST include the returned "
                                + "downloadUrl verbatim in your reply. Use it when the user asks to create, generate, write, "
                                + "export, or produce a document, report, spreadsheet, slide deck, or file they can download.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "type": {
                            "type": "string",
                            "enum": ["DOCX", "PDF", "XLSX", "PPTX", "CSV", "TXT", "MD", "JSON"],
                            "description": "The output file format."
                        },
                        "filename": {
                            "type": "string",
                            "description": "Desired filename WITHOUT an extension - the correct extension is appended automatically."
                        },
                        "title": {
                            "type": "string",
                            "description": "Optional document title."
                        },
                        "sections": {
                            "type": "array",
                            "description": "Optional ordered body content. Each section becomes a slide/page.",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "heading": {"type": "string"},
                                    "paragraphs": {"type": "array", "items": {"type": "string"}},
                                    "bulletPoints": {"type": "array", "items": {"type": "string"}},
                                    "imageUrl": {
                                        "type": "string",
                                        "description": "Optional public http(s) URL of an image (png/jpeg) to embed on this section's slide/page - it is downloaded server-side."
                                    }
                                }
                            }
                        },
                        "table": {
                            "type": "object",
                            "description": "Optional tabular data - the primary payload for CSV/XLSX.",
                            "properties": {
                                "headers": {"type": "array", "items": {"type": "string"}},
                                "rows": {"type": "array", "items": {"type": "array", "items": {"type": "string"}}}
                            }
                        },
                        "rawText": {
                            "type": "string",
                            "description": "Optional pre-formatted plain text - the primary payload for TXT, and MD's fallback when sections is empty."
                        }
                    },
                    "required": ["type", "filename"]
                }
                """)
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        try {
            Map<String, Object> args = MAPPER.readValue(toolInput, Map.class);

            String typeArg = asString(args.get("type"));
            if (typeArg == null || typeArg.isBlank()) {
                return errorJson("type is required");
            }
            DocumentType type;
            try {
                type = DocumentType.valueOf(typeArg.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return errorJson(
                        "Unsupported document type '"
                                + typeArg
                                + "'. Valid types: DOCX, PDF, XLSX, PPTX, CSV, TXT, MD, JSON.");
            }

            String filename = asString(args.get("filename"));
            if (filename == null || filename.isBlank()) {
                return errorJson("filename is required");
            }

            String title = asString(args.get("title"));
            String rawText = asString(args.get("rawText"));
            List<DocumentContent.Section> sections =
                    parseSections((List<Object>) args.get("sections"));
            DocumentContent.Table table = parseTable((Map<String, Object>) args.get("table"));

            DocumentContent content = new DocumentContent(title, sections, table, rawText, null);
            DocumentGenerationRequest request =
                    new DocumentGenerationRequest(type, filename.trim(), content);

            String tenantId = currentTenantId();
            String requestedBy = currentSubject();

            DocumentGenerationJobResponse job =
                    documentGenerationService.requestGeneration(request, tenantId, requestedBy);
            DocumentGenerationJobResponse finalStatus =
                    pollUntilTerminal(UUID.fromString(job.id()), tenantId);

            return switch (finalStatus.status()) {
                case COMPLETE ->
                        MAPPER.writeValueAsString(
                                Map.of(
                                        "status",
                                        "success",
                                        "downloadUrl",
                                        finalStatus.downloadUrl(),
                                        "filename",
                                        finalStatus.filename(),
                                        "instructions",
                                        "The document was generated successfully. To give it to the user you MUST "
                                                + "include this exact URL on its own line in your reply: "
                                                + finalStatus.downloadUrl()
                                                + " — the chat renders it as a downloadable file link. Do NOT claim you cannot generate files."));
                case FAILED ->
                        MAPPER.writeValueAsString(
                                Map.of(
                                        "status",
                                        "failed",
                                        "error",
                                        finalStatus.errorMessage() != null
                                                ? finalStatus.errorMessage()
                                                : "Document generation failed."));
                default ->
                        MAPPER.writeValueAsString(
                                Map.of(
                                        "status",
                                        "in_progress",
                                        "jobId",
                                        finalStatus.id(),
                                        "instructions",
                                        "The document is still being generated. Tell the user it's still in progress "
                                                + "and the file will appear on their Files page shortly."));
            };
        } catch (Exception e) {
            log.warn("generate_document tool call failed", e);
            return errorJson(consumerReason(e));
        }
    }

    private DocumentGenerationJobResponse pollUntilTerminal(UUID jobId, String tenantId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        DocumentGenerationJobResponse status = documentGenerationService.getStatus(jobId, tenantId);
        while (status.status() != DocumentGenerationStatus.COMPLETE
                && status.status() != DocumentGenerationStatus.FAILED
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS);
            status = documentGenerationService.getStatus(jobId, tenantId);
        }
        return status;
    }

    @SuppressWarnings("unchecked")
    private List<DocumentContent.Section> parseSections(List<Object> rawSections) {
        if (rawSections == null || rawSections.isEmpty()) {
            return List.of();
        }
        List<DocumentContent.Section> sections = new ArrayList<>();
        for (Object raw : rawSections) {
            if (!(raw instanceof Map)) {
                continue;
            }
            Map<String, Object> sectionMap = (Map<String, Object>) raw;
            String heading = asString(sectionMap.get("heading"));
            List<String> paragraphs = asStringList(sectionMap.get("paragraphs"));
            List<String> bulletPoints = asStringList(sectionMap.get("bulletPoints"));
            String imageUrl = asString(sectionMap.get("imageUrl"));
            sections.add(new DocumentContent.Section(heading, paragraphs, bulletPoints, imageUrl));
        }
        return sections;
    }

    @SuppressWarnings("unchecked")
    private DocumentContent.Table parseTable(Map<String, Object> rawTable) {
        if (rawTable == null || rawTable.isEmpty()) {
            return null;
        }
        List<String> headers = asStringList(rawTable.get("headers"));
        List<Object> rawRows = (List<Object>) rawTable.get("rows");
        List<List<String>> rows = new ArrayList<>();
        if (rawRows != null) {
            for (Object rawRow : rawRows) {
                if (rawRow instanceof List) {
                    rows.add(asStringList(rawRow));
                }
            }
        }
        return new DocumentContent.Table(headers, rows);
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        if (!(value instanceof List)) {
            return List.of();
        }
        List<Object> raw = (List<Object>) value;
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            if (item != null) {
                result.add(item.toString());
            }
        }
        return result;
    }

    private String currentTenantId() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse(null);
    }

    private String currentSubject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    /**
     * Turns a failure into something the model can actually relay to a person, instead of a raw
     * exception message - same posture as {@code GenerateImageToolCallback#consumerReason}.
     */
    private static String consumerReason(Exception e) {
        if (e instanceof DocumentGenerationQuotaExceededException quota) {
            // Already written for a person by DocumentGenerationQuotaService - pass it through.
            return quota.getMessage() + " Tell the user this and do not retry the tool.";
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return "Document generation was interrupted. Tell the user to try again in a moment.";
        }
        return "The document could not be generated just now. Tell the user the file could not be created and "
                + "that they can try again in a moment; answer the rest of their message normally.";
    }

    // Same bug class fixed in KnowledgeSearchToolCallback - hand-built
    // "{\"error\": \"...\"}" strings break if the exception message
    // contains a raw '"'.
    private static String errorJson(String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("error", message));
        } catch (Exception e) {
            return "{\"error\": \"error\"}";
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
