package com.agenticai.core.common.plugins;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * HTTP surface over {@link PluginArtifactService} - all validation, digesting, and the
 * bare-key storage discipline live in the service; this class only maps the two routes:
 *
 * <ul>
 *   <li>{@code POST /api/platform/plugins/artifacts} - platform-admin upload, gated by
 *       the existing {@code /api/platform/**} {@code ROLE_platform-admin} matcher in
 *       {@code SecurityConfig} (no new rule needed). Returns the
 *       {@code {fileName,sizeBytes,sha256}} fragment web-admin merges into the
 *       {@code plugins.catalog} setting on success.</li>
 *   <li>{@code GET /api/plugins/artifacts/{pluginId}/{version}/{fileName}} - permitAll
 *       download (rule placed with the catalog's in {@code SecurityConfig}), streamed
 *       with {@code Content-Disposition: attachment} and an exact {@code Content-Length}
 *       so browsers show real download progress. The {@code InputStreamResource} body
 *       means Spring closes the storage stream after writing it.</li>
 * </ul>
 */
@RestController
public class PluginArtifactController {

    private final PluginArtifactService pluginArtifactService;

    public PluginArtifactController(PluginArtifactService pluginArtifactService) {
        this.pluginArtifactService = pluginArtifactService;
    }

    @PostMapping("/api/platform/plugins/artifacts")
    public PluginArtifactService.StoredArtifact upload(
        @RequestParam("pluginId") String pluginId,
        @RequestParam("version") String version,
        @RequestParam("file") MultipartFile file
    ) {
        return pluginArtifactService.store(pluginId, version, file);
    }

    @GetMapping("/api/plugins/artifacts/{pluginId}/{version}/{fileName}")
    public ResponseEntity<InputStreamResource> download(
        @PathVariable String pluginId,
        @PathVariable String version,
        @PathVariable String fileName
    ) {
        PluginArtifactService.ArtifactDownload download = pluginArtifactService.open(pluginId, version, fileName);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(download.sizeBytes())
            // fileName already passed the strict segment allowlist, so it is
            // header-safe verbatim (no quotes/CR/LF/%: can appear in it).
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"")
            .body(new InputStreamResource(download.stream()));
    }
}
