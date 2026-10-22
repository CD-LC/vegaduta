package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.GenerateGraphResponse.ExplanationStep;
import com.agenticai.core.workflow.dto.GenerateGraphResponse.InsertedApproval;
import com.agenticai.core.workflow.dto.GenerateGraphResponse.RequirementItem;
import com.agenticai.core.workflow.dto.GenerateGraphResponse.Requirements;
import com.agenticai.core.workflow.dto.GenerateGraphResponse.SuggestedSchedule;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.guidance.WorkflowGraphExplainer;
import com.agenticai.core.workflow.guidance.WorkflowSendApprovalGuard;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The deterministic half of the AI workflow generator: the prompt sections that are specific to a
 * direct "describe it" generation, and everything derived from the model's reply AFTER it has been
 * parsed (schedule suggestion, requirements checklist, explanation). Static and Spring-free so each
 * piece is unit-testable without an LLM; {@link WorkflowGraphGeneratorService} only orchestrates.
 */
final class WorkflowGeneratorSupport {

    /**
     * Hard ceiling on the current graph sent to the model for an edit, whatever the output budget.
     */
    static final int MAX_EDIT_GRAPH_CHARS = 24_000;

    /**
     * Conservative characters-per-output-token for graph JSON (ids, braces and quotes tokenise
     * poorly).
     */
    static final double EDIT_CHARS_PER_OUTPUT_TOKEN = 2.5;

    static final int MAX_NOTES = 8;
    static final int MAX_LIST_ITEMS = 12;
    private static final int MAX_LABEL_CHARS = 80;
    private static final int MAX_ITEM_CHARS = 160;

    private static final Pattern SECRET_REF =
            Pattern.compile("\\{\\{\\s*secret\\.([A-Za-z0-9_]+)\\s*}}");
    private static final Pattern SECRET_NAME = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    private WorkflowGeneratorSupport() {}

    // --- prompt sections ------------------------------------------------------------------

    /**
     * Appended to the system prompt for a direct generation only (never the Brief planner's
     * reference mode, which has its own addendum and its own trigger model).
     */
    static String directAddendum(boolean enforceApprovalBeforeSend) {
        String approvalRule =
                enforceApprovalBeforeSend
                        ? """

              SAFETY RULE (mandatory): ALWAYS place an "approval" node directly before any node that
              sends something to a person or changes an external system: an "output" node with channel
              "email", "telegram" or "whatsapp", a "bulk_email" node, an "http" node whose method is not
              GET, or a "tool" node that sends a message. When that node is the per-item step of a
              "loop" or "fan_out", put the "approval" node BEFORE the loop / fan_out node instead (never
              between it and its per-item step). One approval is enough for everything after it.
              """
                        : "";
        return approvalRule
                + """

            SCHEDULE: a schedule is NOT a node and must never be drawn as one. When the instruction
            implies recurrence ("every Monday", "each morning at 9", "hourly", "daily"), keep the
            normal "trigger" node (channel "WEBHOOK") and add a top-level "schedule" object next to
            "graph" and "notes":
              "schedule": {"cron": "0 9 * * 1", "label": "Every Monday 09:00"}
            "cron" is exactly FIVE space-separated fields - minute hour day-of-month month
            day-of-week (0 or 7 = Sunday, 1 = Monday) - evaluated in UTC; when no time of day is
            given use 09:00. Supported shapes: hourly "0 * * * *", daily "M H * * *", weekly
            "M H * * D". Omit "schedule" entirely when the instruction does not imply recurrence.

            Also add these top-level arrays (empty when nothing applies):
              "secretsNeeded": the NAME (UPPER_SNAKE_CASE) of every vault secret the graph references
                as {{secret.NAME}};
              "knowledgeNeeded": one short phrase per set of documents a "knowledge" node would need.
            """;
    }

