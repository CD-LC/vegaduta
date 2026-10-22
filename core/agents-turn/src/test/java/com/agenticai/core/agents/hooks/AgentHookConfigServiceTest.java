package com.agenticai.core.agents.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hooks.AgentHookDtos.AgentHooksResponse;
import com.agenticai.core.agents.hooks.AgentHookDtos.TenantHooksResponse;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AgentHookConfigServiceTest {

    private static final UUID AGENT = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String BLOCK_HOOK =
            "{\"hooks\":[{\"id\":\"no-delete\",\"point\":\"BEFORE_TOOL\",\"match\":{\"tool\":\"delete_*\"},\"action\":\"block\"}]}";

    private final Map<String, AgentHookConfig> store = new HashMap<>();
    private final AgentHookConfigRepository repository = mock(AgentHookConfigRepository.class);
    private final AgentService agentService = mock(AgentService.class);
    private final AuditLogService audit = mock(AuditLogService.class);

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getObject()).thenReturn(value);
        return p;
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AgentHookConfigService service(boolean enabled) {
        return new AgentHookConfigService(
                repository, provider(agentService), audit, enabled, "ops.example.com", 60_000);
    }

    @BeforeEach
    void setUp() {
        when(repository.findById(anyString()))
                .thenAnswer(i -> Optional.ofNullable(store.get((String) i.getArgument(0))));
        when(repository.save(any(AgentHookConfig.class)))
                .thenAnswer(
                        i -> {
                            AgentHookConfig c = i.getArgument(0);
                            store.put(c.getScopeKey(), c);
                            return c;
                        });
        asUser("PERM_MANAGE_GUARDRAILS");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static void asUser(String... roles) {
        TenantContext.set(TenantContext.of("tenant-a", "user-1", Set.of(roles)));
    }

    // ------------------------------------------------------------------ reading

    @Test
    void anAgentWithNoHooksReadsEmptyAndAdvertisesTheServersRules() {
        AgentHooksResponse view = service(true).getAgentHooks(AGENT);

        assertTrue(view.enabled());
        assertTrue(view.hooks().isEmpty());
        assertTrue(view.inherited().isEmpty());
        assertNull(view.updatedAt());
        assertEquals(HookSchema.MAX_HOOKS, view.schema().maxHooks());
        assertEquals(6, view.schema().points().size());
        assertTrue(view.schema().actionsByPoint().get("BEFORE_TOOL").contains("require_approval"));
        assertFalse(view.schema().actionsByPoint().get("ON_RUN_END").contains("block"));
        assertTrue(view.schema().builtinPatterns().contains("email"));
        assertTrue(view.schema().sensitivities().contains("UNCLASSIFIED"));
        verify(agentService).requireAccess(AGENT, PermissionLevel.VIEWER);
    }

    @Test
    void readingWorksWhileTheFeatureIsOffAndSaysSo() {
        assertFalse(service(false).getAgentHooks(AGENT).enabled());
        assertFalse(service(false).getTenantHooks().enabled());
    }

    // ------------------------------------------------------------------ saving

    @Test
    void savingStoresTheValidatedHooksAuditsAndReadsBack() {
        AgentHookConfigService service = service(true);

        AgentHooksResponse saved = service.putAgentHooks(AGENT, json(BLOCK_HOOK));

        assertEquals(1, saved.hooks().size());
        assertEquals("no-delete", saved.hooks().get(0).get("id"));
        assertEquals("block", saved.hooks().get(0).get("action"));
        assertEquals(1, HookSchema.parseStored(store.get(AGENT.toString()).getHooksJson()).size());
        assertEquals("user-1", store.get(AGENT.toString()).getUpdatedBy());
        verify(audit)
                .record(
                        eq("user-1"),
                        eq("agent.hooks.update"),
                        eq("agent"),
                        eq(AGENT.toString()),
                        eq(AuditOutcome.SUCCESS),
                        anyString());
        verify(agentService).requireAccess(AGENT, PermissionLevel.EDITOR);
        assertEquals(1, service.getAgentHooks(AGENT).hooks().size());
    }

    @Test
    void savingNeedsTheGuardrailsAuthorityNotJustEditAccess() {
        asUser("PERM_MANAGE_AGENTS");

        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service(true).putAgentHooks(AGENT, json(BLOCK_HOOK)));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        assertTrue(store.isEmpty());
        verify(agentService, never()).requireAccess(AGENT, PermissionLevel.EDITOR);
    }

    @Test
    void aTenantAdminAndAPlatformAdminMaySaveToo() {
        asUser("ROLE_tenant-admin");
        service(true).putAgentHooks(AGENT, json(BLOCK_HOOK));
        asUser("ROLE_platform-admin");
        service(true).putAgentHooks(AGENT, json("{\"hooks\":[]}"));

        assertEquals(0, HookSchema.parseStored(store.get(AGENT.toString()).getHooksJson()).size());
    }

    @Test
    void anAgentTheCallerCannotSeeAnswersBeforeAnyForbiddenOrConflict() {
        asUser("PERM_MANAGE_AGENTS");
        when(agentService.requireAccess(AGENT, PermissionLevel.VIEWER))
                .thenThrow(new IllegalStateException("not found"));

        assertThrows(
                IllegalStateException.class,
                () -> service(false).putAgentHooks(AGENT, json(BLOCK_HOOK)));
    }

    @Test
    void savingIsAConflictWhileTheFeatureIsOff() {
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service(false).putAgentHooks(AGENT, json(BLOCK_HOOK)));

        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertTrue(store.isEmpty());
    }

    @Test
    void theHooksFieldMustBePresentSoATypoCannotClearAnAgent() {
        service(true).putAgentHooks(AGENT, json(BLOCK_HOOK));

        assertThrows(
                IllegalArgumentException.class,
                () -> service(true).putAgentHooks(AGENT, json("{}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> service(true).putAgentHooks(AGENT, json("{\"hook\":[]}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> service(true).putAgentHooks(AGENT, json("{\"hooks\":{}}")));
        assertThrows(
                IllegalArgumentException.class, () -> service(true).putAgentHooks(AGENT, null));

        assertEquals(
                1,
                HookSchema.parseStored(store.get(AGENT.toString()).getHooksJson()).size(),
                "the saved hooks survived");
    }

    @Test
    void anInvalidHookIsRejectedAndNothingIsWritten() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service(true)
                                .putAgentHooks(
                                        AGENT,
                                        json(
                                                "{\"hooks\":[{\"id\":\"a\",\"point\":\"AFTER_MODEL\",\"action\":\"limit\",\"params\":{\"maxPerMinute\":1}}]}")));

        assertTrue(store.isEmpty());
        verify(repository, never()).save(any(AgentHookConfig.class));
    }

    @Test
    void aWebhookMustPointAtAnAllowListedHost() {
        String body =
                "{\"hooks\":[{\"id\":\"vet\",\"point\":\"BEFORE_TOOL\",\"action\":\"webhook\",\"params\":{\"url\":\"%s\"}}]}";

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service(true)
                                .putAgentHooks(
                                        AGENT,
                                        json(String.format(body, "https://evil.example.net/x"))));
        // The operator's list (config) is honoured...
        service(true).putAgentHooks(AGENT, json(String.format(body, "https://ops.example.com/x")));
        // ...and so is the workspace's own, once an admin added the host.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service(true)
                                .putAgentHooks(
                                        AGENT,
                                        json(String.format(body, "https://tenant.example.org/x"))));
        service(true)
                .putTenantHooks(json("{\"hooks\":[],\"webhookHosts\":[\"tenant.example.org\"]}"));
        service(true)
                .putAgentHooks(AGENT, json(String.format(body, "https://tenant.example.org/x")));
    }

    // ----------------------------------------------------------- workspace defaults

    @Test
    void workspaceDefaultsAreSavedWithTheirWebhookHostsAndAbsentHostsAreKept() {
        AgentHookConfigService service = service(true);

        TenantHooksResponse saved =
                service.putTenantHooks(
                        json(
                                "{\"hooks\":[{\"id\":\"audit\",\"point\":\"ON_RUN_END\",\"action\":\"log\"}],\"webhookHosts\":[\"Hooks.Example.org\",\"*.corp.example.org\",\"hooks.example.org\"]}"));

        assertEquals(1, saved.hooks().size());
        assertEquals(
                List.of("hooks.example.org", "*.corp.example.org"),
                saved.webhookHosts(),
                "normalised and de-duplicated");
        assertEquals(List.of("ops.example.com"), saved.platformWebhookHosts());

        TenantHooksResponse again = service.putTenantHooks(json("{\"hooks\":[]}"));

        assertTrue(again.hooks().isEmpty());
        assertEquals(
                List.of("hooks.example.org", "*.corp.example.org"),
                again.webhookHosts(),
                "no webhookHosts field: the list is kept");
        verify(audit, times(2))
                .record(
                        eq("user-1"),
                        eq("agent.hooks.defaults.update"),
                        eq("tenant"),
                        eq("tenant-a"),
                        eq(AuditOutcome.SUCCESS),
                        anyString());
    }

    @Test
    void workspaceDefaultsNeedTheAuthorityAndRejectBadHosts() {
        for (String bad :
                List.of(
                        "\"https://x.example.com\"",
                        "\"x.example.com:8443\"",
                        "\"x\"",
                        "\"*\"",
                        "\"a b.example.com\"",
                        "1")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            service(true)
                                    .putTenantHooks(
                                            json("{\"hooks\":[],\"webhookHosts\":[" + bad + "]}")),
                    bad);
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service(true)
                                .putTenantHooks(
                                        json("{\"hooks\":[],\"webhookHosts\":\"x.example.com\"}")));

        asUser("PERM_VIEW_AUDIT_LOG");
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service(true).putTenantHooks(json("{\"hooks\":[]}")));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
    }

    @Test
    void theWorkspaceDefaultsEndpointNeedsTheGovernanceAuthorityEvenToRead() {
        asUser("PERM_VIEW_AUDIT_LOG");
        ResponseStatusException e =
                assertThrows(ResponseStatusException.class, () -> service(true).getTenantHooks());
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
    }

    @Test
    void aReaderWithoutTheGovernanceAuthoritySeesWebhookUrlsCutToTheirHost() {
        AgentHookConfigService service = service(true);
        String hook =
                "{\"hooks\":[{\"id\":\"vet\",\"point\":\"BEFORE_TOOL\",\"action\":\"webhook\","
                        + "\"params\":{\"url\":\"https://ops.example.com/hook/abc?token=s3cret\"}}]}";
        service.putAgentHooks(AGENT, json(hook));
        assertEquals(
                "https://ops.example.com/hook/abc?token=s3cret",
                ((Map<?, ?>) service.getAgentHooks(AGENT).hooks().get(0).get("params")).get("url"),
                "the manager sees it whole");

        asUser("PERM_VIEW_AUDIT_LOG");
        String seen =
                (String)
                        ((Map<?, ?>) service.getAgentHooks(AGENT).hooks().get(0).get("params"))
                                .get("url");

        assertEquals("https://ops.example.com/...", seen);
    }

    @Test
    void savingAgentHooksHonoursTheFreeTierEditLock() {
        service(true).putAgentHooks(AGENT, json(BLOCK_HOOK));

        verify(agentService).assertEditable(any());
    }

    @Test
    void anAgentSeesTheWorkspaceDefaultsAsInherited() {
        AgentHookConfigService service = service(true);
        service.putTenantHooks(
                json(
                        "{\"hooks\":[{\"id\":\"audit\",\"point\":\"ON_RUN_END\",\"action\":\"log\"}]}"));

        AgentHooksResponse view = service.getAgentHooks(AGENT);

        assertTrue(view.hooks().isEmpty());
        assertEquals(1, view.inherited().size());
        assertEquals("audit", view.inherited().get(0).get("id"));
    }

    // ------------------------------------------------------------ runtime resolution

    @Test
    void effectiveIsTheWorkspaceDefaultsThenTheAgentsOwn() {
        AgentHookConfigService service = service(true);
        service.putTenantHooks(
                json(
                        "{\"hooks\":[{\"id\":\"audit\",\"point\":\"ON_RUN_END\",\"action\":\"log\"}],\"webhookHosts\":[\"tenant.example.org\"]}"));
        service.putAgentHooks(AGENT, json(BLOCK_HOOK));

        HookSet set = service.effective(AGENT);

        assertEquals(2, set.hooks().size());
        assertEquals(
                "tenant:audit",
                set.hooks().get(0).key(),
                "workspace defaults always evaluate first");
        assertEquals("agent:no-delete", set.hooks().get(1).key());
        assertEquals(List.of("tenant.example.org"), set.webhookHosts());
    }

    @Test
    void effectiveIsCachedAndASaveInvalidatesIt() {
        AgentHookConfigService service = service(true);
        service.putAgentHooks(AGENT, json(BLOCK_HOOK));
        HookSet first = service.effective(AGENT);
        org.mockito.Mockito.clearInvocations(repository);

        HookSet second = service.effective(AGENT);

        assertSame(first, second);
        verify(repository, never()).findById(anyString());

        service.putAgentHooks(AGENT, json("{\"hooks\":[]}"));
        assertTrue(
                service.effective(AGENT).isEmpty(), "the save took effect at once on this replica");
    }

    @Test
    void aWorkspaceDefaultsSaveInvalidatesEveryAgentsCache() {
        AgentHookConfigService service = service(true);
        assertTrue(service.effective(AGENT).isEmpty());

        service.putTenantHooks(
                json(
                        "{\"hooks\":[{\"id\":\"audit\",\"point\":\"ON_RUN_END\",\"action\":\"log\"}]}"));

        assertEquals(1, service.effective(AGENT).hooks().size());
    }

    @Test
    void nothingIsReadWhileTheFeatureIsOffOrWithoutATenant() {
        assertTrue(service(false).effective(AGENT).isEmpty());
        TenantContext.clear();
        assertTrue(service(true).effective(AGENT).isEmpty());
        assertTrue(service(true).effective(null).isEmpty());
        verify(repository, never()).findById(anyString());
    }

    @Test
    void aFailedReloadKeepsTheLastKnownSetInsteadOfDroppingGovernance() {
        AgentHookConfigService stale =
                new AgentHookConfigService(repository, provider(agentService), audit, true, "", 0);
        stale.putAgentHooks(AGENT, json(BLOCK_HOOK));
        assertEquals(1, stale.effective(AGENT).hooks().size());

        when(repository.findById(anyString())).thenThrow(new IllegalStateException("db down"));

        assertEquals(
                1,
                stale.effective(AGENT).hooks().size(),
                "ttl 0 forces a reload, which fails; the last good set is used");
        verify(repository, atLeastOnce()).findById(anyString());
    }

    @Test
    void corruptStoredHooksAreSkippedNotFatal() {
        AgentHookConfig corrupt = AgentHookConfig.forAgent(AGENT);
        corrupt.update("{not json", null, "someone");
        store.put(AGENT.toString(), corrupt);

        assertTrue(service(true).effective(AGENT).isEmpty());
        assertTrue(service(true).getAgentHooks(AGENT).hooks().isEmpty());
    }

    // -------------------------------------------------------------------- helpers

    @Test
    void hostListsAreParsedStrictly() {
        assertEquals(
                List.of("a.example.com", "*.b.example.com"),
                AgentHookConfigService.parseHostCsv(
                        " A.example.com, *.b.example.com ,,a.example.com"));
        assertTrue(AgentHookConfigService.parseHostCsv("").isEmpty());
        assertTrue(AgentHookConfigService.parseHostCsv(null).isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentHookConfigService.parseHostArray(json("[\"localhost\"]")));
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 21; i++) {
            many.append(i == 0 ? "" : ",").append("\"h").append(i).append(".example.com\"");
        }
        many.append("]");
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentHookConfigService.parseHostArray(json(many.toString())));
    }
}
