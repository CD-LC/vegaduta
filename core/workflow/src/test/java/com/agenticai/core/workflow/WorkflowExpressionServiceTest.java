package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.EvaluateRequest;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.EvaluateResponse;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.FunctionDoc;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.LintDiagnostic;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.LintItem;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.LintRequest;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.SchemaNode;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.Supplied;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.UpstreamSchemaResponse;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.expression.BuiltIns;
import java.util.ArrayList;
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
 * Evaluate / lint / upstream-schema / function registry behaviour and the exact JSON of their DTOs.
 *
 * <p>Graph used throughout: {@code start (trigger) -> fetch (http) -> price (code)}, plus {@code
 * side} (code, not connected to {@code price}) for the NOT_UPSTREAM case.
 */
class WorkflowExpressionServiceTest {

    private static final UUID WF = UUID.randomUUID();
    private static final String ORDER =
            "{\"items\":[{\"price\":10,\"sku\":\"a\"},{\"price\":20,\"sku\":\"b\"}],\"customer\":{\"name\":\"Ada\"}}";

    private WorkflowService workflowService;
    private WorkflowPinStore pinStore;
    private RunDataStore runData;
    private WorkflowRunRepository runs;
    private WorkflowExpressionRateLimiter limiter;
    private WorkflowExpressionService service;
    private final ObjectMapper mapper = PinTestSupport.appMapper();

    private static WorkflowNodeDto node(
            String id, String type, String label, Map<String, Object> config) {
        return new WorkflowNodeDto(id, type, Map.of(), Map.of("label", label, "config", config));
    }

    private static WorkflowEdgeDto edge(String from, String to) {
        return new WorkflowEdgeDto(from + "-" + to, from, to, null, null);
    }

    private WorkflowGraphDto graph;

    @BeforeEach
    void setUp() {
        graph =
                new WorkflowGraphDto(
                        List.of(
                                node("start", "trigger", "Start", Map.of()),
                                node(
                                        "fetch",
                                        "http",
                                        "Fetch",
                                        Map.of(
                                                "url",
                                                "https://api.example.com/orders",
                                                "method",
                                                "GET")),
                                node(
                                        "price",
                                        "code",
                                        "Price",
                                        Map.of("operation", "template", "template", "x")),
                                node("side", "code", "Side", Map.of())),
                        List.of(edge("start", "fetch"), edge("fetch", "price")));
        workflowService = mock(WorkflowService.class);
        WorkflowDefinition def = mock(WorkflowDefinition.class);
        when(def.getName()).thenReturn("Orders");
        for (PermissionLevel level : PermissionLevel.values()) {
            when(workflowService.requireAccess(WF, level)).thenReturn(def);
        }
        when(workflowService.graph(WF)).thenReturn(graph);
        pinStore = new WorkflowPinStore(PinTestSupport.pinRepository());
        runData = mock(RunDataStore.class);
        runs = mock(WorkflowRunRepository.class);
        limiter = mock(WorkflowExpressionRateLimiter.class);
        service = newService(true);
    }

    private WorkflowExpressionService newService(boolean enabled) {
        return new WorkflowExpressionService(
                workflowService, pinStore, runData, runs, limiter, mapper, enabled, false);
    }

    private void pin(String nodeId, String data) {
        pinStore.save(WF, nodeId, data, "manual", null, "ann", null);
    }

    private EvaluateResponse eval(String template, String nodeId, String source) {
        return service.evaluate(WF, new EvaluateRequest(template, nodeId, source, null));
    }

    // ---- evaluate

    @Test
    void evaluatesATypedExpressionAgainstThePinnedUpstreamOutput() {
        pin("fetch", ORDER);

        EvaluateResponse r = eval("{{ $json.items[1].price * 2 }}", "price", "pinned");

        assertTrue(r.ok());
        assertEquals(40, ((Number) r.value()).intValue());
        assertEquals("number", r.type());
        assertEquals("40", r.rendered());
        assertNull(r.error());
        assertEquals(List.of("fetch"), r.sources(), "the node whose data was read");
        assertEquals(List.of(), r.warnings());
    }

