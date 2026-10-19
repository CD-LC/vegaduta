package com.agenticai.core.workflow;

import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.EvaluateError;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.EvaluateRequest;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.EvaluateResponse;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.FunctionDoc;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.LintDiagnostic;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.LintItem;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.LintRequest;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.LintResponse;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.SchemaNode;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.SchemaPath;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.Supplied;
import com.agenticai.core.workflow.dto.WorkflowExpressionApi.UpstreamSchemaResponse;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.expression.BuiltIn;
import com.agenticai.core.workflow.expression.BuiltIns;
import com.agenticai.core.workflow.expression.Diagnostic;
import com.agenticai.core.workflow.expression.ExpressionEngine;
import com.agenticai.core.workflow.expression.ExpressionException;
import com.agenticai.core.workflow.expression.Limits;
import com.agenticai.core.workflow.expression.LintContext;
import com.agenticai.core.workflow.expression.NodeOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The read-only expression tooling behind the canvas editor (spec 6): live preview ({@link #evaluate}), the
 * linter ({@link #lint}), the data-picker schema ({@link #upstreamSchema}) and the function registry
 * ({@link #functions}). Nothing here executes a node or writes anything.
 *
 * <p><b>Permissions.</b> {@code evaluate} and {@code upstreamSchema} return values read from pins or from the
 * last run, which is run data (often real customer data): EDITOR, the same bar as reading a pin. {@code lint}
 * sees only the saved graph and whether a pin is stale, so VIEWER is enough. Callers below that level get the
 * usual access-denied response of {@link WorkflowService#requireAccess}.
 *
 * <p><b>Kill switch.</b> {@code app.workflow.expressions.enabled=false} makes evaluate and lint answer 409 with a
 * clear message (the runtime resolver is off too); pins and the schema/function reads keep working.
 *
 * <p><b>Limits.</b> Evaluation uses exactly the engine limits a run uses (spec 3.5), plus a per-user rate limit
 * ({@link WorkflowExpressionRateLimiter}) and the request caps below.
 */
@Service
public class WorkflowExpressionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExpressionService.class);

    static final int MAX_LINT_ITEMS = 200;
    static final int MAX_TEMPLATE_CHARS = 20_000;
    static final int MAX_SUPPLIED_NODES = 50;

    private final WorkflowService workflowService;
    private final WorkflowPinStore pinStore;
    private final RunDataStore runData;
    private final WorkflowRunRepository runs;
    private final WorkflowExpressionRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final UpstreamSchemaInferer schemaInferer;
    private final ExpressionPreviewSources sources;
    private final ExpressionEngine engine;
    private final boolean enabled;

    public WorkflowExpressionService(
        WorkflowService workflowService,
        WorkflowPinStore pinStore,
        RunDataStore runData,
        WorkflowRunRepository runs,
        WorkflowExpressionRateLimiter rateLimiter,
        ObjectMapper objectMapper,
        @Value("${app.workflow.expressions.enabled:true}") boolean enabled,
        @Value("${app.workflow.expressions.strict:false}") boolean strict
    ) {
        this.workflowService = workflowService;
        this.pinStore = pinStore;
        this.runData = runData;
        this.runs = runs;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.schemaInferer = new UpstreamSchemaInferer(objectMapper);
        this.sources = new ExpressionPreviewSources(pinStore, runData, runs);
        this.engine = new ExpressionEngine(Limits.defaults().withStrict(strict));
        this.enabled = enabled;
    }

    // ---- evaluate

    public EvaluateResponse evaluate(UUID workflowId, EvaluateRequest request) {
        WorkflowDefinition definition = workflowService.requireAccess(workflowId, PermissionLevel.EDITOR);
        requireEnabled();
        rateLimiter.check();
        if (request == null || request.template() == null) {
            throw bad("A template is required.");
        }
        String template = request.template();
        if (template.length() > MAX_TEMPLATE_CHARS) {
            throw bad("The template is " + template.length() + " characters; the limit is " + MAX_TEMPLATE_CHARS + ".");
        }
        String dataSource = request.dataSource() == null ? "" : request.dataSource();
        if (!Set.of("pinned", "lastRun", "supplied").contains(dataSource)) {
            throw bad("dataSource must be one of pinned, lastRun, supplied.");
        }
        Supplied supplied = request.supplied();
        if (supplied != null) {
            validateSupplied(supplied);
        }

        WorkflowGraphDto graph = workflowService.graph(workflowId);
        WorkflowNodeDto node = request.nodeId() == null ? null : findNode(graph, request.nodeId());
        Set<String> warnings = new LinkedHashSet<>();
        ExpressionPreviewSources.Source source = switch (dataSource) {
            case "pinned" -> sources.pinned(workflowId, graph);
            case "lastRun" -> sources.lastRun(workflowId, graph, warnings);
            default -> sources.supplied(graph, supplied);
        };

        String input = "";
        String inputSourceId = null;
        if (dataSource.equals("supplied") && supplied != null && supplied.input() != null) {
            input = supplied.input();
        } else if (node != null) {
            for (WorkflowEdgeDto edge : graph.edges()) {
                if (node.id().equals(edge.target())) {
                    NodeOutput upstream = source.byId(edge.source());
                    if (upstream != null) {
                        input = upstream.output();
                        inputSourceId = upstream.id();
                        break;
                    }
                }
            }
            if (inputSourceId == null && hasIncoming(graph, node.id())) {
                warnings.add("No " + source.label() + " data for the node feeding this one, so $json is empty.");
            }
        }

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", source.runId());
        run.put("startedAt", source.runStartedAt());
        run.put("mode", "draft");
        run.put("requestedBy", null);
        Map<String, Object> wf = new LinkedHashMap<>();
        wf.put("id", workflowId.toString());
        wf.put("name", definition.getName());
        wf.put("version", null);

        ExpressionPreviewSources.SourceContext ctx = new ExpressionPreviewSources.SourceContext(input, inputSourceId, source, graph, run, wf, warnings);
        boolean legacy = node != null && ExpressionFields.of(node.type()).stream()
            .anyMatch(f -> f.legacy() != ExpressionFields.Legacy.NONE);
        try {
            Object value = engine.renderValue(template, ctx, legacy);
            String rendered = engine.render(template, ctx, legacy);
            Object safe = serialisable(value, rendered);
            return new EvaluateResponse(true, safe, typeName(safe), rendered, null,
                new ArrayList<>(ctx.sources), new ArrayList<>(warnings));
        } catch (ExpressionException e) {
            return failure(template, e.getMessage(), e.start(), e.end(), ctx, warnings);
        } catch (RuntimeException e) {
            // Nothing but ExpressionException is meant to escape the engine; if something does, the preview
            // reports it instead of turning a typing-debounce call into a 500.
            log.warn("Expression preview failed unexpectedly for workflow {}: {}", workflowId, e.toString());
            return failure(template, "The expression could not be evaluated.", 0, template.length(), ctx, warnings);
        }
    }

    private EvaluateResponse failure(
        String template, String message, int start, int end, ExpressionPreviewSources.SourceContext ctx, Set<String> warnings
    ) {
        int s = start < 0 ? 0 : Math.min(start, template.length());
        int e = end < 0 ? template.length() : Math.min(Math.max(end, s), template.length());
        return new EvaluateResponse(false, null, "null", "", new EvaluateError(message, s, e),
            new ArrayList<>(ctx.sources), new ArrayList<>(warnings));
    }

    private Object serialisable(Object value, String rendered) {
        try {
            objectMapper.writeValueAsString(value);
            return value;
        } catch (Exception e) {
            return rendered;
        }
    }

    static String typeName(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof CharSequence) {
            return "string";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof Map) {
            return "object";
        }
        if (v instanceof Collection || v.getClass().isArray()) {
            return "array";
        }
        return "string";
    }

    private void validateSupplied(Supplied supplied) {
        if (supplied.input() != null && supplied.input().length() > WorkflowPinStore.MAX_PIN_CHARS) {
            throw bad("supplied.input is too large.");
        }
        if (supplied.nodes() != null) {
            if (supplied.nodes().size() > MAX_SUPPLIED_NODES) {
                throw bad("At most " + MAX_SUPPLIED_NODES + " supplied nodes.");
            }
            for (String text : supplied.nodes().values()) {
                if (text != null && text.length() > WorkflowPinStore.MAX_PIN_CHARS) {
                    throw bad("A supplied node output is too large.");
                }
            }
        }
    }

    // ---- lint

    public LintResponse lint(UUID workflowId, LintRequest request) {
        workflowService.requireAccess(workflowId, PermissionLevel.VIEWER);
        requireEnabled();
        rateLimiter.check();
        List<LintItem> items = request == null || request.items() == null ? List.of() : request.items();
        if (items.size() > MAX_LINT_ITEMS) {
            throw bad("At most " + MAX_LINT_ITEMS + " fields can be checked in one request.");
        }
        WorkflowGraphDto graph = workflowService.graph(workflowId);
        Map<String, WorkflowNodeDto> byId = new HashMap<>();
        Map<String, Integer> labelCounts = new HashMap<>();
        Map<String, String> labelToId = new LinkedHashMap<>();
        for (WorkflowNodeDto n : graph.nodes()) {
            byId.put(n.id(), n);
            labelCounts.merge(n.label(), 1, Integer::sum);
            if (labelToId.putIfAbsent(n.label(), n.id()) != null) {
                // A second node with the same label: its id is only addressable as $node.id("..."), and the
                // linter checks ids against the map's VALUES, so carry it under a key no label can equal.
                labelToId.put("\u0000id:" + n.id(), n.id());
            }
        }
        Set<String> ambiguous = new HashSet<>();
        labelCounts.forEach((label, count) -> {
            if (count > 1) {
                ambiguous.add(label);
            }
        });
        Map<String, List<String>> incoming = ExpressionNodeRefs.incomingEdges(graph);
        Map<String, WorkflowNodePin> pins = null;

        List<LintDiagnostic> out = new ArrayList<>();
        for (LintItem item : items) {
            if (item == null || item.template() == null || item.template().isEmpty()) {
                continue;
            }
            String template = item.template();
            if (template.length() > MAX_TEMPLATE_CHARS) {
                out.add(diag(item, "error", "PARSE_ERROR",
                    "The field is " + template.length() + " characters; expressions are checked up to " + MAX_TEMPLATE_CHARS + ".",
                    0, 0));
                continue;
            }
            WorkflowNodeDto owner = item.nodeId() == null ? null : byId.get(item.nodeId());
            if (pins == null && template.contains("{{")) {
                pins = new HashMap<>();
                for (WorkflowNodePin p : pinStore.list(workflowId)) {
                    pins.put(p.getNodeId(), p);
                }
            }
            Set<String> knownFields = owner == null ? null : knownInputFields(owner, incoming, pins);
            LintContext lc = new LintContext(labelToId, ambiguous, knownFields);
            for (Diagnostic d : engine.lint(template, lc)) {
                out.add(diag(item, d.severity().name().toLowerCase(Locale.ROOT), mapCode(d.code()), d.message(),
                    d.start(), d.end()));
            }
            if (owner != null) {
                out.addAll(referenceDiagnostics(item, owner, graph, incoming, pins == null ? Map.of() : pins));
            }
        }
        return new LintResponse(out);
    }

    /** The linter's own code for "two nodes share this label" is AMBIGUOUS_NODE; the API contract calls it AMBIGUOUS_LABEL. */
    static String mapCode(String code) {
        return "AMBIGUOUS_NODE".equals(code) ? "AMBIGUOUS_LABEL" : code;
    }

    private static LintDiagnostic diag(LintItem item, String severity, String code, String message, int start, int end) {
        return new LintDiagnostic(item.nodeId(), item.field(), severity, code, message, Math.max(start, 0), Math.max(end, 0));
    }

    /** NOT_UPSTREAM and STALE_PIN for each {@code $node[...]} reference the expression really makes. */
    private List<LintDiagnostic> referenceDiagnostics(
        LintItem item, WorkflowNodeDto owner, WorkflowGraphDto graph, Map<String, List<String>> incoming,
        Map<String, WorkflowNodePin> pins
    ) {
        List<LintDiagnostic> out = new ArrayList<>();
        Set<String> astLabels = engine.referencedNodeLabels(item.template());
        Set<String> astIds = engine.referencedNodeIds(item.template());
        if (astLabels.isEmpty() && astIds.isEmpty()) {
            return out;
        }
        Set<String> ancestors = ExpressionNodeRefs.ancestorsOf(owner.id(), incoming);
        for (ExpressionNodeRefs.NodeRef ref : ExpressionNodeRefs.scanNodeRefs(item.template())) {
            boolean byLabel = ref.kind() == ExpressionNodeRefs.RefKind.LABEL;
            if (byLabel ? !astLabels.contains(ref.name()) : !astIds.contains(ref.name())) {
                continue;
            }
            List<WorkflowNodeDto> targets = new ArrayList<>();
            for (WorkflowNodeDto n : graph.nodes()) {
                if (byLabel ? ref.name().equals(n.label()) : ref.name().equals(n.id())) {
                    targets.add(n);
                }
            }
            if (targets.isEmpty()) {
                continue; // unknown name: the engine's UNKNOWN_NODE already covers it
            }
            boolean anyUpstream = targets.stream().anyMatch(n -> ancestors.contains(n.id()));
            if (!anyUpstream) {
                String name = targets.get(0).label();
                out.add(diag(item, "error", "NOT_UPSTREAM",
                    "'" + name + "' is not upstream of this node, so it has never run when this field is evaluated.",
                    ref.start(), ref.end()));
                continue;
            }
            for (WorkflowNodeDto n : targets) {
                WorkflowNodePin pin = ancestors.contains(n.id()) ? pins.get(n.id()) : null;
                if (pin != null && pin.getConfigHash() != null
                    && !pin.getConfigHash().equals(WorkflowPinService.configHash(n))) {
                    out.add(diag(item, "warning", "STALE_PIN",
                        "The pinned data for '" + n.label() + "' was captured before its configuration changed, "
                            + "so it may no longer match what the node produces. Re-pin it from a fresh run.",
                        ref.start(), ref.end()));
                    break;
                }
            }
        }
        return out;
    }

    /** Top-level fields of the first directly-upstream pinned output that is a JSON object; null when unknown. */
    private Set<String> knownInputFields(
        WorkflowNodeDto owner, Map<String, List<String>> incoming, Map<String, WorkflowNodePin> pins
    ) {
        if (pins == null || pins.isEmpty()) {
            return null;
        }
        for (String upstream : incoming.getOrDefault(owner.id(), List.of())) {
            WorkflowNodePin pin = pins.get(upstream);
            if (pin == null) {
                continue;
            }
            try {
                JsonNode tree = objectMapper.readTree(pin.getDataText());
                if (tree != null && tree.isObject()) {
                    Set<String> names = new HashSet<>();
                    tree.fieldNames().forEachRemaining(names::add);
                    return names;
                }
            } catch (Exception ignored) {
                // pinned text that is not JSON simply tells us nothing about field names
            }
        }
        return null;
    }

    // ---- upstream schema

    public UpstreamSchemaResponse upstreamSchema(UUID workflowId, String nodeId) {
        workflowService.requireAccess(workflowId, PermissionLevel.EDITOR);
        WorkflowGraphDto graph = workflowService.graph(workflowId);
        WorkflowNodeDto node = findNode(graph, nodeId);
        if (node == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Node '" + nodeId + "' is not in this workflow's saved graph - save the canvas first.");
        }
        Map<String, WorkflowNodeDto> byId = new HashMap<>();
        for (WorkflowNodeDto n : graph.nodes()) {
            byId.put(n.id(), n);
        }
        Map<String, WorkflowNodePin> pins = new HashMap<>();
        for (WorkflowNodePin p : pinStore.list(workflowId)) {
            pins.put(p.getNodeId(), p);
        }
        UUID lastRunId = null;
        List<String> upstream = ExpressionNodeRefs.orderedAncestors(nodeId, ExpressionNodeRefs.incomingEdges(graph));
        boolean needsLastRun = upstream.stream().anyMatch(id -> !pins.containsKey(id));
        if (needsLastRun) {
            lastRunId = runs.findFirstByWorkflowIdOrderByStartedAtDesc(workflowId).map(WorkflowRun::getId).orElse(null);
        }

        List<SchemaNode> nodes = new ArrayList<>();
        for (String id : upstream) {
            WorkflowNodeDto n = byId.get(id);
            if (n == null) {
                continue;
            }
            WorkflowNodePin pin = pins.get(id);
            String text = null;
            String sourceName = "none";
            if (pin != null) {
                text = pin.getDataText();
                sourceName = "pinned";
            } else if (lastRunId != null) {
                RunDataStore.Stored s = runData.latestById(lastRunId, id);
                if (s != null) {
                    text = s.outputText();
                    sourceName = "lastRun";
                }
            }
            List<SchemaPath> paths = text == null ? List.of() : schemaInferer.infer(text);
            nodes.add(new SchemaNode(id, n.label(), sourceName, paths));
        }
        return new UpstreamSchemaResponse(nodes);
    }

    // ---- functions

    /** The built-in function registry, one entry per function, in registry order. */
    public List<FunctionDoc> functions() {
        List<FunctionDoc> out = new ArrayList<>();
        for (BuiltIn b : BuiltIns.registry().values()) {
            out.add(new FunctionDoc(b.name(), b.category().toLowerCase(Locale.ROOT), b.signature(), b.doc(), b.example()));
        }
        return out;
    }

    // ---- graph helpers

    private void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Workflow expressions are switched off on this deployment (app.workflow.expressions.enabled=false).");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static WorkflowNodeDto findNode(WorkflowGraphDto graph, String nodeId) {
        for (WorkflowNodeDto n : graph.nodes()) {
            if (nodeId != null && nodeId.equals(n.id())) {
                return n;
            }
        }
        return null;
    }


    private static boolean hasIncoming(WorkflowGraphDto graph, String nodeId) {
        for (WorkflowEdgeDto e : graph.edges()) {
            if (nodeId.equals(e.target())) {
                return true;
            }
        }
        return false;
    }

}
