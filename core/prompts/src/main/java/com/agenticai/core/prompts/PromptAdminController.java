package com.agenticai.core.prompts;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The prompt registry's admin API: list every key with its effective value and where it comes
 * from, read the history, create / update / revert / switch off an override at a scope, and
 * validate a draft. Tenant-admin only (the personal install's owner holds that role); the platform
 * scope additionally needs {@code ROLE_platform-admin}, enforced in {@link PromptOverrideService}.
 * A tenant admin can only reach their own tenant's rows: they live in that tenant's schema.
 *
 * <p>{@code scope} in a path is {@code platform}, {@code tenant} or {@code agent}; agent scope also
 * takes {@code agentId}. {@code locale} defaults to the configured default locale.
 */
@RestController
@RequestMapping("/api/prompts")
@PreAuthorize(PromptAdminController.TENANT_ADMIN)
public class PromptAdminController {

    static final String TENANT_ADMIN = "hasAnyAuthority('ROLE_tenant-admin', 'ROLE_platform-admin')";

    /** Body of {@code PUT /{key}/overrides/{scope}}. */
    public record OverrideRequest(
            String text, Boolean enabled, boolean acknowledgeRisk, String reason, Integer expectedVersion) {}

    /** Body of {@code POST /{key}/overrides/{scope}/restore}. */
    public record RestoreRequest(int toVersion, boolean acknowledgeRisk, String reason) {}

    /** Body of {@code POST /{key}/validate}. */
    public record ValidateRequest(String text, Boolean enabled) {}

    /**
     * Offsets above this return an empty page (the same bound as the other capped list endpoints; the
     * tenancy module's {@code ListPaging} cannot be used here, this module does not depend on it).
     */
    private static final long MAX_OFFSET = 1_000_000_000L;

    private final PromptOverrideService service;
    private final PromptProperties properties;

    public PromptAdminController(PromptOverrideService service, PromptProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /**
     * The registry, one row per key. Optional {@code limit} (clamped to {@code app.prompts.list-max-limit}) and
     * {@code offset} window it; no {@code limit} is the whole registry, as before paging existed.
     * {@code X-Total-Count} and {@code X-Has-More} describe the filtered list.
     */
    @GetMapping
    public ResponseEntity<List<PromptOverrideService.PromptView>> list(
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) String family,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        List<PromptOverrideService.PromptView> all = service.list(agentId, locale, family, query);
        int size = limit == null ? Integer.MAX_VALUE : Math.min(Math.max(limit, 1), properties.getListMaxLimit());
        long skip = offset == null ? 0 : Math.min(Math.max(offset, 0), MAX_OFFSET);
        int from = (int) Math.min(skip, all.size());
        int to = (int) Math.min((long) from + size, all.size());
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(all.size()))
                .header("X-Has-More", String.valueOf(to < all.size()))
                .body(all.subList(from, to));
    }

    @GetMapping("/{key}")
    public PromptOverrideService.PromptView get(
            @PathVariable String key, @RequestParam(required = false) UUID agentId, @RequestParam(required = false) String locale) {
        return service.get(key, agentId, locale);
    }

    @GetMapping("/{key}/history")
    public List<PromptHistoryEntry> history(
            @PathVariable String key,
            @RequestParam(defaultValue = "tenant") String scope,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) Integer limit) {
        return service.history(scope(scope), agentId, key, locale, limit);
    }

    @PutMapping("/{key}/overrides/{scope}")
    public PromptOverrideService.Applied put(
            @PathVariable String key,
            @PathVariable String scope,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) String locale,
            @RequestBody OverrideRequest body) {
        return service.put(
                new PromptOverrideService.Write(
                        scope(scope),
                        agentId,
                        key,
                        locale,
                        body.text(),
                        body.enabled() == null || body.enabled(),
                        body.acknowledgeRisk(),
                        body.reason(),
                        body.expectedVersion()));
    }

    @DeleteMapping("/{key}/overrides/{scope}")
    public PromptOverrideService.Applied revert(
            @PathVariable String key,
            @PathVariable String scope,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) Integer expectedVersion) {
        return service.revert(scope(scope), agentId, key, locale, reason, expectedVersion);
    }

    @PostMapping("/{key}/overrides/{scope}/restore")
    public PromptOverrideService.Applied restore(
            @PathVariable String key,
            @PathVariable String scope,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) String locale,
            @RequestBody RestoreRequest body) {
        return service.restore(scope(scope), agentId, key, locale, body.toVersion(), body.acknowledgeRisk(), body.reason());
    }

    @PostMapping("/{key}/validate")
    public PromptOverrideService.Validation validate(@PathVariable String key, @RequestBody ValidateRequest body) {
        return service.validate(key, body.text(), body.enabled() == null || body.enabled());
    }

    private static PromptScope scope(String value) {
        return PromptScope.parse(value)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "scope must be one of "
                                                + List.of(PromptScope.values()).stream()
                                                        .map(s -> s.name().toLowerCase(Locale.ROOT))
                                                        .toList()));
    }
}
