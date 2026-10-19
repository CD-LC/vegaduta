package com.agenticai.core.workflow.guidance;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a workflow graph into an ordered, plain-language "what this does" list. Deterministic on
 * purpose (a graph walk plus one sentence template per node type, no model call): the explanation
 * is read by a newcomer deciding whether to trust a generated draft, so it must describe the graph
 * that will actually run, cost nothing and never drift from it.
 *
 * <p>Order is a depth-first walk from each Trigger following edges in graph order, every node once.
 * Nodes no Trigger reaches are left out - they never run.
 */
public final class WorkflowGraphExplainer {

    private static final int MAX_DETAIL_CHARS = 80;

    private WorkflowGraphExplainer() {
    }

    /** One step of the explanation; {@code type} is the lower-case node type. */
    public record ExplainedStep(String nodeId, String label, String type, String sentence) {
    }

    public static List<ExplainedStep> explain(WorkflowGraphDto graph) {
        if (graph == null || graph.nodes().isEmpty()) {
            return List.of();
        }
        Map<String, WorkflowNodeDto> byId = new LinkedHashMap<>();
        for (WorkflowNodeDto node : graph.nodes()) {
            if (node.id() != null) {
                byId.putIfAbsent(node.id(), node);
            }
        }
        Map<String, List<WorkflowEdgeDto>> outgoing = new HashMap<>();
        for (WorkflowEdgeDto edge : graph.edges()) {
            if (edge != null && byId.containsKey(edge.source()) && byId.containsKey(edge.target())) {
                outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
            }
        }
        List<ExplainedStep> steps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (WorkflowNodeDto node : byId.values()) {
            if ("trigger".equals(typeOf(node))) {
                walk(node, byId, outgoing, seen, steps);
            }
        }
        return steps;
    }

