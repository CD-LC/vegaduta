package com.agenticai.core.agents.tools.openapi;

import com.agenticai.core.agents.tools.openapi.dto.OpenApiPreviewRequest;
import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.agents.tools.openapi.dto.OpenApiPreviewResponse;
import com.agenticai.core.agents.tools.openapi.dto.OpenApiToolResponse;
import com.agenticai.core.agents.tools.openapi.dto.OpenApiToolSourceRequest;
import com.agenticai.core.agents.tools.openapi.dto.OpenApiToolSourceResponse;
import com.agenticai.core.agents.tools.openapi.dto.OpenApiToolTestRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Import-from-OpenAPI: preview, create, list, refresh, delete a source and list/toggle/test its
 * generated tools. Auth posture (see SecurityConfig): reads are open to any signed-in tenant member
 * like {@code /api/tools/scripts}; writes - including preview, which makes a server-side fetch of a
 * caller-supplied URL - are tenant-admin, the same gate {@code /api/mcp/registry} and {@code
 * /api/tools/code} apply to "register something external for every agent in the workspace to call,
 * with a stored credential attached".
 */
@RestController
@RequestMapping("/api/tools/openapi")
public class OpenApiToolSourceController {

    private final OpenApiToolSourceService service;

    public OpenApiToolSourceController(OpenApiToolSourceService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<OpenApiToolSourceResponse>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.listAll().stream().map(this::toResponse).toList(), limit, offset, 50, 200);
    }

    /** Parse without persisting - the console's operation picker. */
    @PostMapping("/preview")
    public OpenApiPreviewResponse preview(@RequestBody OpenApiPreviewRequest request) {
        OpenApiToolSourceService.Preview preview =
                service.preview(
                        request.name(),
                        request.toolPrefix(),
                        request.specUrl(),
                        request.specText());
        return OpenApiPreviewResponse.from(
                preview.spec(), preview.suggestedBaseUrl(), preview.toolPrefix());
    }

    @PostMapping
    public ResponseEntity<OpenApiToolSourceResponse> create(
            @Valid @RequestBody OpenApiToolSourceRequest request) {
        OpenApiToolSource created =
                service.create(
                        request.name(),
                        request.toolPrefix(),
                        request.specUrl(),
                        request.specText(),
                        request.baseUrl(),
                        request.authType(),
                        request.authHeaderName(),
                        request.secret(),
                        request.oauth2TokenUrl(),
                        request.oauth2ClientId(),
                        request.oauth2Scope(),
                        request.oauth2TokenStyle(),
                        request.enabledOperationKeys());
        // After the import transaction has committed - see the service javadoc.
        service.declareClassifications(created.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @PutMapping("/{id}")
    public OpenApiToolSourceResponse update(
            @PathVariable UUID id, @Valid @RequestBody OpenApiToolSourceRequest request) {
        OpenApiToolSource updated =
                service.updateSettings(
                        id,
                        request.name(),
                        request.baseUrl(),
                        request.authType(),
                        request.authHeaderName(),
                        request.secret(),
                        request.oauth2TokenUrl(),
                        request.oauth2ClientId(),
                        request.oauth2Scope(),
                        request.oauth2TokenStyle());
        // Auth mode may have changed the sensitivity of every tool.
        service.declareClassifications(updated.getId());
        return toResponse(updated);
    }

    @PostMapping("/{id}/refresh")
    public OpenApiToolSourceResponse refresh(@PathVariable UUID id) {
        OpenApiToolSource refreshed = service.refresh(id);
        if (refreshed.getLastError() == null) {
            service.declareClassifications(refreshed.getId());
        }
        return toResponse(refreshed);
    }

    @PatchMapping("/{id}/enabled")
    public OpenApiToolSourceResponse setEnabled(
            @PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return toResponse(service.setEnabled(id, enabled));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/tools")
    public ResponseEntity<List<OpenApiToolResponse>> listTools(@PathVariable UUID id, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.listTools(id).stream().map(OpenApiToolResponse::from).toList(), limit, offset, 100, 500);
    }

    @PatchMapping("/{id}/tools/{toolId}/enabled")
    public OpenApiToolResponse setToolEnabled(
            @PathVariable UUID id,
            @PathVariable UUID toolId,
            @RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return OpenApiToolResponse.from(service.setToolEnabled(id, toolId, enabled));
    }

    /**
     * Dry-run against the real API - see {@code OpenApiToolSourceService#test}. 200 with the same
     * {@code {status, body}} / {@code {error}} JSON the agent would receive, never 4xx/5xx for an
     * API-side outcome: "the API said 404" is a normal, expected result of trying a tool out.
     */
    @PostMapping("/{id}/tools/{toolId}/test")
    public Map<String, String> test(
            @PathVariable UUID id,
            @PathVariable UUID toolId,
            @RequestBody OpenApiToolTestRequest request) {
        return Map.of("resultJson", service.test(id, toolId, request.inputJson()));
    }

    private OpenApiToolSourceResponse toResponse(OpenApiToolSource source) {
        return OpenApiToolSourceResponse.from(
                source,
                service.countTools(source.getId()),
                service.countEnabledTools(source.getId()));
    }
}