    /** The user-message suffix that turns a generation into an edit of {@code currentGraphJson}. */
    static String editSuffix(String currentGraphJson) {
        return "\n\nCURRENT GRAPH - the instruction above is a CHANGE REQUEST for this existing workflow, "
                + "not a description of a new one. Modify this graph: keep every node and edge the request does "
                + "not concern exactly as it is (same \"id\", \"type\", \"position\", \"label\" and \"config\"), "
                + "reuse existing ids, give new nodes and edges new unique ids, and return the COMPLETE updated "
                + "graph in the usual JSON shape (not a diff):\n"
                + currentGraphJson;
    }

    /**
     * The largest current graph (in JSON characters) an edit may carry. An edit asks the model to
     * return the COMPLETE graph, so the cap follows the generator's output budget ({@code
     * app.workflow.generator.max-tokens}): a graph that could not fit back into the reply is
     * refused up front with the "too large" 400 instead of paying for a call that comes back
     * truncated. Never above {@link #MAX_EDIT_GRAPH_CHARS}.
     */
    static int maxEditGraphChars(int generatorMaxTokens) {
        long byBudget = Math.round(Math.max(generatorMaxTokens, 0) * EDIT_CHARS_PER_OUTPUT_TOKEN);
        return (int) Math.min(MAX_EDIT_GRAPH_CHARS, byBudget);
    }

    /**
     * True when {@code graph} has something worth editing (more than nothing; a lone trigger still
     * counts).
     */
    static boolean isEditable(WorkflowGraphDto graph) {
        return graph != null && !graph.nodes().isEmpty();
    }

    // --- reply post-processing ------------------------------------------------------------

    /**
     * The schedule the model proposed, or null. An unusable cron is dropped with a note rather than
     * failing the generation - the graph is still good, the user just sets the schedule by hand.
     */
    static SuggestedSchedule parseSchedule(JsonNode root, List<String> notes) {
        JsonNode schedule = root == null ? null : root.get("schedule");
        if (schedule == null || !schedule.isObject()) {
            return null;
        }
        String rawCron = schedule.path("cron").asString("").trim();
        if (rawCron.isEmpty()) {
            return null;
        }
        String cron = WorkflowCronSupport.validFiveField(rawCron).orElse(null);
        if (cron == null) {
            notes.add(
                    "The suggested schedule could not be read, so none was set - choose one on the trigger "
                            + "after you activate the workflow.");
            return null;
        }
        String label = clip(schedule.path("label").asString("").trim(), MAX_LABEL_CHARS);
        return new SuggestedSchedule(cron, label.isEmpty() ? cron : label, "UTC");
    }

