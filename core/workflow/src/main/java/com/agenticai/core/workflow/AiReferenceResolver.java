package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowAiNodeHandler.*;
import static com.agenticai.core.workflow.WorkflowAiNodeSupport.str;

import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.agents.tools.web.HttpRequestResult;
import com.agenticai.core.agents.tools.web.HttpRequestSpec;
import com.agenticai.core.chat.structured.StructuredOutput;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.workflow.WorkflowDocumentText.DocumentExtractionException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves the {@code document_extractor} source (config text, or the node input) to a classified
 * reference - URL, uploaded-file key or raw text - and reads it safely: the storage key shape is
 * checked, size caps apply, and a URL containing a template reference is refused. Extracted from
 * {@link WorkflowAiNodeHandler}.
 */
final class AiReferenceResolver {
    private static final Logger log = LoggerFactory.getLogger(AiReferenceResolver.class);

    private static final Pattern STORAGE_KEY =
            Pattern.compile("chat-attachments/[A-Za-z0-9._/-]{1,250}");

    private final ObjectProvider<FileStorageService> storageProvider;
    private final ObjectProvider<HttpRequestExecutor> httpProvider;
    private final ObjectMapper objectMapper;
    private final int docMaxBytes;

    AiReferenceResolver(
            ObjectProvider<FileStorageService> storageProvider,
            ObjectProvider<HttpRequestExecutor> httpProvider,
            ObjectMapper objectMapper,
            int docMaxBytes) {
        this.storageProvider = storageProvider;
        this.httpProvider = httpProvider;
        this.objectMapper = objectMapper;
        this.docMaxBytes = docMaxBytes;
    }

    enum RefKind {
        URL,
        STORAGE,
        TEXT
    }

    record Reference(RefKind kind, String value, String fileName, String contentType) {}

    /**
     * {@code source} config (already resolved) else the node input; a JSON object is unpacked, a
     * URL / storage key / raw text classified.
     */
    Reference resolve(Map<String, Object> config, String input) {
        String source = str(config.get("source"));
        if (source.isEmpty()) {
            source = input == null ? "" : input.strip();
        }
        if (source.isEmpty()) {
            return null;
        }
        if (source.startsWith("{")) {
            Optional<JsonNode> parsed = StructuredOutput.readObject(objectMapper, source);
            if (parsed.isPresent()) {
                JsonNode json = parsed.get();
                String fileName = firstText(json, "fileName", "filename", "name");
                String type = firstText(json, "contentType", "mimeType");
                String key = firstText(json, "attachmentKey", "key");
                if (!key.isEmpty()) {
                    return new Reference(RefKind.STORAGE, key, fileName, type);
                }
                String url = firstText(json, "url", "fileUrl");
                if (!url.isEmpty()) {
                    return new Reference(
                            RefKind.URL, url, fileName.isEmpty() ? url : fileName, type);
                }
                String text = firstText(json, "text", "content");
                if (!text.isEmpty()) {
                    return new Reference(RefKind.TEXT, text, fileName, type);
                }
                return null;
            }
        }
        String lower = source.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return new Reference(
                    RefKind.URL, source.lines().findFirst().orElse("").strip(), source, null);
        }
        if (source.startsWith("chat-attachments/")) {
            return new Reference(
                    RefKind.STORAGE, source.lines().findFirst().orElse("").strip(), "", null);
        }
        return new Reference(RefKind.TEXT, source, "", null);
    }

    private static String firstText(JsonNode json, String... keys) {
        for (String key : keys) {
            JsonNode value = json.get(key);
            if (value != null && value.isString() && !value.asString().isBlank()) {
                return value.asString().strip();
            }
        }
        return "";
    }

    byte[] readStored(String key) {
        if (!STORAGE_KEY.matcher(key).matches() || key.contains("..") || key.contains("//")) {
            throw new DocumentExtractionException(
                    "the file reference is not a valid uploaded-file key");
        }
        FileStorageService storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new DocumentExtractionException(
                    "file storage is not available in this deployment");
        }
        try {
            long size = storage.size(key);
            if (size > docMaxBytes) {
                throw new DocumentExtractionException(
                        "the file is "
                                + (size / 1024)
                                + " KB, over the "
                                + (docMaxBytes / 1024)
                                + " KB limit");
            }
            try (InputStream in = storage.get(key)) {
                byte[] bytes = in.readNBytes(docMaxBytes + 1);
                if (bytes.length > docMaxBytes) {
                    throw new DocumentExtractionException(
                            "the file is over the " + (docMaxBytes / 1024) + " KB limit");
                }
                return bytes;
            }
        } catch (DocumentExtractionException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.debug("Document extractor could not read stored file {}: {}", key, e.toString());
            throw new DocumentExtractionException("the uploaded file could not be found or read");
        }
    }

    HttpRequestResult fetchUrl(String url) {
        // A "{{secret.NAME}}" smuggled into a URL that came from untrusted input would be resolved
        // by the HTTP executor and
        // sent to the attacker's host. This node never accepts a template reference in a URL.
        if (url.contains("{{") || url.contains("}}")) {
            throw new DocumentExtractionException(
                    "the URL contains a template reference, which is not allowed here");
        }
        HttpRequestExecutor executor = httpProvider.getIfAvailable();
        if (executor == null) {
            throw new DocumentExtractionException(
                    "fetching URLs is not available in this deployment");
        }
        String userId = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        HttpRequestResult result =
                executor.execute(
                        new HttpRequestSpec(
                                "GET",
                                url,
                                null,
                                null,
                                null,
                                null,
                                null,
                                HttpRequestExecutor.HARD_MAX_BODY_CHARS),
                        userId);
        if (!result.ok()) {
            throw new DocumentExtractionException("the URL returned HTTP " + result.status());
        }
        String type = result.contentType().toLowerCase(Locale.ROOT);
        if (type.contains("pdf")
                || type.contains("officedocument")
                || type.contains("octet-stream")
                || type.startsWith("image/")
                || type.startsWith("audio/")
                || type.startsWith("video/")) {
            throw new DocumentExtractionException(
                    "the URL serves a binary file - upload it and pass the attachment key instead");
        }
        return result;
    }

    static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "the URL" : host;
        } catch (IllegalArgumentException e) {
            return "the URL";
        }
    }
}
