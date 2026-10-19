package com.agenticai.core.knowledge.connector;

import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ListPaging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/knowledge/connectors} - CRUD + manual sync for the live
 * connector sources (Notion, Google Drive, Confluence, SharePoint, web
 * crawl). Authenticated tenant-member surface like the rest of
 * {@code /api/knowledge/**}; tenant scoping is implicit in the JPA
 * repositories, which resolve inside the caller's own schema.
 */
@RestController
@RequestMapping("/api/knowledge/connectors")
@Profile("knowledge")
public class KnowledgeConnectorController {

    private final KnowledgeConnectorService service;

    public KnowledgeConnectorController(KnowledgeConnectorService service) {
        this.service = service;
    }

    public record ConnectorUpsertRequest(
        @NotNull UUID collectionId,
        @NotBlank @Size(max = 32) String type,
        @NotBlank @Size(max = 255) String name,
        @NotNull Map<String, String> config
    ) {}

    public record ConnectorUpdateRequest(
        @Size(max = 255) String name,
        Boolean enabled,
        Map<String, String> config
    ) {}

    @GetMapping
    public ResponseEntity<List<ConnectorResponse>> list(@RequestParam UUID collectionId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            service.list(collectionId).stream()
            .map(c -> ConnectorResponse.from(c, service.redactedConfig(c)))
            .toList(),
            limit, offset, 50, 500);
    }

    @PostMapping
    public ConnectorResponse create(@Valid @RequestBody ConnectorUpsertRequest request) {
        ConnectorType type = ConnectorType.parse(request.type());
        if (type == null) {
            throw new IllegalArgumentException(
                "Unknown connector type '" + request.type()
                    + "' - expected notion, google_drive, confluence, sharepoint, or web_crawl");
        }
        KnowledgeConnector c = service.create(
            request.collectionId(), type, request.name(), request.config(), subject());
        return ConnectorResponse.from(c, service.redactedConfig(c));
    }

    @PutMapping("/{id}")
    public ConnectorResponse update(@PathVariable UUID id, @Valid @RequestBody ConnectorUpdateRequest request) {
        KnowledgeConnector c = service.update(id, request.name(), request.enabled(), request.config());
        return ConnectorResponse.from(c, service.redactedConfig(c));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/sync")
    public KnowledgeConnectorService.ConnectorSyncResult sync(@PathVariable UUID id) {
        return service.sync(id);
    }

    private static String subject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse("unknown");
    }
}
