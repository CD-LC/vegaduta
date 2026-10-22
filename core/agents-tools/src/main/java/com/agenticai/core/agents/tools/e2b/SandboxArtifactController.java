package com.agenticai.core.agents.tools.e2b;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import java.io.InputStream;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Serves the files a {@code run_code} call left behind (charts, workbooks, scripts, notebooks) -
 * {@code GET /api/sandbox/artifacts/{id}}. Authenticated like every other {@code /api/**} route.
 * Three layers keep it from being a data leak: object storage is tenant-prefixed, the id is an
 * unguessable random UUID, and the sidecar metadata records the producing agent so the caller must
 * also be able to VIEW that agent ({@link AgentService#get} applies the per-user access rule) -
 * anything else is a plain 404, indistinguishable from "no such file".
 *
 * <p>Defence in depth for model-authored content (an SVG can carry script): always sent as an
 * {@code attachment}, with {@code nosniff} and a {@code sandbox} CSP so even if it is opened
 * directly it cannot run anything.
 */
@RestController
@RequestMapping("/api/sandbox/artifacts")
public class SandboxArtifactController {

    private static final Logger log = LoggerFactory.getLogger(SandboxArtifactController.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final FileStorageService fileStorageService;
    private final AgentService agentService;

    public SandboxArtifactController(
            FileStorageService fileStorageService, AgentService agentService) {
        this.fileStorageService = fileStorageService;
        this.agentService = agentService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        try {
            JsonNode meta = readMeta(id);
            if (meta == null) {
                return ResponseEntity.notFound().build();
            }
            String ext = meta.path("ext").asString("");
            String mime = SandboxArtifacts.mimeFor(ext);
            if (mime == null
                    || !mayView(meta.path("agentId").asString(null))
                    || !isUploader(meta.path("userId").asString(null))) {
                return ResponseEntity.notFound().build();
            }
            byte[] bytes;
            try (InputStream in = fileStorageService.get(SandboxArtifacts.dataKey(id, ext))) {
                bytes = in.readAllBytes();
            }
            String fallback = "analysis-" + id.toString().substring(0, 8) + "." + ext;
            String filename =
                    SandboxArtifacts.safeDisplayName(meta.path("name").asString(null), fallback);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mime))
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(filename).build().toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "default-src 'none'; sandbox")
                    .body(bytes);
        } catch (Exception e) {
            log.warn("Could not serve sandbox artifact {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /** One metadata read; null when absent/unreadable (expired or never existed). */
    private JsonNode readMeta(UUID id) {
        String key = SandboxArtifacts.metaKey(id);
        try {
            if (!fileStorageService.exists(key)) {
                return null;
            }
            try (InputStream in = fileStorageService.get(key)) {
                return MAPPER.readTree(in.readAllBytes());
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Uploader scope: when the artifact recorded the user who produced it, only that user may
     * download it (a different authenticated user, or no identifiable caller, gets a 404 - fails
     * closed). Artifacts without a recorded user (written with no subject, or before this check
     * existed) keep the agent-visibility rule alone.
     */
    private static boolean isUploader(String recordedUserId) {
        if (recordedUserId == null || recordedUserId.isBlank()) {
            return true;
        }
        return TenantContext.current()
                .map(TenantContext::getSubject)
                .filter(recordedUserId::equals)
                .isPresent();
    }

    /** The caller must be able to view the agent that produced the file (fails closed). */
    private boolean mayView(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return false;
        }
        try {
            agentService.get(UUID.fromString(agentId));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
