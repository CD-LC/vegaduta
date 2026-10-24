package com.agenticai.core.agents.limits;

import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.agents.runtime.AgentRuntimeSettings;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.settings.SettingScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant admin's defaults for the runtime limits of every agent of the tenant: an agent's own override
 * still wins. Tenant-admin only. A PUT replaces the whole layer (a field left out goes back to the platform
 * value); values outside {@code app.agent-runtime.bounds.*} are refused with a 400. {@code effective} in the
 * view is for an agent with no override of its own on the SEMI_AUTONOMOUS tier.
 */
@RestController
@RequestMapping("/api/agent-runtime/limits")
@PreAuthorize(TenantRuntimeLimitsController.TENANT_ADMIN)
public class TenantRuntimeLimitsController {

    static final String TENANT_ADMIN = "hasAnyAuthority('ROLE_tenant-admin', 'ROLE_platform-admin')";

    private final TenantRuntimeLimitsService service;
    private final AgentRuntimeSettingsResolver resolver;

    private final RuntimeLimitsHistory history;

    public TenantRuntimeLimitsController(TenantRuntimeLimitsService service, AgentRuntimeSettingsResolver resolver) {
        this(service, resolver, null);
    }

    @Autowired
    public TenantRuntimeLimitsController(
            TenantRuntimeLimitsService service, AgentRuntimeSettingsResolver resolver, RuntimeLimitsHistory history) {
        this.service = service;
        this.resolver = resolver;
        this.history = history;
    }

    @GetMapping
    public RuntimeLimitsView get() {
        return view(service.current());
    }

    @PutMapping
    public RuntimeLimitsView put(@RequestBody(required = false) RuntimeLimits body) {
        RuntimeLimits limits = body == null ? RuntimeLimits.none() : body;
        resolver.validate(limits);
        RuntimeLimits before = history == null ? null : service.current();
        RuntimeLimits stored = service.replace(limits);
        recordHistory(before, stored);
        return view(stored);
    }

    @DeleteMapping
    public ResponseEntity<Void> clear() {
        RuntimeLimits before = history == null ? null : service.current();
        service.replace(RuntimeLimits.none());
        recordHistory(before, RuntimeLimits.none());
        return ResponseEntity.noContent().build();
    }

    private void recordHistory(RuntimeLimits before, RuntimeLimits after) {
        if (history != null) {
            history.record(SettingScope.TENANT, null, before, after, "/api/agent-runtime/limits");
        }
    }

    private RuntimeLimitsView view(RuntimeLimits tenant) {
        AgentRuntimeSettings effective =
                resolver.resolve(AgentAutonomyTier.SEMI_AUTONOMOUS, tenant, RuntimeLimits.none(), null);
        return new RuntimeLimitsView(tenant, RuntimeLimitsView.Effective.of(effective), resolver.bounds());
    }
}
