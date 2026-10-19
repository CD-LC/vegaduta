package com.agenticai.core.tenancy;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.dto.AiModelPreferenceResponse;
import com.agenticai.core.tenancy.dto.UpdateAiModelPreferenceRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Backs the "AI Model Control" settings page - per-tenant, per-feature
 * preferred provider/model overrides. See {@link AiModelFeature} for the
 * catalog of feature rows this always returns and, importantly, which one
 * ({@link AiModelFeature#DEFAULT}) is actually wired into a live routing
 * decision today ({@code com.agenticai.core.chat.gateway.LlmGatewayService#resolveClient}).
 *
 * <p>Same trust tier as {@code TenantSettingsController}: GET is readable by
 * any authenticated tenant member (falls through to {@code
 * anyRequest().authenticated()} in {@code SecurityConfig}), PUT/DELETE are
 * {@code ROLE_tenant-admin} only - changing which model a tenant's AI
 * features actually run on is an account-level decision, not a per-member
 * one, same posture the {@code usePlatformLlm}/branding writes on that
 * controller already have.
 */
@RestController
@RequestMapping("/api/tenant/ai-model-preferences")
@Profile("security")
public class TenantAiModelPreferenceController {

    private final TenantAiModelPreferenceService preferenceService;

    public TenantAiModelPreferenceController(TenantAiModelPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @GetMapping
    public List<AiModelPreferenceResponse> get() {
        return toResponse(preferenceService.getAll(tenantId()));
    }

    /**
     * Upserts the override for one feature. {@code providerName}/{@code
     * model} both null is rejected here (400) - use {@code DELETE} to clear
     * a feature back to "no override," so a PUT body is never ambiguous
     * about intent.
     */
    @PutMapping("/{featureKey}")
    public List<AiModelPreferenceResponse> update(
        @PathVariable String featureKey, @RequestBody UpdateAiModelPreferenceRequest request
    ) {
        if (request.providerName() == null && request.model() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "providerName and model must both be set to save an override - use DELETE to clear one."
            );
        }
        AiModelPreference preference = new AiModelPreference(request.providerName(), request.model());
        return toResponse(preferenceService.upsert(tenantId(), featureKey, preference));
    }

    @DeleteMapping("/{featureKey}")
    public List<AiModelPreferenceResponse> clear(@PathVariable String featureKey) {
        return toResponse(preferenceService.upsert(tenantId(), featureKey, null));
    }

    /**
     * One row per known {@link AiModelFeature} (so the settings page always
     * has a full, stable list to render), each filled in with the stored
     * override if one exists. A stored key that names no known {@code
     * AiModelFeature} (e.g. a feature retired from the enum after a tenant
     * already configured it) is appended at the end under its raw key with
     * no label, rather than silently dropped - the tenant's override is
     * still real and still applies via {@code TenantAiModelPreferenceService
     * #resolve}, it just predates this feature catalog.
     */
    private List<AiModelPreferenceResponse> toResponse(Map<String, AiModelPreference> stored) {
        List<AiModelPreferenceResponse> rows = new ArrayList<>();
        for (AiModelFeature feature : AiModelFeature.values()) {
            AiModelPreference preference = stored.get(feature.key());
            rows.add(new AiModelPreferenceResponse(
                feature.key(), feature.label(),
                preference != null ? preference.providerName() : null,
                preference != null ? preference.model() : null
            ));
        }
        for (Map.Entry<String, AiModelPreference> entry : stored.entrySet()) {
            if (AiModelFeature.fromKey(entry.getKey()) == null) {
                rows.add(new AiModelPreferenceResponse(
                    entry.getKey(), null, entry.getValue().providerName(), entry.getValue().model()
                ));
            }
        }
        return rows;
    }

    private String tenantId() {
        return TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException(
                "No TenantContext for an authenticated /api/tenant/ai-model-preferences request - SecurityConfig should have blocked this"));
    }
}
