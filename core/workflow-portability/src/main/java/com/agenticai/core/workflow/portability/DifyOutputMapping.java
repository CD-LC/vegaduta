package com.agenticai.core.workflow.portability;

import com.agenticai.core.agents.importer.AgentImportJson;
import com.agenticai.core.agents.importer.AgentImportText;
import com.agenticai.core.workflow.portability.DifyImportModel.Ctx;
import com.agenticai.core.workflow.portability.DifyImportModel.Exit;
import com.agenticai.core.workflow.portability.DifyImportModel.Level;
import com.agenticai.core.workflow.portability.DifyImportModel.Mapped;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Maps the Dify nodes that produce text for {@link DifyImportMapper}: a template transform becomes
 * a Transform (template), and an answer / end becomes a Transform that builds the text followed by
 * a log Output.
 */
final class DifyOutputMapping {

    private static final int TEXT_CAP_CHARS = 20_000;
    private static final Pattern JINJA_VAR =
            Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    private final DifyReferences references;
    private final DifyNodeFactory factory;

    DifyOutputMapping(DifyReferences references, DifyNodeFactory factory) {
        this.references = references;
        this.factory = factory;
    }

    Mapped templateTransform(Ctx c) {
        String template = AgentImportJson.text(c.data, "template");
        if (template == null) {
            return factory.placeholder(c, "The template node has no template.");
        }
        if (template.contains("{%")
                || template.contains("{#")
                || template.matches("(?s).*\\{\\{[^}]*[|(][^}]*}}.*")) {
            return factory.placeholder(
                    c,
                    "The template uses Jinja logic or filters (loops, conditions, pipes), which the "
                            + "Transform node's plain {{ }} substitution cannot run.");
        }
        Map<String, String> byName = new HashMap<>();
        JsonNode variables = AgentImportJson.arr(c.data, "variables");
        if (variables != null) {
            for (JsonNode v : variables) {
                String name = AgentImportJson.text(v, "variable");
                String expression = references.selectorExpression(c, v.get("value_selector"));
                if (name != null && expression != null) {
                    byName.put(name, expression);
                }
            }
        }
        Matcher m = JINJA_VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String expression = byName.get(m.group(1));
            if (expression == null) {
                c.lose(
                        "The template variable '"
                                + AgentImportText.inline(m.group(1), 40)
                                + "' has no source selector and was left as written.");
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement("{{ " + expression + " }}"));
            }
        }
        m.appendTail(sb);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("operation", "template");
        config.put("template", AgentImportText.truncate(sb.toString(), TEXT_CAP_CHARS));
        return factory.single(c, "code", c.node.title(), config, Level.EXACT);
    }

    Mapped answer(Ctx c, String answerText) {
        Map<String, Object> templateConfig = new LinkedHashMap<>();
        templateConfig.put("operation", "template");
        templateConfig.put(
                "template",
                AgentImportText.truncate(answerText == null ? "" : answerText, TEXT_CAP_CHARS));
        return textThenOutput(c, templateConfig);
    }

    Mapped end(Ctx c) {
        StringBuilder sb = new StringBuilder();
        JsonNode outputs = AgentImportJson.arr(c.data, "outputs");
        if (outputs != null) {
            for (JsonNode o : outputs) {
                String name = AgentImportJson.text(o, "variable");
                String expression = references.selectorExpression(c, o.get("value_selector"));
                if (name != null) {
                    sb.append(name)
                            .append(": ")
                            .append(expression == null ? "" : "{{ " + expression + " }}")
                            .append('\n');
                }
            }
        }
        c.lose(
                "The End node's named outputs were written as 'name: value' lines; structured outputs are not "
                        + "preserved.");
        Map<String, Object> templateConfig = new LinkedHashMap<>();
        templateConfig.put("operation", "template");
        templateConfig.put(
                "template", AgentImportText.truncate(sb.toString().strip(), TEXT_CAP_CHARS));
        return textThenOutput(c, templateConfig);
    }

    /**
     * A Transform that builds the text, then a log Output, so the workflow's result is that text.
     */
    private Mapped textThenOutput(Ctx c, Map<String, Object> templateConfig) {
        Mapped m = new Mapped();
        m.label = c.node.title();
        m.entryId = c.node.id();
        String outId = c.node.id() + "__out";
        Map<String, Object> outConfig = new LinkedHashMap<>();
        outConfig.put("channel", "log");
        Level level = c.approximate || c.refs ? Level.APPROXIMATE : Level.EXACT;
        List<String> notes = new ArrayList<>(c.notes);
        if (c.refs) {
            notes.add(DifyNodeFactory.REFS_NOTE);
        }
        m.level = level;
        m.notes.addAll(notes);
        m.nodes.add(
                PortableNodes.node(
                        c.node.id(),
                        "code",
                        AgentImportText.truncate(c.node.title(), 200),
                        factory.position(c.node),
                        templateConfig,
                        notes,
                        level != Level.EXACT));
        Map<String, Object> outPosition = factory.position(c.node);
        outPosition.put("x", ((Number) outPosition.get("x")).longValue() + 260L);
        m.nodes.add(
                PortableNodes.node(
                        outId,
                        "output",
                        AgentImportText.truncate(c.node.title() + " (output)", 200),
                        outPosition,
                        outConfig,
                        new ArrayList<>(),
                        false));
        m.internalEdges.add(
                PortableNodes.edge("ec_" + c.node.id() + "_out", c.node.id(), null, outId));
        m.exits = handle -> new Exit(outId, null);
        return m;
    }
}
