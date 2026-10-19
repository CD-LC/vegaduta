package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowNodeSupport.safeLength;
import static com.agenticai.core.workflow.WorkflowNodeSupport.stringConfig;
import static com.agenticai.core.workflow.WorkflowNodeSupport.truncate;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.omnichannel.ChannelException;
import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.workflow.output.EmailOutputService;
import com.agenticai.core.workflow.output.GoogleDriveOutputService;
import com.agenticai.core.workflow.output.PdfOutputRenderer;
import com.agenticai.core.workflow.output.TeamsOutputService;
import com.agenticai.core.workflow.output.TelegramOutputService;
import com.agenticai.core.workflow.output.WhatsAppOutputService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** "output": delivers the result to a channel (log, event, file, email, Telegram, Teams, WhatsApp, Drive). */
@Component
class OutputNodeHandler implements NodeHandler {

    private static final Logger log = LoggerFactory.getLogger(OutputNodeHandler.class);

    private final WorkflowEventPublisher eventPublisher;
    private final EmailOutputService emailOutputService;
    private final TelegramOutputService telegramOutputService;
    private final TeamsOutputService teamsOutputService;
    private final WhatsAppOutputService whatsAppOutputService;
    private final GoogleDriveOutputService googleDriveOutputService;
    private final PdfOutputRenderer pdfOutputRenderer;
    OutputNodeHandler(
            WorkflowEventPublisher eventPublisher,
            EmailOutputService emailOutputService,
            TelegramOutputService telegramOutputService,
            TeamsOutputService teamsOutputService,
            WhatsAppOutputService whatsAppOutputService,
            GoogleDriveOutputService googleDriveOutputService,
            PdfOutputRenderer pdfOutputRenderer) {
        this.eventPublisher = eventPublisher;
        this.emailOutputService = emailOutputService;
        this.telegramOutputService = telegramOutputService;
        this.teamsOutputService = teamsOutputService;
        this.whatsAppOutputService = whatsAppOutputService;
        this.googleDriveOutputService = googleDriveOutputService;
        this.pdfOutputRenderer = pdfOutputRenderer;
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("output");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return executeOutputNode(
                inv.workflowDefinitionId(),
                inv.workflowDefinitionName(),
                inv.runId(),
                inv.node(),
                inv.input(),
                inv.whatsAppTemplatesEnabled());
    }

