package com.agenticai.core.agents.tools.code;

import com.agenticai.core.agents.tools.code.dto.CustomCodeToolRequest;
import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.agents.tools.code.dto.CustomCodeToolResponse;
import com.agenticai.core.agents.tools.code.dto.CustomCodeToolSecretsRequest;
import com.agenticai.core.agents.tools.code.dto.CustomCodeToolTestRequest;
import com.agenticai.core.secrets.SecretWriteForbiddenException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
 * CRUD + dry-run for tenant-authored {@link CustomCodeTool} rows, shaped exactly like {@code
 * UserToolScriptController} with one deliberate difference: the AUTHORIZATION is stricter. Reads
 * fall through to {@code anyRequest().authenticated()} like the scripts controller, but every
 * mutating verb here is gated to {@code ROLE_tenant-admin} in {@code SecurityConfig} - authoring a
 * real program that the platform will compile and run is a materially more privileged act than
 * authoring a SpEL expression, even with the sandbox in between. That gate is declared centrally in
 * {@code SecurityConfig}, not with {@code @PreAuthorize}, per this codebase's convention.
 */
@RestController
@RequestMapping("/api/tools/code")
public class CustomCodeToolController {

    private final CustomCodeToolService service;

    // Granular RBAC (2026-09-23): custom-tool authoring tracks MANAGE_TOOLS -
    // same scope as the OpenAPI tool-source surface. Grants bind admins too;
    // platform-admin keeps the role check (no tenant grants by design).
    private static final String PERM_MANAGE_TOOLS = "PERM_MANAGE_TOOLS";
    private static final String ROLE_PLATFORM_ADMIN = "ROLE_platform-admin";

    public CustomCodeToolController(CustomCodeToolService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<CustomCodeToolResponse>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.listAll().stream().map(CustomCodeToolResponse::from).toList(), limit, offset, 50, 200);
    }

    @PostMapping
    public ResponseEntity<CustomCodeToolResponse> create(
            @Valid @RequestBody CustomCodeToolRequest request) {
        CustomCodeTool created =
                service.create(
                        request.toolName(),
                        request.displayName(),
                        request.description(),
                        request.language(),
                        request.sourceCode(),
                        request.inputSchemaJson(),
                        request.outputDescription(),
                        request.timeoutMs(),
                        request.memoryMb());
        // 201 even when the sandbox REJECTED the program: the row really was
        // created, and the response carries status/validationError so the
        // editor can show the diagnostics. A 4xx here would imply nothing was
        // saved, which would be a lie.
        return ResponseEntity.status(HttpStatus.CREATED).body(CustomCodeToolResponse.from(created));
    }

    /**
     * Replaces the vault secrets this tool may receive.
     *
     * <p>Separate from {@code PUT /{id}} on purpose: granting a program access to a credential is a
     * different decision from editing its code, usually made by a different person, and keeping the
     * verbs apart means a routine code edit cannot quietly widen what the program can reach.
     *
     * <p><b>Workspace-admin only.</b> Only TENANT-scoped secrets ever resolve for a custom code
     * tool (see {@code CustomCodeToolService#resolveSecretsFor}), so a grant here hands
     * tenant-authored code one of the workspace's shared credentials - the same class of decision
     * as storing one, which {@code SecretVaultController} also restricts to an admin.
     */
    @PutMapping("/{id}/secrets")
    public CustomCodeToolResponse updateSecrets(
            @PathVariable UUID id,
            @Valid @RequestBody CustomCodeToolSecretsRequest request,
            Authentication authentication) {
        requireWorkspaceAdmin(authentication);
        return CustomCodeToolResponse.from(
                service.setAllowedSecretNames(id, request.allowedSecretNames()));
    }

    @PutMapping("/{id}")
    public CustomCodeToolResponse update(
            @PathVariable UUID id, @Valid @RequestBody CustomCodeToolRequest request) {
        CustomCodeTool updated =
                service.update(
                        id,
                        request.displayName(),
                        request.description(),
                        request.language(),
                        request.sourceCode(),
                        request.inputSchemaJson(),
                        request.outputDescription(),
                        request.timeoutMs(),
                        request.memoryMb());
        return CustomCodeToolResponse.from(updated);
    }

    @PatchMapping("/{id}/enabled")
    public CustomCodeToolResponse setEnabled(
            @PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return CustomCodeToolResponse.from(service.setEnabled(id, enabled));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Dry run. Returns 200 with the tool's own result JSON, or with an {@code {"error": ...}} body
     * when the program itself failed - "it threw" is a normal outcome of trying code out, not an
     * HTTP error. A sandbox outage is still a real 502 (see {@code
     * ToolSandboxUnavailableException}).
     */
    @PostMapping("/{id}/test")
    public Map<String, String> test(
            @PathVariable UUID id,
            @RequestBody(required = false) CustomCodeToolTestRequest request) {
        return Map.of("resultJson", service.test(id, request == null ? null : request.input()));
    }

    /**
     * Re-check the stored source against the sandbox without editing it - see {@code
     * CustomCodeToolService#revalidate}.
     */
    @PostMapping("/{id}/revalidate")
    public CustomCodeToolResponse revalidate(@PathVariable UUID id) {
        return CustomCodeToolResponse.from(service.revalidate(id));
    }

    /**
     * Authorities are the literal Keycloak role strings the rest of the app matches on (see {@code
     * SecurityConfig}). Duplicated from {@code SecretVaultController} rather than shared because
     * these live in different modules and a one-method utility crossing that boundary would be a
     * worse dependency than six lines - but the two must agree.
     */
    private void requireWorkspaceAdmin(Authentication authentication) {
        boolean admin =
                authentication != null
                        && authentication.getAuthorities() != null
                        && authentication.getAuthorities().stream()
                                .map(GrantedAuthority::getAuthority)
                                .anyMatch(
                                        a ->
                                                PERM_MANAGE_TOOLS.equals(a)
                                                        || ROLE_PLATFORM_ADMIN.equals(a));
        if (!admin) {
            throw new SecretWriteForbiddenException();
        }
    }
}
