package com.agenticai.core.settings;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The settings catalog API (contract: web package settings, {@code contracts/consumers/web/manual.contract.json}).
 * Reads are open to every member (the catalog and the effective values carry no secret); tenant-level writes are
 * tenant-admin only ({@code @PreAuthorize}, and {@link SettingsService} checks again); the history of tenant scope is
 * tenant-admin, of agent scope EDITOR on the agent. Agent-level writes are in {@link AgentSettingsController}.
 *
 * <p>Errors (body {@code {code, key, message}}, see {@link SettingsExceptionHandler}): 400 {@code SETTING_INVALID} (value,
 * missing reason, a security value weaker than the one it inherits), 404 {@code SETTING_UNKNOWN} (and an agent of another
 * tenant), 403 (missing role), 409 {@code SETTING_LOCKED} (the operator pinned the key) and 409 {@code SETTING_CONFLICT}
 * (another writer changed the same key at the same moment; reload and retry). A reset of a {@code high} or
 * {@code security} key needs a typed {@code reason}, like a PUT.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    static final String TENANT_ADMIN = "hasAnyAuthority('ROLE_tenant-admin', 'ROLE_platform-admin')";

    /** Body of the PUT endpoints. {@code value} is a JSON number, boolean or string. */
    public record ValueRequest(Object value, String reason) {}

    /** {@code GET /catalog}. */
    public record CatalogResponse(List<CatalogEntry> entries) {}

    /** {@code GET /effective}. */
    public record EffectiveResponse(Map<String, SettingValue> values) {}

    /** The answer of a PUT or DELETE: the new effective value for the context, the stored version (null = nothing changed). */
    public record WriteResponse(String key, SettingValue effective, Integer version, boolean unchanged) {

        static WriteResponse of(SettingsService.Applied a) {
            return new WriteResponse(a.key(), a.effective(), a.version(), a.unchanged());
        }
    }

    /** {@code GET /history} and {@code GET /api/agents/{id}/settings/history}: one page of the history of every key. */
    public record HistoryPageResponse(List<SettingHistoryRow> entries, long total, int offset, int limit, boolean hasMore) {

        static HistoryPageResponse of(SettingsService.HistoryPage p) {
            return new HistoryPageResponse(
                    p.entries().stream().map(SettingHistoryRow::of).toList(), p.total(), p.offset(), p.limit(), p.hasMore());
        }
    }

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping("/catalog")
    public CatalogResponse catalog() {
        return new CatalogResponse(service.registry().all().stream().map(CatalogEntry::of).toList());
    }

    @GetMapping("/effective")
    public EffectiveResponse effective(@RequestParam(required = false) UUID agentId) {
        return new EffectiveResponse(new LinkedHashMap<>(service.effective(agentId)));
    }

    @PutMapping("/tenant/{key}")
    @PreAuthorize(TENANT_ADMIN)
    public WriteResponse putTenant(@PathVariable String key, @RequestBody ValueRequest body) {
        return WriteResponse.of(service.set(SettingScope.TENANT, null, key, body.value(), body.reason()));
    }

    @DeleteMapping("/tenant/{key}")
    @PreAuthorize(TENANT_ADMIN)
    public WriteResponse deleteTenant(@PathVariable String key, @RequestParam(required = false) String reason) {
        return WriteResponse.of(service.reset(SettingScope.TENANT, null, key, reason));
    }

    @GetMapping("/{key}/history")
    public List<SettingHistoryEntry> history(
            @PathVariable String key,
            @RequestParam(defaultValue = "tenant") String scope,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) Integer limit) {
        SettingScope s =
                SettingScope.parse(scope)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope must be tenant or agent"));
        return service.history(s, agentId, key, limit);
    }

    /**
     * The history of every key in one call, newest first. {@code scope}: {@code all} (default: the workspace's own
     * changes and every agent's, tenant admin), {@code tenant} (tenant admin), {@code agent} with {@code agentId} (that
     * agent only, EDITOR on it) or {@code agent} alone (every agent's changes, tenant admin). {@code since} (ISO-8601 instant) keeps rows at or after it; page with {@code limit} and
     * {@code offset}.
     */
    @GetMapping("/history")
    public HistoryPageResponse historyAll(
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        SettingScope s = null;
        if (!"all".equalsIgnoreCase(scope)) {
            s = SettingScope.parse(scope)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope must be all, tenant or agent"));
        }
        return HistoryPageResponse.of(service.historyPage(s, agentId, since, limit, offset));
    }
}