    private NodeExecutionResult executeOutputNode(
            String workflowDefinitionId,
            String workflowDefinitionName,
            String runId,
            WorkflowNodeDto node,
            String input,
            boolean whatsAppTemplatesEnabled) {
        Object channelRaw = node.config().get("channel");
        String channel = channelRaw == null ? "log" : channelRaw.toString();
        List<String> logLines = new ArrayList<>();

        try {
            switch (channel) {
                case "event" -> {
                    eventPublisher.publish(
                            "workflow.output.emitted",
                            runId,
                            Map.of(
                                    "workflowId",
                                    workflowDefinitionId,
                                    "nodeId",
                                    node.id(),
                                    "message",
                                    input));
                    logLines.add("Published to workflow.events topic.");
                    return NodeExecutionResult.ok(
                            input, "Published to workflow.events.", null, logLines);
                }
                case "file" -> {
                    String fileType = stringConfig(node, "fileType", "text");
                    boolean isPdf = "pdf".equalsIgnoreCase(fileType);
                    String fileName =
                            safeFileName(
                                    stringConfig(
                                            node,
                                            "fileName",
                                            isPdf ? "workflow-output.pdf" : "workflow-output.txt"),
                                    isPdf ? "workflow-output.pdf" : "workflow-output.txt");
                    if (isPdf && !fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
                        fileName = fileName + ".pdf";
                    }
                    String fileData;
                    if (isPdf) {
                        fileData = pdfOutputRenderer.renderToBase64(workflowDefinitionName, input);
                        logLines.add(
                                "Rendered output ("
                                        + safeLength(input)
                                        + " chars) as a PDF document: "
                                        + fileName
                                        + ".");
                    } else {
                        fileData = input;
                        logLines.add(
                                "Writing output ("
                                        + safeLength(input)
                                        + " chars) to "
                                        + fileName
                                        + ".");
                    }
                    logLines.add(
                            "Available for download from this run's detail view once complete.");
                    return NodeExecutionResult.ok(
                            input,
                            "Saved as " + fileName + " - downloadable from this run.",
                            null,
                            logLines,
                            fileName,
                            fileData);
                }
                case "email" -> {
                    String to = stringConfig(node, "emailTo", null);
                    logLines.add("Sending email to " + to + "...");
                    String resultLine =
                            emailOutputService.send(
                                    to,
                                    "Workflow output: " + workflowDefinitionName,
                                    input,
                                    runId,
                                    node.id());
                    logLines.add(resultLine);
                    return NodeExecutionResult.ok(input, resultLine, null, logLines);
                }
                case "telegram" -> {
                    String chatId = stringConfig(node, "telegramChatId", null);
                    if (chatId == null) {
                        // Reply-to-sender: same as the WhatsApp case below -
                        // with no fixed chat id, reply to the Telegram chat that
                        // triggered this run. Set by WorkflowTriggerPortImpl; see
                        // WorkflowTriggerContextHolder.
                        chatId =
                                WorkflowTriggerContextHolder.current()
                                        .filter(
                                                ctx ->
                                                        ctx.channel()
                                                                == WorkflowTriggerChannel.TELEGRAM)
                                        .map(InboundTriggerContext::replyToAddress)
                                        .orElse(null);
                        if (chatId != null) {
                            logLines.add(
                                    "No fixed chat set - replying to the Telegram chat that triggered this run.");
                        }
                    }
                    logLines.add("Sending Telegram message to chat " + chatId + "...");
                    String resultLine = telegramOutputService.send(chatId, input, runId, node.id());
                    logLines.add(resultLine);
                    return NodeExecutionResult.ok(input, resultLine, null, logLines);
                }
                case "teams" -> {
                    String conversationId = stringConfig(node, "teamsConversationId", null);
                    if (conversationId == null) {
                        // Reply-to-sender, same shape as the telegram case
                        // above - TEAMS' packed context carries
                        // "serviceUrl|conversationId" (see
                        // WorkflowTriggerChannel.TEAMS' javadoc); the send
                        // itself re-resolves the freshest serviceUrl from
                        // teams_conversation_route inside TeamsOutputService.
                        conversationId =
                                WorkflowTriggerContextHolder.current()
                                        .filter(
                                                ctx ->
                                                        ctx.channel()
                                                                == WorkflowTriggerChannel.TEAMS)
                                        .map(InboundTriggerContext::replyToAddress)
                                        .map(
                                                packed ->
                                                        packed.contains("|")
                                                                ? packed.substring(
                                                                        packed.indexOf('|') + 1)
                                                                : packed)
                                        .orElse(null);
                        if (conversationId != null) {
                            logLines.add(
                                    "No fixed conversation set - replying to the Teams conversation that triggered this run.");
                        }
                    }
                    logLines.add("Sending Teams message to conversation " + conversationId + "...");
                    String resultLine =
                            teamsOutputService.send(conversationId, input, runId, node.id());
                    logLines.add(resultLine);
                    return NodeExecutionResult.ok(input, resultLine, null, logLines);
                }
                case "whatsapp" -> {
                    String to = stringConfig(node, "whatsappTo", null);
                    if (to == null) {
                        // Reply-to-sender: when the Output node has no fixed
                        // number, fall back to the address of whoever triggered
                        // this run via an inbound WhatsApp message. This is what
                        // makes "an inbound WhatsApp message triggers this
                        // workflow, and the agent's reply goes back to that same
                        // sender" work without hard-coding a destination. Set by
                        // WorkflowTriggerPortImpl; see WorkflowTriggerContextHolder.
                        to =
                                WorkflowTriggerContextHolder.current()
                                        .filter(
                                                ctx ->
                                                        ctx.channel()
                                                                == WorkflowTriggerChannel.WHATSAPP)
                                        .map(InboundTriggerContext::replyToAddress)
                                        .orElse(null);
                        if (to != null) {
                            logLines.add(
                                    "No fixed number set - replying to the WhatsApp sender that triggered this run.");
                        }
                    }
                    // "whatsappMessage" (already expression-resolved) is the text to send; blank
                    // keeps the
                    // original behaviour of sending this node's input.
                    String message = stringConfig(node, "whatsappMessage", input);
                    WhatsAppTemplateRef template =
                            whatsAppTemplatesEnabled ? WhatsAppTemplateRef.of(node) : null;
                    String resultLine;
                    if (template != null) {
                        logLines.add(
                                "Sending WhatsApp template \""
                                        + template.name()
                                        + "\" ("
                                        + template.language()
                                        + ") to "
                                        + to
                                        + "...");
                        resultLine =
                                whatsAppOutputService.sendTemplate(
                                        to,
                                        template.name(),
                                        template.language(),
                                        template.params(),
                                        message,
                                        runId,
                                        node.id());
                    } else {
                        logLines.add("Sending WhatsApp message to " + to + "...");
                        resultLine = whatsAppOutputService.send(to, message, runId, node.id());
                    }
                    logLines.add(resultLine);
                    return NodeExecutionResult.ok(input, resultLine, null, logLines);
                }
                case "google_drive" -> {
                    String folderId = stringConfig(node, "driveFolderId", null);
                    String fileName = stringConfig(node, "driveFileName", "workflow-output.txt");
                    logLines.add("Uploading output to Google Drive as \"" + fileName + "\"...");
                    String resultLine =
                            googleDriveOutputService.upload(
                                    folderId, fileName, input, runId, node.id());
                    logLines.add(resultLine);
                    return NodeExecutionResult.ok(input, resultLine, null, logLines);
                }
                default -> {
                    log.info(
                            "Workflow '{}' run {} output: {}",
                            workflowDefinitionName,
                            runId,
                            input);
                    logLines.add(
                            "Logged output ("
                                    + safeLength(input)
                                    + " chars) - no external destination configured.");
                    return NodeExecutionResult.ok(input, truncate(input, 300), null, logLines);
                }
            }
        } catch (ChannelException e) {
            logLines.add("Destination '" + channel + "' failed: " + e.getMessage());
            return NodeExecutionResult.skipped(input, e.getMessage(), logLines);
        }
    }

    /**
     * The output node's file name can now come from data ({{ $json.title }}), but it is stored in
     * workflow_run.output_file_name (VARCHAR 255) and offered as a download name. Strip path
     * separators and control characters and cap the length, keeping a short extension, so a long or
     * hostile value can never make the final save of the run fail.
     */
    static String safeFileName(String raw, String fallback) {
        String cleaned =
                raw == null ? "" : raw.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]+", "_").strip();
        if (cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '.' || c == '_')) {
            return fallback;
        }
        final int max = 200;
        if (cleaned.length() <= max) {
            return cleaned;
        }
        int dot = cleaned.lastIndexOf('.');
        String ext = dot > 0 && cleaned.length() - dot <= 10 ? cleaned.substring(dot) : "";
        return cleaned.substring(0, max - ext.length()) + ext;
    }
}
