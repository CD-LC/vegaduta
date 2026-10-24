package com.agenticai.core.settings;

import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One agent's setting overrides: EDITOR on the agent writes (checked by {@link SettingsAgentAccess}, which also
 * refuses an agent of another tenant with a 404); a key whose {@code minRole} is TENANT_ADMIN (every security
 * key) additionally needs a tenant admin. Authorization is in {@link SettingsService}.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/settings")
public class AgentSettingsController {

    private final SettingsService service;

    public AgentSettingsController(SettingsService service) {
        this.service = service;
    }

    @PutMapping("/{key}")
    public SettingsController.WriteResponse put(
            @PathVariable UUID agentId, @PathVariable String key, @RequestBody SettingsController.ValueRequest body) {
        return SettingsController.WriteResponse.of(service.set(SettingScope.AGENT, agentId, key, body.value(), body.reason()));
    }

    @DeleteMapping("/{key}")
    public SettingsController.WriteResponse delete(
            @PathVariable UUID agentId, @PathVariable String key, @RequestParam(required = false) String reason) {
        return SettingsController.WriteResponse.of(service.reset(SettingScope.AGENT, agentId, key, reason));
    }

    /**
     * Every change to this agent's settings in one call, newest first (EDITOR on the agent; an agent of another tenant
     * is a 404). {@code since} keeps rows at or after that instant; page with {@code limit} and {@code offset}.
     */
    @GetMapping("/history")
    public SettingsController.HistoryPageResponse history(
            @PathVariable UUID agentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        return SettingsController.HistoryPageResponse.of(service.historyPage(SettingScope.AGENT, agentId, since, limit, offset));
    }
}