    /** Text entries of the top-level array {@code field}, trimmed, de-duplicated, capped. */
    static List<String> strings(JsonNode root, String field, int max) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode node = root == null ? null : root.get(field);
        if (node != null && node.isArray()) {
            for (JsonNode entry : node) {
                if (entry.isString() && !entry.asString().isBlank() && out.size() < max) {
                    out.add(clip(entry.asString().trim(), MAX_ITEM_CHARS));
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Secret names actually referenced as {{secret.NAME}} anywhere in a node's config. */
    static List<String> secretRefs(WorkflowGraphDto graph) {
        Set<String> out = new LinkedHashSet<>();
        if (graph != null) {
            for (WorkflowNodeDto node : graph.nodes()) {
                collectSecretRefs(node.config(), out, 0);
            }
        }
        return new ArrayList<>(out);
    }

    private static void collectSecretRefs(Object value, Set<String> out, int depth) {
        if (depth > 8 || value == null) {
            return;
        }
        if (value instanceof String text) {
            Matcher m = SECRET_REF.matcher(text);
            while (m.find()) {
                out.add(m.group(1));
            }
        } else if (value instanceof java.util.Map<?, ?> map) {
            map.values().forEach(v -> collectSecretRefs(v, out, depth + 1));
        } else if (value instanceof java.util.Collection<?> list) {
            list.forEach(v -> collectSecretRefs(v, out, depth + 1));
        }
    }

    /**
     * What still has to be connected before {@code graph} can run for real. Deterministic: derived
     * from the graph plus two facts about the workspace the caller looked up.
     *
     * @param modelSecrets names the model listed; kept only when they look like vault names
     * @param whatsappConnected null = unknown (omnichannel not present), in which case no hint is
     *     given
     * @param approvalPauses null = unknown; false = the workspace policy auto-approves Approval
     *     nodes
     */
    static Requirements requirements(
            WorkflowGraphDto graph,
            List<String> modelSecrets,
            List<String> knowledgeNeeded,
            Boolean whatsappConnected,
            Boolean approvalPauses) {
        Set<String> secrets = new LinkedHashSet<>(secretRefs(graph));
        for (String name : modelSecrets) {
            String candidate = name.trim().toUpperCase(Locale.ROOT);
            if (SECRET_NAME.matcher(candidate).matches() && secrets.size() < MAX_LIST_ITEMS) {
                secrets.add(candidate);
            }
        }
        List<RequirementItem> items = new ArrayList<>();
        for (String secret : secrets) {
            items.add(
                    new RequirementItem(
                            "secret",
                            "Store a secret named " + secret + " on the Secrets page.",
                            "/secrets",
                            null));
        }
        boolean whatsappHintGiven = false;
        boolean approvalHintGiven = false;
        for (WorkflowNodeDto node : graph.nodes()) {
            String type = node.type() == null ? "" : node.type().toLowerCase(Locale.ROOT);
            switch (type) {
                case "agent", "tool", "supervisor" -> {
                    if (blank(node.config().get("agentId"))) {
                        items.add(
                                new RequirementItem(
                                        "agent",
                                        "Pick an agent for \""
                                                + node.label()
                                                + "\" (create one on the Agents page first if you have none).",
                                        "/agents",
                                        node.id()));
                    }
                }
                case "knowledge" -> {
                    if (blank(node.config().get("collectionId"))) {
                        items.add(
                                new RequirementItem(
                                        "knowledge",
                                        "Pick a knowledge collection for \"" + node.label() + "\".",
                                        "/knowledge",
                                        node.id()));
                    }
                }
                case "output" -> {
                    String channel =
                            String.valueOf(node.config().get("channel")).toLowerCase(Locale.ROOT);
                    if ("whatsapp".equals(channel)
                            && Boolean.FALSE.equals(whatsappConnected)
                            && !whatsappHintGiven) {
                        whatsappHintGiven = true;
                        items.add(
                                new RequirementItem(
                                        "channel",
                                        "Connect WhatsApp on the Channels page - \""
                                                + node.label()
                                                + "\" cannot send until you do.",
                                        "/admin/channels",
                                        node.id()));
                    }
                }
                case "approval" -> {
                    if (Boolean.FALSE.equals(approvalPauses) && !approvalHintGiven) {
                        approvalHintGiven = true;
                        items.add(
                                new RequirementItem(
                                        "approval-policy",
                                        "Approval steps are auto-approved in this workspace, so \""
                                                + node.label()
                                                + "\" will not pause. Turn approval on for workflow approval steps on the Approvals page.",
                                        "/admin/approvals",
                                        node.id()));
                    }
                }
                default -> {
                    // nothing to connect
                }
            }
        }
        return new Requirements(
                new ArrayList<>(secrets), List.copyOf(knowledgeNeeded), List.copyOf(items));
    }

    static List<ExplanationStep> explanation(WorkflowGraphDto graph) {
        return WorkflowGraphExplainer.explain(graph).stream()
                .map(s -> new ExplanationStep(s.nodeId(), s.label(), s.type(), s.sentence()))
                .toList();
    }

    static List<InsertedApproval> insertedApprovals(WorkflowSendApprovalGuard.Result result) {
        return result.insertions().stream()
                .map(
                        i ->
                                new InsertedApproval(
                                        i.approvalNodeId(), i.beforeNodeId(), i.sendNodeId()))
                .toList();
    }

    private static boolean blank(Object value) {
        return value == null || value.toString().isBlank();
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
