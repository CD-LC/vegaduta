package com.agenticai.core.workflow;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.guidance.WorkflowSendApprovalGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stands in for a node that would send something out, in a draft run started with
 * {@code simulateSends: true} ({@link RunMode#DRAFT_DRY_RUN}). Nothing is dispatched: the node
 * reports what it WOULD have sent and to whom, and passes its input on unchanged so the rest of
 * the graph still runs.
 *
 * <p>Covered ({@link WorkflowSendApprovalGuard#isSimulatableSend}, the one predicate shared with
 * the approval guard's send classification): output nodes on an external channel (email, telegram,
 * teams, whatsapp, google_drive) or the {@code event} channel, {@code bulk_email}, {@code http} with
 * a method other than GET / HEAD / OPTIONS, {@code tool} nodes named {@code send_*}, CRM writes,
 * device control and {@code workflow} (sub-workflow) nodes - the child would otherwise run in
 * production mode and send for real. NOT covered, because what they do is only decided at run
 * time: {@code agent} / {@code supervisor} nodes (an agent may call a sending tool) and
 * {@code tool} nodes with any other name. Those still execute, and {@link #markRealExecution}
 * says so in the step's trace so "nothing is sent" is never claimed for them. Approval nodes are
 * untouched too - they still pause, so the author sees the gate.
 *
 * <p>Called from {@link WorkflowNodeExecutor#executeNode} after the approval gate and after
 * expression resolution, so the destination shown is the resolved one. Pure and static.
 */
final class WorkflowDryRun {

    /** Every simulated node's summary starts with this; {@code NodeTraceEntry#dryRun} keys on it. */
    static final String SUMMARY_PREFIX = NodeTraceEntry.DRY_RUN_SUMMARY_PREFIX;
    static final int PREVIEW_CHARS = 160;

    private WorkflowDryRun() {
    }

    /**
     * @param resolvedNode the node with its expression fields already resolved
     * @param scope        the scope bound for this execution; only a dry-run scope simulates
     * @return the stand-in result, or null when the node must execute for real
     */
    static NodeExecutionResult simulateIfDryRun(WorkflowNodeDto resolvedNode, String input, ExpressionScope scope) {
        return simulateIfDryRun(resolvedNode, input, scope, false);
    }

    /** @param whatsAppTemplatesEnabled whether a WhatsApp output's template config would be honoured by a real run */
    static NodeExecutionResult simulateIfDryRun(
        WorkflowNodeDto resolvedNode, String input, ExpressionScope scope, boolean whatsAppTemplatesEnabled
    ) {
        if (scope == null || !scope.simulatesSends() || !WorkflowSendApprovalGuard.isSimulatableSend(resolvedNode)) {
            return null;
        }
        return simulate(resolvedNode, input, whatsAppTemplatesEnabled);
    }

    /** Trace line added to a node a dry run could not stand in for. */
    static final String RAN_FOR_REAL_LINE = "Test run: this step ran for real - sends are only simulated for steps "
        + "whose effect is known in advance. If this agent or tool sends or changes something, that happened.";

    /** Node types whose side effects are decided at run time, so a dry run executes them. */
    static boolean runsForRealInDryRun(WorkflowNodeDto node) {
        String type = node == null || node.type() == null ? "" : node.type().strip().toLowerCase(Locale.ROOT);
        return "agent".equals(type) || "supervisor".equals(type) || "tool".equals(type);
    }

    /**
     * In a dry run, appends {@link #RAN_FOR_REAL_LINE} to the trace of an agent / supervisor / tool
     * node that executed (simulated nodes never get here). Any other run, node or a null result is
     * returned untouched.
     */
    static NodeExecutionResult markRealExecution(WorkflowNodeDto resolvedNode, ExpressionScope scope, NodeExecutionResult result) {
        if (result == null || scope == null || !scope.simulatesSends() || !runsForRealInDryRun(resolvedNode)) {
            return result;
        }
        List<String> logLines = new ArrayList<>(result.logLines() == null ? List.of() : result.logLines());
        logLines.add(RAN_FOR_REAL_LINE);
        return new NodeExecutionResult(result.output(), result.status(), result.summary(), result.branch(),
            result.haltRun(), logLines, result.fileName(), result.fileData());
    }

    static NodeExecutionResult simulate(WorkflowNodeDto node, String input) {
        return simulate(node, input, false);
    }

    static NodeExecutionResult simulate(WorkflowNodeDto node, String input, boolean whatsAppTemplatesEnabled) {
        String type = node.type() == null ? "" : node.type().toLowerCase(Locale.ROOT);
        Map<String, Object> config = node.config();
        String what;
        String destination;
        String payload = input;
        switch (type) {
            case "bulk_email" -> {
                what = "send a bulk email";
                String source = text(config.get("recipientsSource"));
                destination = switch (source) {
                    case "static" -> orElse(text(config.get("staticRecipients")), "(no recipients listed)");
                    case "group" -> "the contact group " + orElse(text(config.get("groupId")), "(none chosen)");
                    default -> "each recipient in the incoming list";
                };
                payload = "Subject: " + text(config.get("subject")) + " | " + text(config.get("body"));
            }
            case "tool" -> {
                what = "call the tool \"" + text(config.get("toolName")) + "\"";
                destination = null;
                String args = text(config.get("argsJson"));
                payload = args.isEmpty() ? input : args;
            }
            case "workflow" -> {
                what = "start the workflow " + orElse(text(config.get("targetWorkflowId")), "(none chosen)")
                    + " as a real run";
                destination = null;
            }
            case "crm_lead" -> {
                what = "write to the Leads CRM (" + orElse(text(config.get("action")), "upsert") + ")";
                destination = null;
                String who = orElse(text(config.get("email")), text(config.get("name")));
                payload = who.isEmpty() ? input : who;
            }
            case "data_table" -> {
                what = "change the data table " + orElse(text(config.get("table")), "(none chosen)") + " ("
                    + orElse(text(config.get("operation")), "get_rows") + ")";
                destination = null;
                String values = text(config.get("valuesJson"));
                payload = values.isEmpty() ? input : values;
            }
            case "device_control" -> {
                what = "send a command to the device " + orElse(text(config.get("deviceId")), "(none chosen)");
                destination = null;
                String command = text(config.get("payload"));
                payload = command.isEmpty() ? input : command;
            }
            case "http" -> {
                String method = orElse(text(config.get("method")), "GET").toUpperCase(Locale.ROOT);
                what = "make a " + method + " request";
                destination = orElse(text(config.get("url")), "(no URL set)");
                String body = text(config.get("body"));
                payload = body.isEmpty() ? "(no body)" : body;
            }
            default -> {
                String channel = text(config.get("channel")).toLowerCase(Locale.ROOT);
                switch (channel) {
                    case "email" -> {
                        what = "send an email";
                        destination = orElse(text(config.get("emailTo")), "(no address set)");
                    }
                    case "telegram" -> {
                        what = "send a Telegram message";
                        destination = orElse(text(config.get("telegramChatId")), "the chat that triggers the run");
                    }
                    case "teams" -> {
                        what = "send a Teams message";
                        destination = orElse(text(config.get("teamsConversationId")), "the conversation that triggers the run");
                    }
                    case "whatsapp" -> {
                        destination = orElse(text(config.get("whatsappTo")), "the sender that triggers the run");
                        payload = orElse(text(config.get("whatsappMessage")), input == null ? "" : input);
                        WhatsAppTemplateRef template = whatsAppTemplatesEnabled ? WhatsAppTemplateRef.of(node) : null;
                        if (template == null) {
                            what = "send a WhatsApp message";
                        } else {
                            what = "send the WhatsApp template \"" + template.name() + "\" (" + template.language() + ")";
                            payload = template.params().isEmpty() ? "(no variables)" : "variables " + template.params();
                        }
                    }
                    case "event" -> {
                        what = "publish a workflow event (it can start other workflows)";
                        destination = null;
                    }
                    case "google_drive" -> {
                        what = "upload a file";
                        String folder = text(config.get("driveFolderId"));
                        destination = "Google Drive as " + orElse(text(config.get("driveFileName")), "workflow-output.txt")
                            + (folder.isEmpty() ? " in My Drive" : " in folder " + folder);
                    }
                    default -> {
                        what = "send";
                        destination = "(unknown destination)";
                    }
                }
            }
        }
        String line = SUMMARY_PREFIX + "would " + what
            + (destination == null ? "" : " to " + WorkflowNodeSupport.truncate(destination, 200)) + ": "
            + WorkflowNodeSupport.truncate(payload == null ? "" : payload, PREVIEW_CHARS);
        List<String> logLines = new ArrayList<>();
        logLines.add(line);
        logLines.add("Nothing was sent or changed - this is a test run with sends simulated. Later steps received this step's input unchanged.");
        return NodeExecutionResult.ok(input, line, null, logLines);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().strip();
    }

    private static String orElse(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }
}