    @Test
    void aMixedTemplateRendersToAStringAndAnObjectKeepsItsType() {
        pin("fetch", ORDER);

        EvaluateResponse text = eval("Hello {{ $json.customer.name }}!", "price", "pinned");
        assertEquals("string", text.type());
        assertEquals("Hello Ada!", text.value());
        assertEquals("Hello Ada!", text.rendered());

        EvaluateResponse object = eval("{{ $json.customer }}", "price", "pinned");
        assertEquals("object", object.type());
        assertEquals(Map.of("name", "Ada"), object.value());
        assertEquals("{\"name\":\"Ada\"}", object.rendered());

        assertEquals("array", eval("{{ $json.items }}", "price", "pinned").type());
        assertEquals("boolean", eval("{{ $json.items.length > 1 }}", "price", "pinned").type());
        assertEquals("null", eval("{{ $json.nothing }}", "price", "pinned").type());
    }

    @Test
    void readsAnyPinnedNodeByLabelAndTheTriggerPayload() {
        pin("start", "{\"event\":\"order.created\"}");
        pin("fetch", ORDER);

        EvaluateResponse r =
                eval(
                        "{{ $node[\"Fetch\"].customer.name }} / {{ $trigger.event }}",
                        "price",
                        "pinned");

        assertEquals("Ada / order.created", r.value());
        assertEquals(Set.of("fetch", "start"), Set.copyOf(r.sources()));
    }

    @Test
    void missingDataReadsAsEmptyWithAWarningNotAnError() {
        EvaluateResponse r = eval("{{ $node[\"Fetch\"].customer.name }}", "price", "pinned");

        assertTrue(r.ok());
        assertEquals("null", r.type());
        assertEquals("", r.rendered());
        assertTrue(
                r.warnings().stream().anyMatch(w -> w.contains("'Fetch'")),
                r.warnings().toString());
    }

    @Test
    void aFeedingNodeWithoutDataIsWarnedAbout() {
        EvaluateResponse r = eval("{{ $json.a }}", "price", "pinned");
        assertTrue(
                r.warnings().stream().anyMatch(w -> w.contains("$json")), r.warnings().toString());
    }

    @Test
    void aParseErrorIsReportedWithPositionsInsideTheTemplate() {
        String template = "Total: {{ $json.items[0].price + }} EUR";

        EvaluateResponse r = eval(template, "price", "pinned");

        assertFalse(r.ok());
        assertNull(r.value());
        assertEquals("null", r.type());
        assertEquals("", r.rendered());
        assertNotNull(r.error());
        assertFalse(r.error().message().isBlank());
        int open = template.indexOf("{{");
        int close = template.indexOf("}}") + 2;
        assertTrue(
                r.error().start() >= open && r.error().start() <= close,
                "start " + r.error().start());
        assertTrue(r.error().end() > r.error().start() || r.error().end() == r.error().start());
        assertTrue(r.error().end() <= template.length());
        assertEquals(List.of(), r.sources());
    }

    @Test
    void aRuntimeErrorPointsAtTheFailingCallNotJustTheTemplate() {
        String template = "{{ 1 }} then {{ parseJson(\"{oops\") }}";

        EvaluateResponse r = eval(template, null, "supplied");

        assertFalse(r.ok());
        int second = template.indexOf("parseJson");
        assertTrue(r.error().start() >= template.indexOf("{{", 3), "start " + r.error().start());
        assertTrue(
                r.error().start() >= second - 3 && r.error().end() <= template.length(),
                r.error().toString());
    }

    @Test
    void anUnknownFunctionIsAnErrorWithAPositionAndNeverAServerError() {
        EvaluateResponse r = eval("{{ nosuchfn(1) }}", null, "supplied");
        assertFalse(r.ok());
        assertTrue(r.error().message().toLowerCase().contains("nosuchfn"), r.error().message());
    }

