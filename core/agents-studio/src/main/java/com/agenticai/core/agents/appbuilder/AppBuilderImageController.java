package com.agenticai.core.agents.appbuilder;

import com.agenticai.core.agentspi.ImageGenerator;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantStatusGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/internal/app-builder/generate-image} - image/logo generation for generated apps.
 * Same service-to-service posture as {@link AppBuilderCompletionController} (X-Service-Token +
 * X-Tenant-Id, fail closed, auth before body parsing). Reuses the existing {@link
 * ImageGenerationOrchestratorService} (tenant BYOK OpenAI/Vertex, else the platform pool), so
 * provider resolution and per-tenant quota are unchanged. When no provider can serve images,
 * answers 409 with {@code code=no_image_provider} rather than faking a result.
 */
@RestController
@Profile("security")
public class AppBuilderImageController {

    private static final int MAX_PROMPT_CHARS = 4000;

    private final ImageGenerator orchestrator;
    private final AppBuilderServiceAuth serviceAuth;
    private final ObjectMapper objectMapper;

    public AppBuilderImageController(
            ImageGenerator orchestrator,
            @Value("${app.app-builder.service-token:}") String serviceToken,
            @Value("${app.app-builder.allowed-tenants:}") String allowedTenants,
            ObjectMapper objectMapper,
            ObjectProvider<TenantStatusGuard> tenantStatusGuard) {
        this.orchestrator = orchestrator;
        this.serviceAuth =
                new AppBuilderServiceAuth(serviceToken, allowedTenants, tenantStatusGuard);
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/internal/app-builder/generate-image")
    public ResponseEntity<Map<String, Object>> generateImage(
            @RequestHeader(value = "X-Service-Token", required = false) String presentedToken,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestBody(required = false) String rawBody) {
        serviceAuth.authenticate(presentedToken);
        if (tenantId == null || tenantId.isBlank()) {
            throw new AppBuilderTokenMissingException("Missing X-Tenant-Id header.");
        }
        serviceAuth.authorizeTenantClaim(tenantId);
        String prompt;
        String size;
        try {
            JsonNode node = objectMapper.readTree(rawBody == null ? "" : rawBody);
            prompt = node.path("prompt").asText("");
            size = node.hasNonNull("size") ? node.get("size").asText() : null;
        } catch (Exception e) {
            throw new AppBuilderValidationException(
                    "The request body is missing or not valid JSON for this endpoint.");
        }
        if (prompt.isBlank() || prompt.length() > MAX_PROMPT_CHARS) {
            throw new AppBuilderValidationException(
                    "prompt is required and must be at most " + MAX_PROMPT_CHARS + " characters.");
        }

        TenantContext.set(
                TenantContext.of(tenantId, "app-builder-service", Set.of(), "app-builder"));
        try {
            serviceAuth.requireUsableTenant(tenantId);
            ImageGenerator.GeneratedImage r = orchestrator.generate(prompt, size);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contentBase64", Base64.getEncoder().encodeToString(r.bytes()));
            body.put("contentType", r.contentType());
            body.put("model", r.model());
            body.put("provider", r.provider());
            return ResponseEntity.ok(body);
        } catch (ImageGenerator.NotConfiguredException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            body.put("code", "no_image_provider");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        } finally {
            TenantContext.clear();
        }
    }
}
