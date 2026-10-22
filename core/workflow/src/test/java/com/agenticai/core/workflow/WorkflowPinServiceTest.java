package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.dto.WorkflowPinRequest;
import com.agenticai.core.workflow.dto.WorkflowPinResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Who may list, write and read pins (RUNNER lists without data, EDITOR does everything), the
 * server-side config hash, audit logging, and the exact JSON of the pin DTOs.
 */
class WorkflowPinServiceTest {

    private static final UUID WF = UUID.randomUUID();

    private WorkflowService workflowService;
    private AuditLogService audit;
    private WorkflowPinStore store;
    private WorkflowPinService service;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        audit = mock(AuditLogService.class);
        store = new WorkflowPinStore(PinTestSupport.pinRepository());
        service = new WorkflowPinService(workflowService, store, audit);
        asEditor();
    }

    private static WorkflowNodeDto node(String id, String type, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("label", id, "config", config));
    }

    /**
     * The caller holds {@code highest} and every level below it (the ladder is RUNNER < VIEWER <
     * EDITOR).
     */
    private void grant(PermissionLevel highest) {
        org.mockito.Mockito.reset(workflowService);
        WorkflowDefinition def = mock(WorkflowDefinition.class);
        for (PermissionLevel level : PermissionLevel.values()) {
            if (level.compareTo(highest) <= 0) {
                org.mockito.Mockito.doReturn(def).when(workflowService).requireAccess(WF, level);
            } else {
                org.mockito.Mockito.doThrow(new ResourceAccessDeniedException())
                        .when(workflowService)
                        .requireAccess(WF, level);
            }
        }
        when(workflowService.graph(WF))
                .thenReturn(
                        new WorkflowGraphDto(
                                List.of(
                                        node("n1", "http", Map.of("url", "https://example.com")),
                                        node("n2", "code", Map.of())),
                                List.of()));
    }

    private void asEditor() {
        grant(PermissionLevel.EDITOR);
    }

    private void asViewer() {
        grant(PermissionLevel.VIEWER);
    }

    private void asRunner() {
        grant(PermissionLevel.RUNNER);
    }

    private WorkflowPinResponse put(String nodeId, String data) {
        return service.put(WF, nodeId, new WorkflowPinRequest(data, "test", "run-1", null), "ann");
    }

    @Test
    void anEditorListsPinsWithTheirData() {
        put("n1", "{\"a\":1}");
        List<WorkflowPinResponse> pins = service.list(WF);
        assertEquals(1, pins.size());
        assertEquals("{\"a\":1}", pins.get(0).data());
    }

    @Test
    void aRunnerListsPinMetadataButNeverTheData() {
        put("n1", "{\"customer\":\"a@b.co\"}");
        asRunner();

        List<WorkflowPinResponse> pins = service.list(WF);

        assertEquals(1, pins.size());
        WorkflowPinResponse p = pins.get(0);
        assertNull(p.data(), "a runner must not read pinned (possibly customer) data");
        assertEquals("n1", p.nodeId());
        assertEquals("test", p.source());
        assertEquals("ann", p.pinnedBy());
        assertEquals("{\"customer\":\"a@b.co\"}".length(), p.sizeChars(), "size stays visible");
        assertNotNull(p.configHash());
    }

    @Test
    void aViewerSeesMetadataOnlyBecauseReadingTheGraphIsNotReadingRunData() {
        put("n1", "{\"a\":1}");
        asViewer();
        assertNull(service.list(WF).get(0).data());
    }

    @Test
    void creatingOrDeletingAPinNeedsEditAndChangesNothingOtherwise() {
        put("n1", "{}");
        asViewer();

        assertThrows(ResourceAccessDeniedException.class, () -> put("n2", "{}"));
        assertThrows(ResourceAccessDeniedException.class, () -> service.delete(WF, "n1", "vic"));

        asRunner();
        assertThrows(ResourceAccessDeniedException.class, () -> put("n2", "{}"));
        assertTrue(store.find(WF, "n2").isEmpty());
        assertTrue(store.find(WF, "n1").isPresent(), "the denied delete left the pin alone");
        verify(audit, times(1))
                .record(
                        anyString(),
                        eq("WORKFLOW_PIN_SET"),
                        anyString(),
                        anyString(),
                        any(),
                        anyString());
        verify(audit, never())
                .record(
                        anyString(),
                        eq("WORKFLOW_PIN_DELETE"),
                        anyString(),
                        anyString(),
                        any(),
                        anyString());
    }

    @Test
    void theServerComputesTheConfigHashWhenTheClientSendsNone() {
        WorkflowPinResponse p = put("n1", "{}");
        assertEquals(
                WorkflowPinService.configHash(
                        node("n1", "http", Map.of("url", "https://example.com"))),
                p.configHash());
        assertEquals(32, p.configHash().length());
    }

    @Test
    void aClientSuppliedConfigHashIsKept() {
        WorkflowPinResponse p =
                service.put(
                        WF,
                        "n1",
                        new WorkflowPinRequest("{}", "manual", null, "client-hash"),
                        "ann");
        assertEquals("client-hash", p.configHash());
    }

    @Test
    void theConfigHashIgnoresKeyOrderLabelAndPositionButNotConfigOrType() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("url", "https://x");
        a.put("method", "GET");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("method", "GET");
        b.put("url", "https://x");
        WorkflowNodeDto relabelled =
                new WorkflowNodeDto(
                        "z", "http", Map.of("x", 5), Map.of("label", "Other", "config", b));

        assertEquals(
                WorkflowPinService.configHash(node("n1", "http", a)),
                WorkflowPinService.configHash(relabelled));
        assertNotEquals(
                WorkflowPinService.configHash(node("n1", "http", a)),
                WorkflowPinService.configHash(
                        node("n1", "http", Map.of("url", "https://y", "method", "GET"))));
        assertNotEquals(
                WorkflowPinService.configHash(node("n1", "http", a)),
                WorkflowPinService.configHash(node("n1", "code", a)));
    }

    @Test
    void aNodeThatIsNotInTheSavedGraphCannotBePinned() {
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> put("ghost", "{}"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertTrue(store.list(WF).isEmpty());
    }

    @Test
    void limitViolationsSurfaceAsBadRequestWithTheMessage() {
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> put("n1", "x".repeat(256_001)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("256000"));
    }

    @Test
    void aMissingBodyIsBadRequestAndAMissingSourceMeansManual() {
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(
                                ResponseStatusException.class,
                                () -> service.put(WF, "n1", null, "ann"))
                        .getStatusCode());
        assertEquals(
                "manual",
                service.put(WF, "n1", new WorkflowPinRequest("{}", null, null, null), "ann")
                        .source());
    }

    @Test
    void pinChangesAreAuditedWithoutTheirData() {
        put("n1", "{\"secret\":\"do-not-log\"}");
        service.delete(WF, "n1", "ann");
        service.delete(WF, "n1", "ann"); // idempotent, and nothing to audit the second time

        verify(audit)
                .record(
                        eq("ann"),
                        eq("WORKFLOW_PIN_SET"),
                        eq("Workflow"),
                        eq(WF.toString()),
                        eq(AuditOutcome.SUCCESS),
                        contains("n1"));
        verify(audit)
                .record(
                        eq("ann"),
                        eq("WORKFLOW_PIN_DELETE"),
                        eq("Workflow"),
                        eq(WF.toString()),
                        eq(AuditOutcome.SUCCESS),
                        contains("n1"));
        verify(audit, times(2))
                .record(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        org.mockito.ArgumentMatchers.argThat(
                                detail -> !detail.contains("do-not-log")));
        assertTrue(store.find(WF, "n1").isEmpty());
    }

    @Test
    void aFailingAuditWriteDoesNotUndoThePin() {
        when(audit.record(anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("audit db down"));
        assertEquals("{}", put("n1", "{}").data());
        assertTrue(store.find(WF, "n1").isPresent());
    }

    // ---- JSON contract

    @Test
    void thePinResponseSerialisesWithExactlyTheContractFieldNames() throws Exception {
        ObjectMapper mapper = PinTestSupport.appMapper();
        JsonNode full = mapper.readTree(mapper.writeValueAsString(put("n1", "{\"a\":1}")));
        assertEquals(
                new TreeSet<>(
                        Set.of(
                                "nodeId",
                                "source",
                                "sourceRunId",
                                "pinnedBy",
                                "pinnedAt",
                                "sizeChars",
                                "configHash",
                                "data")),
                fieldNames(full));
        assertEquals("n1", full.get("nodeId").asString());
        assertEquals("test", full.get("source").asString());
        assertEquals("run-1", full.get("sourceRunId").asString());
        assertEquals("ann", full.get("pinnedBy").asString());
        assertTrue(
                full.get("pinnedAt").isString(), "pinnedAt is an ISO string, not an epoch number");
        assertTrue(java.time.Instant.parse(full.get("pinnedAt").asString()) != null);
        assertEquals(7, full.get("sizeChars").asInt());
        assertEquals("{\"a\":1}", full.get("data").asString());

        asRunner();
        JsonNode hidden = mapper.readTree(mapper.writeValueAsString(service.list(WF).get(0)));
        assertTrue(
                hidden.has("data") && hidden.get("data").isNull(),
                "data is present and null for a runner");
    }

    @Test
    void thePinRequestReadsTheContractBodyAndIgnoresUnknownFields() throws Exception {
        WorkflowPinRequest r =
                PinTestSupport.appMapper()
                        .readValue(
                                "{\"data\":\"{}\",\"source\":\"trigger_sample\",\"sourceRunId\":\"r1\",\"configHash\":\"h\",\"extra\":1}",
                                WorkflowPinRequest.class);
        assertEquals("{}", r.data());
        assertEquals("trigger_sample", r.source());
        assertEquals("r1", r.sourceRunId());
        assertEquals("h", r.configHash());
    }

    static TreeSet<String> fieldNames(JsonNode node) {
        TreeSet<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