    @Test
    void theEngineLimitsApplyAndAreReportedAsAnError() {
        // Under the 2000-char source limit but far over the 500-node limit.
        EvaluateResponse r = eval("{{ " + "1+".repeat(600) + "1 }}", null, "supplied");
        assertFalse(r.ok());
        assertNotNull(r.error());
        assertTrue(
                r.error().message().toLowerCase().contains("complex")
                        || r.error().message().toLowerCase().contains("node")
                        || r.error().message().toLowerCase().contains("large")
                        || r.error().message().toLowerCase().contains("long"),
                r.error().message());
    }

    @Test
    void suppliedDataFeedsJsonAndNodesByLabel() {
        EvaluateResponse r =
                service.evaluate(
                        WF,
                        new EvaluateRequest(
                                "{{ $json.a }}-{{ $node[\"Fetch\"].b }}",
                                "price",
                                "supplied",
                                new Supplied("{\"a\":1}", Map.of("Fetch", "{\"b\":2}"))));
        assertTrue(r.ok());
        assertEquals("1-2", r.value());
    }

    @Test
    void suppliedNodeOutputsAreUsedForTheFeedingNodeWhenNoInputIsGiven() {
        EvaluateResponse r =
                service.evaluate(
                        WF,
                        new EvaluateRequest(
                                "{{ $json.b }}",
                                "price",
                                "supplied",
                                new Supplied(null, Map.of("Fetch", "{\"b\":7}"))));
        assertEquals(7, ((Number) r.value()).intValue());
    }

    @Test
    void lastRunDataComesFromTheRunDataStoreOfTheNewestRun() {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun(runId, WF, "Orders", "u");
        when(runs.findFirstByWorkflowIdOrderByStartedAtDesc(WF))
                .thenReturn(java.util.Optional.of(run));
        when(runData.latestById(runId, "fetch"))
                .thenReturn(
                        new RunDataStore.Stored(
                                "fetch", "Fetch", 0, "OK", "{\"total\":99}", false));

        EvaluateResponse r = eval("{{ $json.total }}", "price", "lastRun");

        assertEquals(99, ((Number) r.value()).intValue());
        assertEquals(List.of("fetch"), r.sources());
    }

    @Test
    void lastRunWithNoRunsYetSaysSo() {
        when(runs.findFirstByWorkflowIdOrderByStartedAtDesc(WF))
                .thenReturn(java.util.Optional.empty());
        EvaluateResponse r = eval("{{ $json.total }}", "price", "lastRun");
        assertTrue(r.ok());
        assertTrue(
                r.warnings().stream().anyMatch(w -> w.contains("not been run")),
                r.warnings().toString());
    }

