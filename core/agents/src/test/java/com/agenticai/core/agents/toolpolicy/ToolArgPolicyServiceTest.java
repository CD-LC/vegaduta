package com.agenticai.core.agents.toolpolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.toolpolicy.ToolArgPolicyDtos.PoliciesResponse;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.guardrails.toolargs.ToolArgPolicyEvaluator;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ToolArgPolicyServiceTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String DOC =
            "{\"policies\":[{\"tool\":\"fetch_web_page\",\"rules\":"
                    + "[{\"path\":\"url\",\"urlHostIn\":[\"Example.com\"]}]}]}";

    private final UUID agentId = UUID.randomUUID();
    private AgentService agentService;
    private AgentToolArgPolicyRepository repository;
    private AuditLogService audit;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        repository = mock(AgentToolArgPolicyRepository.class);
        audit = mock(AuditLogService.class);
        meters = new SimpleMeterRegistry();
        Agent agent = mock(Agent.class);
        when(agentService.requireAccess(eq(agentId), any(PermissionLevel.class))).thenReturn(agent);
        when(repository.save(any(AgentToolArgPolicy.class))).thenAnswer(i -> i.getArgument(0));
    }

    private ToolArgPolicyService service(boolean enabled, long ttlSeconds) {
        return new ToolArgPolicyService(
                agentService,
                repository,
                providerOf(audit),
                providerOf((MeterRegistry) meters),
                enabled,
                ttlSeconds);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static JsonNode json(String s) throws Exception {
        return MAPPER.readTree(s);
    }

    // ------------------------------------------------------------------ flag off

    @Test
    void withTheFlagOffNothingIsReadEnforcedOrWritten() throws Exception {
        ToolArgPolicyService off = service(false, 30);

        assertFalse(off.isEnabled());
        assertNull(off.evaluatorFor(agentId));
        PoliciesResponse got = off.get(agentId);
        assertFalse(got.enabled());
        assertEquals(0, got.policies().get("policies").size());
        verify(repository, never()).findById(any());

        ResponseStatusException conflict =
                assertThrows(ResponseStatusException.class, () -> off.put(agentId, json(DOC)));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        verify(repository, never()).save(any());
    }

    // ------------------------------------------------------------------ REST side

    @Test
    void getIsDeniedWhenTheCallerCannotViewTheAgent() {
        when(agentService.requireAccess(agentId, PermissionLevel.VIEWER))
                .thenThrow(new ResourceAccessDeniedException());
        assertThrows(ResourceAccessDeniedException.class, () -> service(true, 30).get(agentId));
    }

    @Test
    void getReturnsTheStoredDocumentAndTheLimits() {
        AgentToolArgPolicy row = new AgentToolArgPolicy(agentId, DOC, "alice");
        when(repository.findById(agentId)).thenReturn(Optional.of(row));

        PoliciesResponse got = service(true, 30).get(agentId);

        assertTrue(got.enabled());
        assertEquals(
                "fetch_web_page", got.policies().get("policies").get(0).get("tool").asString());
        assertEquals("alice", got.updatedBy());
        assertNotNull(got.updatedAt());
        assertEquals(50, got.limits().maxPolicies());
    }

    @Test
    void getWithNothingStoredIsAnEmptyList() {
        when(repository.findById(agentId)).thenReturn(Optional.empty());
        PoliciesResponse got = service(true, 30).get(agentId);
        assertTrue(got.enabled());
        assertEquals(0, got.policies().get("policies").size());
        assertNull(got.updatedAt());
    }

    @Test
    void putValidatesStoresTheCanonicalFormAndEchoesIt() throws Exception {
        when(repository.findById(agentId)).thenReturn(Optional.empty());

        PoliciesResponse saved = service(true, 30).put(agentId, json(DOC));

        verify(agentService).requireAccess(agentId, PermissionLevel.EDITOR);
        org.mockito.ArgumentCaptor<AgentToolArgPolicy> captor =
                org.mockito.ArgumentCaptor.forClass(AgentToolArgPolicy.class);
        verify(repository).save(captor.capture());
        assertEquals(agentId, captor.getValue().getAgentId());
        // Canonical: the host was lower-cased on the way in.
        assertTrue(
                captor.getValue().getPoliciesJson().contains("\"example.com\""),
                captor.getValue().getPoliciesJson());
        assertEquals(
                "example.com",
                saved.policies()
                        .get("policies")
                        .get(0)
                        .get("rules")
                        .get(0)
                        .get("urlHostIn")
                        .get(0)
                        .asString());
    }

    @Test
    void putRejectsAnInvalidDocumentWithoutTouchingStorage() throws Exception {
        IllegalArgumentException bad =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service(true, 30)
                                        .put(
                                                agentId,
                                                json(
                                                        "{\"policies\":[{\"tool\":\"t\",\"rules\":[{\"path\":\"a\",\"maxLenght\":3}]}]}")));
        assertTrue(bad.getMessage().contains("maxLenght"), bad.getMessage());
        assertThrows(
                IllegalArgumentException.class, () -> service(true, 30).put(agentId, json("[1]")));
        assertThrows(IllegalArgumentException.class, () -> service(true, 30).put(agentId, null));
        verify(repository, never()).save(any());
    }

    @Test
    void anEmptyPolicyListClearsTheStoredRow() throws Exception {
        AgentToolArgPolicy row = new AgentToolArgPolicy(agentId, DOC, "alice");
        when(repository.findById(agentId)).thenReturn(Optional.of(row));

        PoliciesResponse cleared = service(true, 30).put(agentId, json("{\"policies\":[]}"));

        verify(repository).delete(row);
        verify(repository, never()).save(any());
        assertEquals(0, cleared.policies().get("policies").size());
    }

    @Test
    void putUpdatesAnExistingRowInPlace() throws Exception {
        AgentToolArgPolicy row = new AgentToolArgPolicy(agentId, "{\"policies\":[]}", "alice");
        when(repository.findById(agentId)).thenReturn(Optional.of(row));

        service(true, 30).put(agentId, json(DOC));

        verify(repository).save(row);
        assertTrue(row.getPoliciesJson().contains("fetch_web_page"));
    }

    // ------------------------------------------------------------------ dispatch side

    @Test
    void evaluatorForReturnsNullWhenThereAreNoPolicies() {
        when(repository.findById(agentId)).thenReturn(Optional.empty());
        assertNull(service(true, 30).evaluatorFor(agentId));
        assertNull(service(true, 30).evaluatorFor(null));
    }

    @Test
    void evaluatorForEnforcesTheStoredPoliciesAndCachesThem() {
        when(repository.findById(agentId))
                .thenReturn(Optional.of(new AgentToolArgPolicy(agentId, DOC, "a")));
        ToolArgPolicyService svc = service(true, 30);

        ToolArgPolicyEvaluator first = svc.evaluatorFor(agentId);
        ToolArgPolicyEvaluator second = svc.evaluatorFor(agentId);

        assertNotNull(first);
        assertTrue(
                first.evaluate("fetch_web_page", "{\"url\":\"https://example.com/\"}").allowed());
        assertFalse(first.evaluate("fetch_web_page", "{\"url\":\"https://evil.test/\"}").allowed());
        assertEquals(first, second);
        verify(repository, times(1)).findById(agentId);
    }

    @Test
    void aZeroTtlRereadsEveryTime() {
        when(repository.findById(agentId))
                .thenReturn(Optional.of(new AgentToolArgPolicy(agentId, DOC, "a")));
        ToolArgPolicyService svc = service(true, 0);
        svc.evaluatorFor(agentId);
        svc.evaluatorFor(agentId);
        verify(repository, times(2)).findById(agentId);
    }

    @Test
    void aPutDropsTheCachedEvaluator() throws Exception {
        when(repository.findById(agentId)).thenReturn(Optional.empty());
        ToolArgPolicyService svc = service(true, 30);
        assertNull(svc.evaluatorFor(agentId));

        svc.put(agentId, json(DOC));
        when(repository.findById(agentId))
                .thenReturn(Optional.of(new AgentToolArgPolicy(agentId, DOC, "a")));

        assertNotNull(svc.evaluatorFor(agentId));
    }

    @Test
    void aStoredDocumentThatNoLongerValidatesIsNotEnforcedButDoesNotThrow() {
        when(repository.findById(agentId))
                .thenReturn(
                        Optional.of(
                                new AgentToolArgPolicy(
                                        agentId,
                                        "{\"policies\":[{\"tool\":\"t\",\"rules\":[{\"path\":\"a\"}]}]}",
                                        "a")));
        assertNull(service(true, 30).evaluatorFor(agentId));
        assertEquals(
                1.0, meters.counter("agent.tool.arg_policy.unparseable", "tool", "none").count());
    }

    // ------------------------------------------------------------------ audit

    @Test
    void aViolationIsAuditedAndCountedWithoutArgumentValues() {
        service(true, 30)
                .onViolation(agentId, "sess-1", "fetch_web_page", "'url' must be an http(s) URL");

        verify(audit)
                .record(
                        eq("agent:" + agentId),
                        eq("tool.arg_policy.denied"),
                        eq("AGENT_TOOL"),
                        eq("fetch_web_page"),
                        eq(AuditOutcome.REJECTED),
                        anyString());
        assertEquals(
                1.0,
                meters.counter("agent.tool.arg_policy.denied", "tool", "fetch_web_page").count());
    }

    @Test
    void auditDetailIsBounded() {
        String huge = "x".repeat(10_000);
        service(true, 30).onViolation(agentId, "s", "t".repeat(500), huge);
        org.mockito.ArgumentCaptor<String> target =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> detail =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(audit)
                .record(
                        anyString(),
                        anyString(),
                        anyString(),
                        target.capture(),
                        any(AuditOutcome.class),
                        detail.capture());
        assertTrue(target.getValue().length() <= 128);
        assertTrue(detail.getValue().length() <= 2000);
    }

    @Test
    void aMissingAuditServiceIsNotAnError() {
        ToolArgPolicyService svc =
                new ToolArgPolicyService(
                        agentService,
                        repository,
                        providerOf((AuditLogService) null),
                        providerOf((MeterRegistry) null),
                        true,
                        30);
        svc.onViolation(agentId, "s", "t", "r");
    }
}
