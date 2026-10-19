package com.agenticai.core.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The node config keys that get {@code {{ }}} resolution (spec 4.2), per node type. {@link
 * WorkflowNodeExecutor#executeNode} resolves exactly these before the type handler runs; every other
 * config value reaches the handler untouched. The web list ({@code EXPRESSION_FIELDS} in nodeDefs.ts) MUST
 * stay identical - a key listed there but not here renders as an expression field that does nothing.
 *
 * <p>Path grammar (same as the web): {@code a.b} nests, {@code list[].value} is the {@code value} of every
 * element of the {@code list} array (a plain object or a raw JSON string of either shape is accepted too,
 * matching how the HTTP node reads its headers).
 */
final class ExpressionFields {

    /** How a string at a field's path is resolved. */
    enum Mode {
        /** Rendered to text. */
        TEXT,
        /**
         * Text that is usually JSON (tool args, device payload): when it parses as a JSON object/array the
         * strings inside are resolved and the document re-serialised, so a value containing quotes cannot
         * break the JSON; a single-expression string keeps its type. Otherwise rendered as TEXT.
         */
        JSON_TEXT,
        /** Rendered once per item by the handler itself (bulk_email subject/body: one render per recipient). */
        PER_ITEM,
        /** Whole field is a bare expression without braces (condition.expression); read by the handler. */
        BARE
    }

    /** The placeholder algorithm this field used before expressions; only consulted when the kill switch is off. */
    enum Legacy {
        /** Never templated before: left untouched when expressions are disabled. */
        NONE,
        /** Only the literal {@code {{input}}} was replaced (agent inputMapping.query). */
        INPUT_ONLY,
        /** Sequential {@code {{key}}} replace over {input, top-level fields} (transform template, http). */
        MAP,
        /** crm_lead regex renderer: unknown or null keys render as empty text. */
        CRM
    }

    record Field(String path, Mode mode, Legacy legacy, Predicate<Map<String, Object>> when) {

        boolean appliesTo(Map<String, Object> config) {
            return when == null || when.test(config);
        }
    }

    private static final Map<String, List<Field>> BY_TYPE = new LinkedHashMap<>();

    static {
        BY_TYPE.put("agent", List.of(f("inputMapping.query", Mode.TEXT, Legacy.INPUT_ONLY)));
        BY_TYPE.put("condition", List.of(f("expression", Mode.BARE, Legacy.NONE)));
        BY_TYPE.put("code", List.of(new Field("template", Mode.TEXT, Legacy.MAP,
            c -> "template".equals(str(c.get("operation"))))));
        BY_TYPE.put("http", List.of(
            f("url", Mode.TEXT, Legacy.MAP),
            new Field("body", Mode.TEXT, Legacy.MAP, c -> !"none".equalsIgnoreCase(strOr(c.get("bodyType"), "none"))),
            f("headers[].value", Mode.TEXT, Legacy.MAP),
            f("query[].value", Mode.TEXT, Legacy.MAP)));
        BY_TYPE.put("tool", List.of(f("argsJson", Mode.JSON_TEXT, Legacy.NONE)));
        BY_TYPE.put("device_control", List.of(f("payload", Mode.JSON_TEXT, Legacy.NONE)));
        BY_TYPE.put("output", texts(Legacy.NONE,
            "emailTo", "telegramChatId", "teamsConversationId", "whatsappTo", "driveFolderId", "driveFileName", "fileName",
            "whatsappMessage", "whatsappTemplateParams[]"));
        BY_TYPE.put("bulk_email", List.of(
            f("subject", Mode.PER_ITEM, Legacy.MAP),
            f("body", Mode.PER_ITEM, Legacy.MAP),
            f("replyTo", Mode.TEXT, Legacy.NONE)));
        // wait: resolved by the engine itself (WorkflowNodeExecutor#resolveConfig), not by a handler.
        BY_TYPE.put("wait", texts(Legacy.NONE, "until", "duration"));
        BY_TYPE.put("crm_lead", texts(Legacy.CRM,
            "action", "leadId", "email", "name", "phone", "company", "title", "source", "stage",
            "score", "value", "tags", "owner", "notes", "note", "activityType", "activityBody"));
        // Pure data nodes (WorkflowDataOps). Only the comparison / assignment VALUES and the merge sources are
        // expression fields; field paths and operators are structure, never templated. Each is evaluated once for
        // the node (against its whole input); per-item values belong in a Loop body where $json is the item.
        BY_TYPE.put("set", List.of(f("assignments[].value", Mode.TEXT, Legacy.NONE)));
        BY_TYPE.put("filter", List.of(f("conditions[].value", Mode.TEXT, Legacy.NONE)));
        BY_TYPE.put("switch", List.of(f("cases[].conditions[].value", Mode.TEXT, Legacy.NONE)));
        BY_TYPE.put("limit", List.of(f("count", Mode.TEXT, Legacy.NONE)));
        BY_TYPE.put("merge", List.of(f("sources[]", Mode.TEXT, Legacy.NONE)));
        // Dify-style AI nodes: only the data-bearing fields are evaluated; class / parameter definitions are static
        // author config. The web EXPRESSION_FIELDS (nodeDefs.ts) lists the same four.
        BY_TYPE.put("question_classifier", texts(Legacy.NONE, "text"));
        BY_TYPE.put("parameter_extractor", texts(Legacy.NONE, "text"));
        BY_TYPE.put("variable_aggregator", texts(Legacy.NONE, "sources[]"));
        BY_TYPE.put("document_extractor", texts(Legacy.NONE, "source"));
        // data_table: filter values and the values object are expressions; table / key / sort / limit too.
        BY_TYPE.put("data_table", List.of(
            f("table", Mode.TEXT, Legacy.NONE),
            f("keyColumn", Mode.TEXT, Legacy.NONE),
            f("valuesJson", Mode.JSON_TEXT, Legacy.NONE),
            f("filter[].value", Mode.TEXT, Legacy.NONE),
            f("sortColumn", Mode.TEXT, Legacy.NONE),
            f("limit", Mode.TEXT, Legacy.NONE)));
    }

    private ExpressionFields() {
    }

    /** The resolution fields of a node type; empty for types with none. */
    static List<Field> of(String type) {
        if (type == null) {
            return List.of();
        }
        return BY_TYPE.getOrDefault(type.toLowerCase(Locale.ROOT), List.of());
    }

    /** Every node type that has resolution fields (the parity test compares this set with the web's). */
    static java.util.Set<String> types() {
        return java.util.Collections.unmodifiableSet(BY_TYPE.keySet());
    }

    /** Field paths in declaration order - the list the web mirrors. */
    static List<String> paths(String type) {
        List<String> out = new ArrayList<>();
        for (Field f : of(type)) {
            out.add(f.path());
        }
        return out;
    }

    private static Field f(String path, Mode mode, Legacy legacy) {
        return new Field(path, mode, legacy, null);
    }

    private static List<Field> texts(Legacy legacy, String... keys) {
        List<Field> out = new ArrayList<>();
        for (String k : keys) {
            out.add(f(k, Mode.TEXT, legacy));
        }
        return List.copyOf(out);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String strOr(Object o, String fallback) {
        return o == null || o.toString().isBlank() ? fallback : o.toString().strip();
    }
}