    @Test
    void badRequestsAre400() {
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        service.evaluate(
                                                WF,
                                                new EvaluateRequest(null, null, "pinned", null)))
                        .getStatusCode());
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        service.evaluate(
                                                WF,
                                                new EvaluateRequest(
                                                        "{{ 1 }}", null, "bogus", null)))
                        .getStatusCode());
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        service.evaluate(
                                                WF,
                                                new EvaluateRequest(
                                                        "x".repeat(20_001), null, "pinned", null)))
                        .getStatusCode());
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.evaluate(WF, null))
                        .getStatusCode());
    }

    @Test
    void evaluateNeedsEditAndIsRateLimited() {
        doThrow(new ResourceAccessDeniedException())
                .when(workflowService)
                .requireAccess(WF, PermissionLevel.EDITOR);
        assertThrows(ResourceAccessDeniedException.class, () -> eval("{{ 1 }}", null, "supplied"));
        verify(limiter, never()).check();

        org.mockito.Mockito.reset(workflowService);
        WorkflowDefinition def = mock(WorkflowDefinition.class);
        when(workflowService.requireAccess(any(), any())).thenReturn(def);
        when(workflowService.graph(WF)).thenReturn(graph);
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "slow down"))
                .when(limiter)
                .check();
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                assertThrows(ResponseStatusException.class, () -> eval("{{ 1 }}", null, "supplied"))
                        .getStatusCode());
    }

    // ---- kill switch

    @Test
    void whenExpressionsAreSwitchedOffEvaluateAndLintAnswer409WithAMessage() {
        WorkflowExpressionService off = newService(false);

        ResponseStatusException ev =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                off.evaluate(
                                        WF,
                                        new EvaluateRequest("{{ 1 }}", null, "supplied", null)));
        ResponseStatusException li =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                off.lint(
                                        WF,
                                        new LintRequest(
                                                List.of(
                                                        new LintItem(
                                                                "price", "template", "{{ 1 }}")))));

        assertEquals(HttpStatus.CONFLICT, ev.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, li.getStatusCode());
        assertTrue(ev.getReason().contains("expressions"), ev.getReason());
        assertTrue(li.getReason().contains("app.workflow.expressions.enabled"), li.getReason());
        // Everything else keeps working: the schema and the function list do not evaluate anything.
        assertNotNull(off.upstreamSchema(WF, "price"));
        assertFalse(off.functions().isEmpty());
    }

    // ---- lint

    private List<LintDiagnostic> lint(String nodeId, String template) {
        return service.lint(
                        WF, new LintRequest(List.of(new LintItem(nodeId, "template", template))))
                .diagnostics();
    }

    private static LintDiagnostic only(List<LintDiagnostic> d, String code) {
        List<LintDiagnostic> matching = d.stream().filter(x -> x.code().equals(code)).toList();
        assertEquals(1, matching.size(), d.toString());
        return matching.get(0);
    }

    @Test
    void aCleanTemplateHasNoDiagnostics() {
        pin("fetch", ORDER);
        assertEquals(
                List.of(),
                lint(
                        "price",
                        "{{ $json.items[0].price }} and {{ $node[\"Fetch\"].customer.name }}"));
    }

    @Test
    void lintReportsParseErrorsWithTheFieldAndPosition() {
        String template = "a {{ 1 + }} b";
        LintDiagnostic d = only(lint("price", template), "PARSE_ERROR");
        assertEquals("error", d.severity());
        assertEquals("price", d.nodeId());
        assertEquals("template", d.field());
        assertTrue(d.start() >= 0 && d.end() <= template.length() && d.start() <= d.end());
    }

    @Test
    void lintReportsUnknownFunctions() {
        LintDiagnostic d = only(lint("price", "{{ frobnicate($json) }}"), "UNKNOWN_FUNCTION");
        assertEquals("error", d.severity());
    }

    @Test
    void lintReportsAnUnknownNodeLabelWithASuggestion() {
        String template = "{{ $node[\"Fetsh\"].x }}";
        LintDiagnostic d = only(lint("price", template), "UNKNOWN_NODE");
        assertEquals("error", d.severity());
        assertTrue(d.message().contains("Fetch"), d.message());
        assertTrue(
                template.substring(d.start(), d.end()).contains("Fetsh"),
                "position of the reference");
    }

    @Test
    void lintReportsAReferenceToANodeThatIsNotUpstream() {
        String template = "x {{ $node[\"Side\"].v }}";
        LintDiagnostic d = only(lint("price", template), "NOT_UPSTREAM");
        assertEquals("error", d.severity());
        assertEquals("$node[\"Side\"]", template.substring(d.start(), d.end()));
        // The same label from a node that does have it upstream is fine.
        assertEquals(List.of(), lint("price", "{{ $node[\"Fetch\"].v }}"));
        // A node is never upstream of itself.
        assertEquals(
                "NOT_UPSTREAM",
                only(lint("fetch", "{{ $node[\"Fetch\"].v }}"), "NOT_UPSTREAM").code());
        // An id reference is checked the same way.
        assertEquals(
                "NOT_UPSTREAM",
                only(lint("price", "{{ $node.id(\"side\").v }}"), "NOT_UPSTREAM").code());
    }

    @Test
    void lintReportsAmbiguousLabels() {
        graph =
                new WorkflowGraphDto(
                        List.of(
                                node("a", "code", "Twin", Map.of()),
                                node("b", "code", "Twin", Map.of()),
                                node("c", "code", "C", Map.of())),
                        List.of(edge("a", "c"), edge("b", "c")));
        when(workflowService.graph(WF)).thenReturn(graph);

        LintDiagnostic d = only(lint("c", "{{ $node[\"Twin\"].x }}"), "AMBIGUOUS_LABEL");
        assertEquals("warning", d.severity());
        // $node.id("b") must not be reported as unknown just because "Twin" maps to a.
        assertEquals(List.of(), lint("c", "{{ $node.id(\"b\").x }}"));
    }

    @Test
    void lintWarnsAboutABareNameTheUpstreamPinnedOutputDoesNotHave() {
        pin("fetch", ORDER);
        LintDiagnostic d = only(lint("price", "{{ totl }}"), "LEGACY_UNKNOWN_NAME");
        assertEquals("warning", d.severity());
        assertEquals(
                List.of(),
                lint("price", "{{ customer }}"),
                "a field the pinned output has is fine");
    }

    @Test
    void lintFlagsAPinWhoseNodeConfigChangedSinceItWasPinned() {
        pin("fetch", ORDER); // no hash recorded by this helper
        assertEquals(
                List.of(),
                lint("price", "{{ $node[\"Fetch\"].customer.name }}"),
                "no recorded hash -> nothing to compare");

        pinStore.save(
                WF, "fetch", ORDER, "manual", null, "ann", "0123456789abcdef0123456789abcdef");
        String template = "{{ $node[\"Fetch\"].customer.name }}";
        LintDiagnostic stale = only(lint("price", template), "STALE_PIN");
        assertEquals("warning", stale.severity());
        assertEquals("$node[\"Fetch\"]", template.substring(stale.start(), stale.end()));

        // Pinned with the node's CURRENT config hash: not stale.
        pinStore.save(
                WF,
                "fetch",
                ORDER,
                "manual",
                null,
                "ann",
                WorkflowPinService.configHash(graph.nodes().get(1)));
        assertEquals(List.of(), lint("price", template));
    }

    @Test
    void lintKeepsTheItemIdentityAndSkipsEmptyTemplates() {
        List<LintDiagnostic> d =
                service.lint(
                                WF,
                                new LintRequest(
                                        List.of(
                                                new LintItem("price", "template", ""),
                                                new LintItem("fetch", "url", "{{ 1 + }}"))))
                        .diagnostics();
        assertEquals(1, d.size());
        assertEquals("fetch", d.get(0).nodeId());
        assertEquals("url", d.get(0).field());
    }

    @Test
    void lintOfANodeThatIsNotSavedYetStillChecksTheExpression() {
        List<LintDiagnostic> d = lint("brand-new", "{{ frobnicate(1) }} {{ $node[\"Fetch\"].x }}");
        assertEquals(List.of("UNKNOWN_FUNCTION"), d.stream().map(LintDiagnostic::code).toList());
    }

    @Test
    void lintNeedsOnlyViewAndHasARequestCap() {
        doThrow(new ResourceAccessDeniedException())
                .when(workflowService)
                .requireAccess(WF, PermissionLevel.VIEWER);
        assertThrows(ResourceAccessDeniedException.class, () -> lint("price", "{{ 1 }}"));

        org.mockito.Mockito.reset(workflowService);
        when(workflowService.requireAccess(any(), any()))
                .thenReturn(mock(WorkflowDefinition.class));
        when(workflowService.graph(WF)).thenReturn(graph);
        List<LintItem> tooMany = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            tooMany.add(new LintItem("price", "f", "{{ 1 }}"));
        }
        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(
                                ResponseStatusException.class,
                                () -> service.lint(WF, new LintRequest(tooMany)))
                        .getStatusCode());
    }

    @Test
    void theAmbiguityCodeIsRenamedToTheContractName() {
        assertEquals("AMBIGUOUS_LABEL", WorkflowExpressionService.mapCode("AMBIGUOUS_NODE"));
        assertEquals("UNKNOWN_NODE", WorkflowExpressionService.mapCode("UNKNOWN_NODE"));
    }

    // ---- upstream schema

    @Test
    void theSchemaListsEveryAncestorNearestFirstWithPinBeforeLastRunBeforeNone() {
        UUID runId = UUID.randomUUID();
        when(runs.findFirstByWorkflowIdOrderByStartedAtDesc(WF))
                .thenReturn(java.util.Optional.of(new WorkflowRun(runId, WF, "Orders", "u")));
        when(runData.latestById(runId, "fetch"))
                .thenReturn(
                        new RunDataStore.Stored(
                                "fetch", "Fetch", 0, "OK", "{\"from\":\"last run\"}", false));
        when(runData.latestById(runId, "start")).thenReturn(null);
        pin("fetch", "{\"from\":\"pin\"}");

        UpstreamSchemaResponse r = service.upstreamSchema(WF, "price");

        assertEquals(
                List.of("fetch", "start"), r.nodes().stream().map(SchemaNode::nodeId).toList());
        SchemaNode fetch = r.nodes().get(0);
        assertEquals("Fetch", fetch.label());
        assertEquals("pinned", fetch.source(), "a pin beats the last run");
        assertEquals("from", fetch.paths().get(0).path());
        assertEquals("pin", fetch.paths().get(0).sample());
        SchemaNode start = r.nodes().get(1);
        assertEquals("none", start.source());
        assertEquals(List.of(), start.paths());
    }

    @Test
    void theLastRunIsUsedWhenThereIsNoPin() {
        UUID runId = UUID.randomUUID();
        when(runs.findFirstByWorkflowIdOrderByStartedAtDesc(WF))
                .thenReturn(java.util.Optional.of(new WorkflowRun(runId, WF, "Orders", "u")));
        when(runData.latestById(runId, "fetch"))
                .thenReturn(
                        new RunDataStore.Stored("fetch", "Fetch", 0, "OK", "{\"total\":5}", false));

        SchemaNode fetch = service.upstreamSchema(WF, "price").nodes().get(0);

        assertEquals("lastRun", fetch.source());
        assertEquals("total", fetch.paths().get(0).path());
        assertEquals("number", fetch.paths().get(0).type());
    }

    @Test
    void onlyAncestorsAreListedAndAnUnsavedNodeIs404() {
        assertEquals(List.of(), service.upstreamSchema(WF, "start").nodes());
        assertFalse(
                service.upstreamSchema(WF, "price").nodes().stream()
                        .anyMatch(n -> n.nodeId().equals("side")));
        assertEquals(
                HttpStatus.NOT_FOUND,
                assertThrows(
                                ResponseStatusException.class,
                                () -> service.upstreamSchema(WF, "nope"))
                        .getStatusCode());
    }

    @Test
    void theSchemaNeedsEdit() {
        doThrow(new ResourceAccessDeniedException())
                .when(workflowService)
                .requireAccess(WF, PermissionLevel.EDITOR);
        assertThrows(
                ResourceAccessDeniedException.class, () -> service.upstreamSchema(WF, "price"));
    }

    // ---- functions

    @Test
    void theFunctionListIsTheRegistryWithContractCategories() {
        List<FunctionDoc> docs = service.functions();
        assertEquals(BuiltIns.registry().size(), docs.size());
        Set<String> categories =
                Set.of("string", "number", "array", "object", "date", "type", "logic");
        for (FunctionDoc d : docs) {
            assertTrue(categories.contains(d.category()), d.toString());
            assertFalse(
                    d.name().isBlank()
                            || d.signature().isBlank()
                            || d.doc().isBlank()
                            || d.example().isBlank(),
                    d.toString());
        }
        assertTrue(
                docs.stream()
                        .anyMatch(d -> d.name().equals("round") && d.category().equals("number")));
    }

    // ---- JSON contract

    private static TreeSet<String> keys(JsonNode n) {
        TreeSet<String> names = new TreeSet<>();
        n.propertyNames().forEach(names::add);
        return names;
    }

    @Test
    void evaluateSerialisesWithExactlyTheContractFields() throws Exception {
        pin("fetch", ORDER);
        JsonNode ok =
                mapper.readTree(
                        mapper.writeValueAsString(
                                eval("{{ $json.items[0].price }}", "price", "pinned")));
        assertEquals(
                new TreeSet<>(
                        Set.of("ok", "value", "type", "rendered", "error", "sources", "warnings")),
                keys(ok));
        assertTrue(ok.get("ok").asBoolean());
        assertTrue(ok.get("error").isNull());
        assertTrue(ok.get("sources").isArray() && ok.get("warnings").isArray());

        JsonNode bad =
                mapper.readTree(mapper.writeValueAsString(eval("{{ 1 + }}", null, "supplied")));
        assertFalse(bad.get("ok").asBoolean());
        assertTrue(bad.get("value").isNull());
        assertEquals(new TreeSet<>(Set.of("message", "start", "end")), keys(bad.get("error")));
        assertTrue(bad.get("error").get("start").isInt() && bad.get("error").get("end").isInt());
    }

    @Test
    void evaluateReadsTheContractRequestBody() throws Exception {
        EvaluateRequest r =
                mapper.readValue(
                        "{\"template\":\"{{ 1 }}\",\"nodeId\":\"price\",\"dataSource\":\"supplied\","
                                + "\"supplied\":{\"input\":\"{}\",\"nodes\":{\"Fetch\":\"{}\"}}}",
                        EvaluateRequest.class);
        assertEquals("{{ 1 }}", r.template());
        assertEquals("price", r.nodeId());
        assertEquals("supplied", r.dataSource());
        assertEquals("{}", r.supplied().input());
        assertEquals(Map.of("Fetch", "{}"), r.supplied().nodes());
    }

    @Test
    void lintSerialisesWithExactlyTheContractFields() throws Exception {
        String json =
                mapper.writeValueAsString(
                        service.lint(
                                WF,
                                new LintRequest(
                                        List.of(
                                                new LintItem(
                                                        "price",
                                                        "template",
                                                        "{{ frobnicate(1) }}")))));
        JsonNode root = mapper.readTree(json);
        assertEquals(new TreeSet<>(Set.of("diagnostics")), keys(root));
        JsonNode d = root.get("diagnostics").get(0);
        assertEquals(
                new TreeSet<>(
                        Set.of("nodeId", "field", "severity", "code", "message", "start", "end")),
                keys(d));
        assertEquals("error", d.get("severity").asString());
        LintRequest parsed =
                mapper.readValue(
                        "{\"items\":[{\"nodeId\":\"a\",\"field\":\"url\",\"template\":\"t\"}]}",
                        LintRequest.class);
        assertEquals(new LintItem("a", "url", "t"), parsed.items().get(0));
    }

    @Test
    void theSchemaSerialisesWithExactlyTheContractFields() throws Exception {
        pin("fetch", ORDER);
        JsonNode root =
                mapper.readTree(mapper.writeValueAsString(service.upstreamSchema(WF, "price")));
        assertEquals(new TreeSet<>(Set.of("nodes")), keys(root));
        JsonNode node = root.get("nodes").get(0);
        assertEquals(new TreeSet<>(Set.of("nodeId", "label", "source", "paths")), keys(node));
        JsonNode path = node.get("paths").get(0);
        assertEquals(new TreeSet<>(Set.of("path", "type", "sample")), keys(path));
        JsonNode container =
                node.get("paths").get(0); // "items" is an array: sample is present and null
        assertEquals("items", container.get("path").asString());
        assertTrue(container.has("sample") && container.get("sample").isNull());
    }

    @Test
    void theFunctionListSerialisesWithExactlyTheContractFields() throws Exception {
        JsonNode first = mapper.readTree(mapper.writeValueAsString(service.functions())).get(0);
        assertEquals(
                new TreeSet<>(Set.of("name", "category", "signature", "doc", "example")),
                keys(first));
    }
}
