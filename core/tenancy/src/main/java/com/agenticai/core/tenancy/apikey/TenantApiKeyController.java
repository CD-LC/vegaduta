package com.agenticai.core.tenancy.apikey;

import com.agenticai.core.tenancy.apikey.dto.TenantApiKeyCreateRequest;
import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.tenancy.apikey.dto.TenantApiKeyCreatedResponse;
import com.agenticai.core.tenancy.apikey.dto.TenantApiKeyResponse;
import com.agenticai.core.tenancy.apikey.dto.TenantApiKeyToggleRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant REST API keys (V483) - the headless-automation credential: a CI
 * pipeline or partner system calls {@code /api/**} with {@code
 * Authorization: Bearer vdak_...} instead of an OIDC login. The key
 * authenticates through the normal security chain ({@code ApiKeyAuthFilter}
 * -&gt; {@code ApiKeyAuthenticationToken}) with exactly the {@code PERM_*}
 * authorities minted onto it.
 *
 * <p>Every route is {@code PERM_MANAGE_USERS} (see {@code
 * TenantFeatureRules}) - issuing a credential that can hold permissions IS
 * permission administration. Writes additionally pass the service-layer
 * gates: developer-API plan entitlement and the issuer-subset rule (a key
 * can never carry a scope its issuer didn't hold).
 */
@RestController
@RequestMapping("/api/tenant/api-keys")
public class TenantApiKeyController {

    private final TenantApiKeyService service;

    public TenantApiKeyController(TenantApiKeyService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<TenantApiKeyResponse>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.listAll(), limit, offset, 50, 200);
    }

    @PostMapping
    public ResponseEntity<TenantApiKeyCreatedResponse> create(
            @Valid @RequestBody TenantApiKeyCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    /**
     * Mints a fresh secret for this same key (same scopes) and immediately
     * invalidates the old one - see {@link TenantApiKeyService#rotate}.
     */
    @PostMapping("/{id}/rotate")
    public TenantApiKeyCreatedResponse rotate(@PathVariable UUID id) {
        return service.rotate(id);
    }

    @PatchMapping("/{id}")
    public TenantApiKeyResponse toggle(
            @PathVariable UUID id, @Valid @RequestBody TenantApiKeyToggleRequest request) {
        return service.setEnabled(id, request.enabled());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        service.revoke(id);
        return ResponseEntity.noContent().build();
    }
}