    private static void walk(
        WorkflowNodeDto start, Map<String, WorkflowNodeDto> byId, Map<String, List<WorkflowEdgeDto>> outgoing,
        Set<String> seen, List<ExplainedStep> steps
    ) {
        // Iterative: generated graphs are small, but a hand-built 200-node chain must not blow the stack.
        java.util.Deque<WorkflowNodeDto> stack = new java.util.ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            WorkflowNodeDto node = stack.pop();
            if (!seen.add(node.id())) {
                continue;
            }
            List<WorkflowEdgeDto> out = outgoing.getOrDefault(node.id(), List.of());
            steps.add(new ExplainedStep(node.id(), node.label(), typeOf(node), sentence(node, out, byId)));
            for (int i = out.size() - 1; i >= 0; i--) { // reversed so the first edge is walked first
                stack.push(byId.get(out.get(i).target()));
            }
        }
    }

    private static String typeOf(WorkflowNodeDto node) {
        return node.type() == null ? "" : node.type().strip().toLowerCase(Locale.ROOT);
    }

    // --- sentence templates ---------------------------------------------------------------

    static String sentence(WorkflowNodeDto node, List<WorkflowEdgeDto> out, Map<String, WorkflowNodeDto> byId) {
        Map<String, Object> config = node.config();
        return switch (typeOf(node)) {
            case "trigger" -> triggerSentence(text(config.get("channel")));
            case "agent" -> "The AI agent \"" + node.label() + "\" works on the result of the previous step.";
            case "knowledge" -> "Looks up relevant passages in your knowledge base.";
            case "condition" -> conditionSentence(config, out, byId);
            case "approval" -> "The run pauses for your approval - nothing after this step happens until a person approves it.";
            case "human_input" -> "The run pauses and asks a person to fill in a short form before it continues.";
            case "output" -> outputSentence(config);
            case "tool" -> text(config.get("toolName")).isEmpty()
                ? "Runs one tool directly."
                : "Runs the \"" + text(config.get("toolName")) + "\" tool directly.";
            case "loop" -> "Repeats the next step once for each item in the list, one after another"
                + next(out, byId) + ".";
            case "fan_out" -> "Runs the next step once for each item in the list, several at a time"
                + next(out, byId) + ".";
            case "sub_flow" -> "Runs a small group of steps once for each item in the list.";
            case "parallel" -> "Runs " + (out.isEmpty() ? "its branches" : out.size() + " branch"
                + (out.size() == 1 ? "" : "es")) + " at the same time on the same input.";
            case "code" -> "Reshapes the data (" + (text(config.get("operation")).isEmpty()
                ? "transform" : text(config.get("operation")).replace('_', ' ')) + ").";
            case "http" -> httpSentence(config);
            case "supervisor" -> supervisorSentence(config, out, byId);
            case "bulk_email" -> "Sends a personalised email to each recipient"
                + (text(config.get("subject")).isEmpty() ? "" : " with the subject \"" + clip(text(config.get("subject"))) + "\"")
                + ".";
            case "crm_lead" -> crmSentence(text(config.get("action")));
            case "data_table" -> dataTableSentence(text(config.get("operation")));
            case "media" -> "Creates media (" + (text(config.get("operation")).isEmpty()
                ? "audio or video" : text(config.get("operation")).replace('_', ' ')) + ").";
            case "workflow" -> "Starts another saved workflow and uses its result.";
            case "device_control" -> "Sends a command to a paired device.";
            case "voice_menu" -> "Plays a phone menu and waits for the caller to press a key.";
            case "set" -> "Adds or changes fields on the data.";
            case "filter" -> "Keeps only the items that match the conditions.";
            case "sort" -> "Puts the list in order.";
            case "aggregate" -> "Counts, adds up or combines the list into a summary.";
            case "limit" -> "Keeps only the first or last few items of the list.";
            case "dedupe" -> "Removes repeated items from the list.";
            case "switch" -> "Sends the run down the first path whose conditions match, or the default path.";
            case "merge" -> "Combines several inputs into one.";
            case "question_classifier" -> "Reads the text and sends the run down the branch for the class it fits best.";
            case "parameter_extractor" -> "Pulls the listed details out of the text into structured fields.";
            case "variable_aggregator" -> "Takes the first of several earlier results that actually has a value.";
            case "document_extractor" -> "Turns an uploaded file or page into plain text.";
            default -> "Runs the \"" + node.label() + "\" step.";
        };
    }

    private static String triggerSentence(String channel) {
        return switch (channel.toUpperCase(Locale.ROOT)) {
            case "", "WEBHOOK" -> "Starts when the workflow is run - by hand, on a schedule, or from a webhook call.";
            case "CALL" -> "Starts when a phone call comes in.";
            case "EMAIL" -> "Starts when an email arrives.";
            case "SMS" -> "Starts when an SMS arrives.";
            case "X" -> "Starts when a message arrives on X.";
            case "WHATSAPP" -> "Starts when a WhatsApp message arrives.";
            default -> "Starts when a " + titleCase(channel) + " message arrives.";
        };
    }

    private static String conditionSentence(
        Map<String, Object> config, List<WorkflowEdgeDto> out, Map<String, WorkflowNodeDto> byId
    ) {
        String check;
        if ("expression".equalsIgnoreCase(text(config.get("mode"))) && !text(config.get("expression")).isEmpty()) {
            check = "Checks whether " + clip(text(config.get("expression")));
        } else if (!text(config.get("contains")).isEmpty()) {
            check = "Checks whether the previous result mentions \"" + clip(text(config.get("contains"))) + "\"";
        } else {
            check = "Checks a condition";
        }
        String yes = targetLabel(out, "true", byId);
        String no = targetLabel(out, "false", byId);
        if (yes != null && no != null) {
            return check + ": if yes it continues to \"" + yes + "\", otherwise to \"" + no + "\".";
        }
        if (yes != null) {
            return check + ": if yes it continues to \"" + yes + "\", otherwise this path stops.";
        }
        if (no != null) {
            return check + ": if not it continues to \"" + no + "\", otherwise this path stops.";
        }
        return check + ".";
    }

    private static String supervisorSentence(
        Map<String, Object> config, List<WorkflowEdgeDto> out, Map<String, WorkflowNodeDto> byId
    ) {
        List<String> options = new ArrayList<>();
        if (config.get("routes") instanceof List<?> routes) {
            for (Object raw : routes) {
                if (!(raw instanceof Map<?, ?> route)) {
                    continue;
                }
                String handle = text(route.get("handle"));
                String label = text(route.get("label"));
                String target = handle.isEmpty() ? null : targetLabel(out, handle, byId);
                String name = label.isEmpty() ? handle : label;
                if (name.isEmpty()) {
                    continue;
                }
                options.add("\"" + clip(name) + "\"" + (target == null ? "" : " (goes to \"" + target + "\")"));
            }
        }
        return options.isEmpty()
            ? "An AI agent decides which path the run takes next."
            : "An AI agent picks one path: " + String.join(", ", options) + ".";
    }

    private static String outputSentence(Map<String, Object> config) {
        String channel = text(config.get("channel")).toLowerCase(Locale.ROOT);
        return switch (channel) {
            case "email" -> "Sends the result by email" + to(config.get("emailTo")) + ".";
            case "whatsapp" -> (text(config.get("whatsappMessage")).isEmpty()
                    ? "Sends the result as a WhatsApp message" : "Sends a WhatsApp message")
                + (text(config.get("whatsappTo")).isEmpty() ? " to the person who messaged" : to(config.get("whatsappTo"))) + ".";
            case "telegram" -> "Sends the result as a Telegram message"
                + (text(config.get("telegramChatId")).isEmpty() ? " to the chat that started the run" : to(config.get("telegramChatId")))
                + ".";
            case "teams" -> "Sends the result to a Microsoft Teams conversation.";
            case "google_drive" -> "Saves the result as a file in Google Drive.";
            case "file" -> "Saves the result as a file you can download from the run.";
            case "event" -> "Publishes the result as an event other workflows can react to.";
            default -> "Records the result in the run log.";
        };
    }

    private static String httpSentence(Map<String, Object> config) {
        String method = text(config.get("method")).toUpperCase(Locale.ROOT);
        String host = host(text(config.get("url")));
        boolean reads = method.isEmpty() || WorkflowSendApprovalGuard.READ_ONLY_HTTP_METHODS.contains(method);
        return (reads ? "Fetches data from " : "Sends data to ") + (host.isEmpty() ? "an external service" : host)
            + " (" + (method.isEmpty() ? "GET" : method) + " request).";
    }

    private static String dataTableSentence(String operation) {
        return switch (operation.toLowerCase(Locale.ROOT)) {
            case "insert_row" -> "Adds a row to a data table.";
            case "update_rows" -> "Changes the matching rows of a data table.";
            case "upsert_row" -> "Adds a row to a data table, or updates it if it is already there.";
            case "delete_rows" -> "Deletes the matching rows from a data table.";
            case "count" -> "Counts the matching rows of a data table.";
            default -> "Reads rows from a data table.";
        };
    }

    private static String crmSentence(String action) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "stage" -> "Moves the lead to another stage in your CRM.";
            case "activity" -> "Adds a note to the lead's timeline in your CRM.";
            default -> "Creates or updates the lead in your CRM.";
        };
    }

    // --- helpers --------------------------------------------------------------------------

    private static String next(List<WorkflowEdgeDto> out, Map<String, WorkflowNodeDto> byId) {
        if (out.isEmpty()) {
            return "";
        }
        WorkflowNodeDto target = byId.get(out.get(0).target());
        return target == null ? "" : " (\"" + target.label() + "\")";
    }

    private static String targetLabel(List<WorkflowEdgeDto> out, String handle, Map<String, WorkflowNodeDto> byId) {
        for (WorkflowEdgeDto edge : out) {
            if (handle.equalsIgnoreCase(edge.sourceHandle() == null ? "" : edge.sourceHandle())) {
                WorkflowNodeDto target = byId.get(edge.target());
                return target == null ? null : target.label();
            }
        }
        return null;
    }

    private static String to(Object destination) {
        String value = text(destination);
        if (value.isEmpty()) {
            return "";
        }
        // An expression is how "each customer's number" is written; quoting the syntax helps nobody.
        return value.contains("{{") ? " to an address taken from the data" : " to " + clip(value);
    }

    private static String host(String url) {
        if (url.isEmpty() || url.contains("{{")) {
            int scheme = url.indexOf("://");
            if (scheme < 0) {
                return "";
            }
            String rest = url.substring(scheme + 3);
            int end = indexOfAny(rest, "/?#{");
            String candidate = end < 0 ? rest : rest.substring(0, end);
            return candidate.contains("{") ? "" : clip(candidate);
        }
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static int indexOfAny(String value, String chars) {
        for (int i = 0; i < value.length(); i++) {
            if (chars.indexOf(value.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().strip();
    }

    private static String clip(String value) {
        return value.length() <= MAX_DETAIL_CHARS ? value : value.substring(0, MAX_DETAIL_CHARS - 1) + "...";
    }

    private static String titleCase(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
