package com.agenticai.core.workflow;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.ExpressionFields.Field;
import com.agenticai.core.workflow.ExpressionFields.Legacy;
import com.agenticai.core.workflow.ExpressionFields.Mode;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.expression.EvalContext;
import com.agenticai.core.workflow.expression.ExpressionEngine;
import com.agenticai.core.workflow.expression.ExpressionException;
import com.agenticai.core.workflow.expression.Limits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The one place {@code {{ }}} placeholders in node config are evaluated (spec 4.2). {@link
 * WorkflowNodeExecutor#executeNode} calls {@link #resolve} on the node BEFORE dispatching to its
 * type handler, so the handlers no longer substitute anything themselves and a resolved value is
 * never scanned a second time. It runs inside the Temporal activity (executeNode), never in
 * workflow code.
 *
 * <p>Only the config keys listed in {@link ExpressionFields} are touched; everything else reaches
 * the handler as stored. The node passed in is never mutated (it is shared by loop iterations and
 * parallel threads): a changed config gets a copy-on-write node.
 *
 * <p>Kill switch {@code app.workflow.expressions.enabled=false}: fields fall back to the
 * placeholder algorithm they had before expressions existed ({@link Legacy}); fields that never
 * templated stay untouched, and the condition node's expression mode refuses to run rather than
 * route wrongly.
 */
@Component
public class WorkflowExpressionResolver {

    private static final Pattern CRM_PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    /**
     * Who and what is executing: enough to build {@code $run} / {@code $workflow} and to address
     * the run data store.
     */
    public record NodeEvalInfo(
            String workflowId, String workflowName, String runId, String requestedBy) {}

    /**
     * Renders a bulk_email subject/body once per recipient ({@code vars} are that recipient's
     * fields).
     */
    @FunctionalInterface
    public interface RecipientRenderer {
        String render(String field, String template, Map<String, String> vars, int index);

        /**
         * Renders for an HTML body. The default escapes the recipient's fields up front (enough
         * when only those can be substituted); the resolver overrides it to escape every
         * substituted value, including data read from the input, the trigger and upstream nodes.
         */
        default String renderHtml(
                String field, String template, Map<String, String> vars, int index) {
            Map<String, String> escaped = new HashMap<>();
            vars.forEach((k, v) -> escaped.put(k, v == null ? "" : HtmlUtils.htmlEscape(v)));
            return render(field, template, escaped, index);
        }
    }

    private final ExpressionEngine engine;
    private final RunDataStore store;
    private final WorkflowRunRepository runs;
    private final WorkflowRunOutputRecorder recorder;
    private final ObjectMapper objectMapper;
    private final ObjectMapper looseMapper;
    private final WorkflowLegacyTemplates legacyTemplates;
    private final boolean enabled;

    /**
     * Set by Spring (setter, so no constructor changes): feeds {@code $run.waitUrl}; null in plain
     * unit tests.
     */
    private WorkflowWaitResumeTokens waitTokens;

    private boolean waitUrlEnabled;

    /**
     * Wires {@code $run.waitUrl}, the run-scoped resume URL of a webhook-mode Wait node, so an
     * upstream node (an email, an HTTP call, an agent) can hand it to the party who will resume the
     * run. Only present while {@code app.workflow.wait-node.enabled=true} and the deployment can
     * build absolute links.
     */
    @Autowired
    void configureWaitUrl(
            ObjectProvider<WorkflowWaitResumeTokens> tokens,
            @Value("${app.workflow.wait-node.enabled:false}") boolean waitNodeEnabled) {
        this.waitTokens = tokens.getIfAvailable();
        this.waitUrlEnabled = waitNodeEnabled;
    }

    @Autowired
    public WorkflowExpressionResolver(
            ObjectProvider<RunDataStore> store,
            ObjectProvider<WorkflowRunRepository> runs,
            ObjectMapper objectMapper,
            @Value("${app.workflow.expressions.enabled:true}") boolean enabled,
            @Value("${app.workflow.expressions.strict:false}") boolean strict) {
        this(store.getIfAvailable(), runs.getIfAvailable(), objectMapper, enabled, strict);
    }

    WorkflowExpressionResolver(
            RunDataStore store,
            WorkflowRunRepository runs,
            ObjectMapper objectMapper,
            boolean enabled,
            boolean strict) {
        this.store = store;
        this.runs = runs;
        this.recorder = new WorkflowRunOutputRecorder(store);
        this.objectMapper = objectMapper;
        this.legacyTemplates = new WorkflowLegacyTemplates(objectMapper);
        this.looseMapper =
                objectMapper
                        .rebuild()
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .build();
        this.enabled = enabled;
        this.engine = new ExpressionEngine(Limits.defaults().withStrict(strict));
        warmUp();
    }

    private static volatile boolean warmedUp;

    /**
     * Runs one throwaway resolve / condition / recipient render at bean construction, so the
     * resolver's own classes (context, Jackson tree walking, per-field apply) and the library's are
     * initialised at startup and never billed to the first real node's wall budget (default 50 ms).
     * Once per JVM; no run id, so nothing touches the store. Best effort.
     */
    private void warmUp() {
        ExpressionEngine.warmUp();
        if (warmedUp || !enabled) {
            return;
        }
        try {
            NodeEvalInfo info = new NodeEvalInfo("warm", "warm", null, "warm");
            ExpressionScope scope = ExpressionScope.iteration("{\"a\":1}", 0, List.of("{\"a\":1}"));
            WorkflowNodeDto node =
                    new WorkflowNodeDto(
                            "warm",
                            "http",
                            Map.of(),
                            Map.of(
                                    "label",
                                    "warm",
                                    "config",
                                    Map.of(
                                            "url",
                                            "https://warm.invalid/{{ $json.a[0] }}/{{ upper($item) }}/{{input}}",
                                            "headers",
                                            List.of(Map.of("key", "k", "value", "{{ a }}")),
                                            "bodyType",
                                            "json",
                                            "body",
                                            "{\"v\":{{ $json.a[0] ?? 1 }}}")));
            resolve(node, "{\"a\":[1,2]}", info, scope);
            evaluateCondition("$json.a[0] > 0 && $index == 0", "{\"a\":[1,2]}", info, scope);
            recipientRenderer("{}", info, scope)
                    .render("subject", "Hi {{name}} {{ $json.x ?? 'z' }}", Map.of("name", "n"), 0);
            warmedUp = true;
        } catch (RuntimeException | LinkageError ignored) {
            // best effort: a failure here must never stop the bean from constructing
        }
    }

    /** A resolver with no run data store: expressions over the node's own input and scope only. */
    static WorkflowExpressionResolver standalone(ObjectMapper objectMapper) {
        return new WorkflowExpressionResolver(
                (RunDataStore) null, (WorkflowRunRepository) null, objectMapper, true, false);
    }

    boolean isEnabled() {
        return enabled;
    }

    // ---- node resolution

    /**
     * Returns {@code node} with every applicable expression field resolved, or {@code node} itself
     * when nothing needed resolving.
     *
     * @throws WorkflowExpressionException a placeholder failed to parse or evaluate
     */
    public WorkflowNodeDto resolve(
            WorkflowNodeDto node, String input, NodeEvalInfo info, ExpressionScope scope) {
        List<Field> fields = ExpressionFields.of(node.type());
        Map<String, Object> original = node.config();
        if (fields.isEmpty() || original.isEmpty()) {
            return node;
        }
        boolean http = "http".equalsIgnoreCase(node.type());
        WorkflowNodeEvalContext ctx = null;
        Map<String, Object> current = original;
        for (Field field : fields) {
            if (field.mode() == Mode.BARE
                    || field.mode() == Mode.PER_ITEM
                    || !field.appliesTo(original)) {
                continue;
            }
            if (ctx == null) {
                ctx = new WorkflowNodeEvalContext(this, input, info, scope, http);
            }
            final WorkflowNodeEvalContext c = ctx;
            UnaryOperator<Object> leaf = value -> resolveValue(value, field, c, input);
            Object updated = applyPath(current, List.of(field.path().split("\\.")), 0, leaf);
            if (updated != current) {
                @SuppressWarnings("unchecked")
                Map<String, Object> next = (Map<String, Object>) updated;
                current = next;
            }
        }
        if (current == original) {
            return node;
        }
        Map<String, Object> data = new LinkedHashMap<>(node.data());
        data.put("config", current);
        return new WorkflowNodeDto(node.id(), node.type(), node.position(), data);
    }

    /**
     * Walks {@code names} from {@code node}; {@code leaf} resolves what it finds. Returns the same
     * instance when nothing changed.
     */
    private Object applyPath(Object node, List<String> names, int i, UnaryOperator<Object> leaf) {
        if (i == names.size()) {
            return leaf.apply(node);
        }
        if (node instanceof String text) {
            // The container itself may be stored as raw JSON text (headers/query from a textarea).
            Object parsed = parseContainer(text);
            if (parsed == null) {
                return node;
            }
            Object resolved = applyPath(parsed, names, i, leaf);
            return resolved == parsed ? node : resolved;
        }
        if (!(node instanceof Map<?, ?> map)) {
            return node;
        }
        String segment = names.get(i);
        boolean iterate = segment.endsWith("[]");
        String key = iterate ? segment.substring(0, segment.length() - 2) : segment;
        Object child = map.get(key);
        if (child == null) {
            return node;
        }
        Object updated =
                iterate
                        ? applyEach(child, names, i + 1, leaf)
                        : applyPath(child, names, i + 1, leaf);
        if (updated == child) {
            return node;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            copy.put(String.valueOf(e.getKey()), e.getValue());
        }
        copy.put(key, updated);
        return copy;
    }

    private Object applyEach(
            Object child, List<String> names, int next, UnaryOperator<Object> leaf) {
        if (child instanceof String text) {
            Object parsed = parseContainer(text);
            if (parsed == null) {
                return child;
            }
            Object resolved = applyEach(parsed, names, next, leaf);
            return resolved == parsed ? child : resolved;
        }
        if (child instanceof List<?> list) {
            List<Object> out = null;
            for (int k = 0; k < list.size(); k++) {
                Object element = list.get(k);
                Object updated = applyPath(element, names, next, leaf);
                if (updated != element && out == null) {
                    out = new ArrayList<>(list);
                }
                if (out != null) {
                    out.set(k, updated);
                }
            }
            return out == null ? child : out;
        }
        if (child instanceof Map<?, ?> map) {
            // Plain {name: value} object form: every value is a "value".
            Map<String, Object> out = null;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object updated = leaf.apply(e.getValue());
                if (updated != e.getValue() && out == null) {
                    out = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> all : map.entrySet()) {
                        out.put(String.valueOf(all.getKey()), all.getValue());
                    }
                }
                if (out != null) {
                    out.put(String.valueOf(e.getKey()), updated);
                }
            }
            return out == null ? child : out;
        }
        return child;
    }

    private Object parseContainer(String text) {
        String t = text.stripLeading();
        if (t.isEmpty() || (t.charAt(0) != '[' && t.charAt(0) != '{')) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(text, Object.class);
            return parsed instanceof Map || parsed instanceof List ? parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolves one config value: strings by the field's mode, maps and lists deeply, anything else
     * as is.
     */
    private Object resolveValue(
            Object value, Field field, WorkflowNodeEvalContext ctx, String input) {
        if (value instanceof String s) {
            if (!enabled) {
                return legacy(s, field, ctx, input);
            }
            if (!s.contains("{{")) {
                return s;
            }
            if (field.legacy() == Legacy.CRM) {
                s = blankMissingCrmKeys(s, ctx);
            }
            ctx.rawInputWins = field.legacy() == Legacy.INPUT_ONLY || field.legacy() == Legacy.CRM;
            try {
                return field.mode() == Mode.JSON_TEXT
                        ? resolveJsonText(s, field, ctx)
                        : renderText(s, field.path(), ctx, field.legacy() != Legacy.NONE);
            } finally {
                ctx.rawInputWins = false;
            }
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = null;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object updated = resolveValue(e.getValue(), field, ctx, input);
                if (updated != e.getValue() && out == null) {
                    out = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> all : map.entrySet()) {
                        out.put(String.valueOf(all.getKey()), all.getValue());
                    }
                }
                if (out != null) {
                    out.put(String.valueOf(e.getKey()), updated);
                }
            }
            return out == null ? value : out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = null;
            for (int k = 0; k < list.size(); k++) {
                Object updated = resolveValue(list.get(k), field, ctx, input);
                if (updated != list.get(k) && out == null) {
                    out = new ArrayList<>(list);
                }
                if (out != null) {
                    out.set(k, updated);
                }
            }
            return out == null ? value : out;
        }
        return value;
    }

    /**
     * JSON-shaped text (tool args, device payload): resolve the STRINGS inside the parsed document,
     * so a substituted value with quotes or newlines cannot break the JSON, and so a string that is
     * exactly one expression keeps its type (a number stays a number). Text that is not a JSON
     * object/array is plain text.
     */
    private Object resolveJsonText(String s, Field field, WorkflowNodeEvalContext ctx) {
        Object parsed = parseContainer(s);
        if (parsed == null) {
            return renderText(s, field.path(), ctx);
        }
        Object resolved = resolveTyped(parsed, field, ctx);
        if (resolved == parsed) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(resolved);
        } catch (Exception e) {
            throw new WorkflowExpressionException(
                    "Expression in field \""
                            + field.path()
                            + "\": resolved value could not be written as JSON");
        }
    }

    private Object resolveTyped(Object value, Field field, WorkflowNodeEvalContext ctx) {
        if (value instanceof String s) {
            return s.contains("{{") ? renderTyped(s, field.path(), ctx) : s;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            boolean changed = false;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object updated = resolveTyped(e.getValue(), field, ctx);
                changed |= updated != e.getValue();
                out.put(String.valueOf(e.getKey()), updated);
            }
            return changed ? out : value;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            boolean changed = false;
            for (Object element : list) {
                Object updated = resolveTyped(element, field, ctx);
                changed |= updated != element;
                out.add(updated);
            }
            return changed ? out : value;
        }
        return value;
    }

    // ---- rendering

    /**
     * crm_lead has always rendered a placeholder whose key is missing (or null) in the input as
     * EMPTY text, so a default config like {@code email: "{{email}}"} leaves the field blank
     * instead of saving "{{email}}" as an address. The engine deliberately leaves unknown bare
     * names literal everywhere else, so this node keeps its own rule: a plain key (or the first
     * segment of a dotted one) that the input does not carry is blanked before the engine sees the
     * text. {@code input} and anything that is not a simple key are left alone.
     */
    private String blankMissingCrmKeys(String template, WorkflowNodeEvalContext ctx) {
        JsonNode tree = ctx.inputTree();
        Matcher m = CRM_PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            int dot = key.indexOf('.');
            String head = dot < 0 ? key : key.substring(0, dot);
            boolean present =
                    "input".equals(head)
                            || (tree != null
                                    && tree.isObject()
                                    && tree.has(head)
                                    && !tree.get(head).isNull());
            boolean exactKey =
                    tree != null && tree.isObject() && tree.has(key) && !tree.get(key).isNull();
            m.appendReplacement(sb, Matcher.quoteReplacement(present || exactKey ? m.group() : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    String renderText(String template, String field, EvalContext ctx) {
        return renderText(template, field, ctx, false);
    }

    /**
     * {@code legacyLiteral}: the field templated before expressions existed, so foreign {{...}}
     * text stays literal.
     */
    String renderText(String template, String field, EvalContext ctx, boolean legacyLiteral) {
        return renderText(template, field, ctx, legacyLiteral, null);
    }

    private String renderText(
            String template,
            String field,
            EvalContext ctx,
            boolean legacyLiteral,
            UnaryOperator<String> post) {
        if (template == null || !template.contains("{{")) {
            return template;
        }
        WorkflowVaultRefGuard.Protected p = WorkflowVaultRefGuard.protect(template);
        try {
            return p.restore(
                    WorkflowVaultRefGuard.neutralizeVaultRefs(
                            engine.render(p.text(), ctx, legacyLiteral, post)));
        } catch (ExpressionException e) {
            throw WorkflowExpressionException.of(p.text(), field, e);
        }
    }

    private Object renderTyped(String template, String field, EvalContext ctx) {
        WorkflowVaultRefGuard.Protected p = WorkflowVaultRefGuard.protect(template);
        try {
            Object v = WorkflowVaultRefGuard.neutralizeVaultRefs(engine.renderValue(p.text(), ctx));
            return v instanceof String s ? p.restore(s) : v;
        } catch (ExpressionException e) {
            throw WorkflowExpressionException.of(p.text(), field, e);
        }
    }

    // ---- condition expression mode

    /**
     * Evaluates the condition node's bare boolean expression. Truthiness: null, false, the number 0
     * and the empty string are false; everything else (including non-empty text such as "false",
     * and any array or object) is true - write {@code $json.flag == "false"} or {@code
     * toBoolean(...)} for string flags.
     */
    boolean evaluateCondition(
            String expression, String input, NodeEvalInfo info, ExpressionScope scope) {
        if (!enabled) {
            throw new WorkflowExpressionException(
                    "Condition expressions are disabled on this deployment (app.workflow.expressions.enabled=false).");
        }
        String bare = expression == null ? "" : expression.strip();
        if (bare.startsWith("{{") && bare.endsWith("}}") && bare.length() > 4) {
            bare = bare.substring(2, bare.length() - 2).strip();
        }
        if (bare.isEmpty()) {
            throw new WorkflowExpressionException("Condition expression is empty.");
        }
        try {
            return truthy(
                    engine.evaluate(
                            bare, new WorkflowNodeEvalContext(this, input, info, scope, false)));
        } catch (ExpressionException e) {
            throw WorkflowExpressionException.of(
                    "{{" + bare + "}}", "expression", shiftIntoBraces(e));
        }
    }

    private static ExpressionException shiftIntoBraces(ExpressionException e) {
        return e.start() < 0
                ? e
                : new ExpressionException(e.getMessage(), e.start() + 2, e.end() + 2);
    }

    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0.0 && !Double.isNaN(n.doubleValue());
        }
        if (v instanceof CharSequence cs) {
            return cs.length() > 0;
        }
        return true;
    }

    // ---- bulk_email

    /**
     * The per-recipient renderer handed to {@link BulkEmailNodeHandler}; bare {@code {{name}}}
     * reads the recipient.
     */
    public RecipientRenderer recipientRenderer(
            String input, NodeEvalInfo info, ExpressionScope scope) {
        if (!enabled) {
            return (field, template, vars, index) ->
                    WorkflowNodeSupport.applyTemplate(template, vars);
        }
        WorkflowNodeEvalContext base = new WorkflowNodeEvalContext(this, input, info, scope, false);
        return new RecipientRenderer() {
            @Override
            public String render(
                    String field, String template, Map<String, String> vars, int index) {
                return renderText(
                        template,
                        field,
                        new WorkflowRecipientEvalContext(objectMapper, base, vars, index),
                        true);
            }

            /**
             * Escapes each substituted value exactly once, whether it came from the recipient,
             * $json, $trigger or $node.
             */
            @Override
            public String renderHtml(
                    String field, String template, Map<String, String> vars, int index) {
                return renderText(
                        template,
                        field,
                        new WorkflowRecipientEvalContext(objectMapper, base, vars, index),
                        true,
                        HtmlUtils::htmlEscape);
            }
        };
    }

    // ---- recording

    /**
     * Persists a finished node's output for later expressions. Best effort: a store failure never
     * fails the node.
     */
    void record(
            NodeEvalInfo info,
            WorkflowNodeDto node,
            ExpressionScope scope,
            String input,
            NodeExecutionResult result) {
        recorder.record(info, node, scope, input, result);
    }

    boolean recordingFailed(UUID runId) {
        return recorder.recordingFailed(runId);
    }

    // ---- kill-switch fallbacks (the algorithms fields used before expressions existed)

    private String legacy(String s, Field field, WorkflowNodeEvalContext ctx, String input) {
        if (!s.contains("{{")) {
            return s;
        }
        return switch (field.legacy()) {
            case NONE -> s;
            case INPUT_ONLY -> s.replace("{{input}}", input == null ? "" : input);
            case MAP ->
                    WorkflowNodeSupport.applyTemplate(
                            s, legacyTemplates.legacyValues(input, ctx.withInputJson));
            case CRM -> legacyTemplates.legacyCrm(s, input);
        };
    }

    // ---- what the evaluation context reads

    RunDataStore store() {
        return store;
    }

    WorkflowRunRepository runs() {
        return runs;
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    /**
     * The run-scoped resume URL, or null when waits are off / unconfigured / there is no tenant or
     * run.
     */
    String runWaitUrl(UUID runId) {
        if (!waitUrlEnabled || waitTokens == null || runId == null) {
            return null;
        }
        try {
            String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
            return waitTokens.runResumeUrl(tenantId, runId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    Object parseLoose(String text) {
        if (text == null) {
            return null;
        }
        try {
            JsonNode n = looseMapper.readTree(text);
            return n == null || n.isMissingNode() ? text : n;
        } catch (Exception e) {
            return text;
        }
    }
}
