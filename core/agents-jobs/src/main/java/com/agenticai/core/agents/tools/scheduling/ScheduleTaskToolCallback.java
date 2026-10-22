package com.agenticai.core.agents.tools.scheduling;

import com.agenticai.core.agents.scheduledtask.ScheduledTaskApprovalSummary;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskRequest;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskResponse;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskService;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskSettings;
import com.agenticai.core.guardrails.ToolConfirmationDescriber;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code schedule_task} - turn "every weekday at 8am summarise my unread email and send me the top
 * 5 on WhatsApp" into a recurring, unattended run of THIS agent.
 *
 * <p><b>The model proposes, the person confirms.</b> The tool is classified a tenant-scoped DURABLE
 * write that requires human confirmation (see {@code BuiltInToolClassifications}) and is on the
 * always-confirm list, so even a fully autonomous agent parks the call as an approval request
 * instead of creating anything. The confirmation card's text is produced by {@link
 * #describeForConfirmation}: the schedule in plain language and the next three run times, computed
 * from the VALIDATED schedule - never from anything the model wrote. When the call is approved the
 * request is replayed through this same callback and {@link #call} creates the task.
 *
 * <p>Identity never travels as an argument: the owner is the tenant context's subject (restored by
 * the approval replay from the original request), and the agent is the one this callback was built
 * for. Failures come back as short text for the model to relay, never as exceptions.
 */
public class ScheduleTaskToolCallback implements ToolCallback, ToolConfirmationDescriber {

    public static final String TOOL_NAME = ScheduleTaskToolFactory.TOOL_NAME;
    private static final Logger log = LoggerFactory.getLogger(ScheduleTaskToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final ScheduledTaskService service;
    private final ScheduledTaskSettings settings;
    private final UUID agentId;

    public ScheduleTaskToolCallback(
            ScheduledTaskService service, ScheduledTaskSettings settings, UUID agentId) {
        this.service = service;
        this.settings = settings;
        this.agentId = agentId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(
                        "Schedule a recurring task: a prompt this agent runs by itself at set times (for example weekdays at "
                                + "08:00) and delivers in-app or on a channel the user named. The user must confirm "
                                + "before it is created. Use the user's own timezone. For a one-time reminder set maxRuns to 1. Leave "
                                + "delivery and deliveryTo out unless the user named a channel and a recipient: the default is in-app.")
                .inputSchema(
                        """
                {
                    "type": "object",
                    "properties": {
                        "name": {"type": "string", "description": "Short title, e.g. Morning email digest."},
                        "prompt": {"type": "string", "description": "Exactly what to do on each run, written as a standalone instruction."},
                        "frequency": {"type": "string", "enum": ["daily", "weekdays", "weekly", "monthly", "custom"]},
                        "time": {"type": "string", "description": "HH:mm, 24-hour (not for custom)."},
                        "day": {"type": "string", "description": "weekly: Mon..Sun. monthly: 1-28 or last."},
                        "cron": {"type": "string", "description": "custom only: 5-field cron, at most every 15 minutes."},
                        "timezone": {"type": "string", "description": "IANA zone, e.g. Asia/Kolkata."},
                        "delivery": {"type": "string", "enum": ["in_app", "email", "whatsapp", "whatsapp_qr", "telegram", "slack", "signal", "matrix", "imessage", "a2a", "voice"], "description": "Omit unless the user named a channel; the default is in_app."},
                        "deliveryTo": {"type": "string", "description": "Recipient for email/whatsapp/whatsapp_qr/telegram/slack/signal/matrix/imessage/a2a/voice, only when the user gave one. Never invent it."},
                        "maxRuns": {"type": "integer", "description": "Optional: stop after this many runs. Use 1 for a one-time reminder."},
                        "expiresAt": {"type": "string", "description": "Optional: ISO-8601 instant after which it stops."}
                    },
                    "required": ["name", "prompt", "frequency"]
                }
                """)
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            TaskRequest request = parse(toolInput);
            TaskResponse created = service.create(request);
            ObjectNode out = MAPPER.createObjectNode();
            out.put("scheduled", true);
            out.put("id", created.id().toString());
            out.put("name", created.name());
            out.put("when", created.scheduleDescription());
            out.put("delivery", created.delivery());
            var runs = out.putArray("nextRuns");
            created.upcomingRuns().forEach(i -> runs.add(i.toString()));
            out.put(
                    "note",
                    "The task is active. The user can pause, edit or delete it any time from Scheduled tasks.");
            return MAPPER.writeValueAsString(out);
        } catch (IllegalArgumentException
                | TenantLimitExceededException
                | NoSuchElementException e) {
            return "Could not schedule the task: " + e.getMessage();
        } catch (ResourceAccessDeniedException e) {
            return "Could not schedule the task: you do not have access to run this agent.";
        } catch (Exception e) {
            log.warn("schedule_task tool call failed", e);
            return "Could not schedule the task: an internal error occurred. Nothing was created.";
        }
    }

    @Override
    public String describeForConfirmation(String toolInput) {
        try {
            return ScheduledTaskApprovalSummary.of(
                    parse(toolInput), settings.getMinIntervalMinutes(), Instant.now());
        } catch (RuntimeException e) {
            return null; // fall back to the default summary
        }
    }

    private TaskRequest parse(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            throw new IllegalArgumentException("name, prompt and frequency are required.");
        }
        JsonNode args;
        try {
            args = MAPPER.readTree(toolInput);
        } catch (Exception e) {
            throw new IllegalArgumentException("the arguments were not valid JSON.");
        }
        if (args == null || !args.isObject()) {
            throw new IllegalArgumentException("the arguments must be a JSON object.");
        }
        Integer maxRuns = null;
        JsonNode maxRunsNode = args.get("maxRuns");
        if (maxRunsNode != null && !maxRunsNode.isNull()) {
            if (!maxRunsNode.canConvertToInt()) {
                throw new IllegalArgumentException("maxRuns must be a whole number.");
            }
            maxRuns = maxRunsNode.asInt();
        }
        Instant expiresAt = null;
        String expires = text(args, "expiresAt");
        if (expires != null) {
            try {
                expiresAt = Instant.parse(expires);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "expiresAt must be an ISO-8601 instant such as 2026-12-31T00:00:00Z.");
            }
        }
        return new TaskRequest(
                agentId,
                text(args, "name"),
                text(args, "prompt"),
                text(args, "frequency"),
                text(args, "time"),
                text(args, "day"),
                text(args, "cron"),
                text(args, "timezone"),
                text(args, "delivery"),
                text(args, "deliveryTo"),
                maxRuns,
                expiresAt);
    }

    private static String text(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asString();
        return value == null || value.isBlank() ? null : value.trim();
    }
}
