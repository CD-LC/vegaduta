package com.agenticai.core.agents.scheduledtask;

import com.agenticai.core.agents.job.schedule.AgentCronSchedules;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskRequest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The human-readable text of the confirmation card for a {@code schedule_task} call: the schedule in plain language,
 * the next three run times (in the task's own timezone, with the zone abbreviation), where the result goes, and the
 * start of the prompt. Pure - no Spring, no database - because it runs while a tool call is being parked for approval
 * and must never be the reason a gate fails; any problem degrades to a shorter line rather than an exception.
 *
 * <p>This is the text a person reads before agreeing to let an agent act unattended on their behalf, so it is built
 * from the VALIDATED schedule (the same {@link ScheduledTaskSchedule#compile} the create path runs), never from
 * model-written prose.
 */
public final class ScheduledTaskApprovalSummary {

    static final int NEXT_RUNS_SHOWN = 3;
    private static final int PROMPT_PREVIEW_CHARS = 4000;
    private static final DateTimeFormatter RUN_TIME = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm z", Locale.ENGLISH);

    private ScheduledTaskApprovalSummary() {
    }

    /**
     * @param minIntervalMinutes the configured minimum interval (an invalid schedule is described, not thrown)
     * @param now                the instant the "next runs" are computed from
     */
    public static String of(TaskRequest request, int minIntervalMinutes, Instant now) {
        StringBuilder out = new StringBuilder("Schedule a recurring task");
        if (request == null) {
            return out.toString();
        }
        if (request.name() != null && !request.name().isBlank()) {
            out.append(" \"").append(oneLine(request.name(), 80)).append('"');
        }
        try {
            ScheduledTaskSchedule.Compiled compiled = ScheduledTaskSchedule.compile(
                new ScheduledTaskSchedule.Spec(
                    ScheduledTaskSchedule.Kind.parse(request.frequency()), request.time(), request.day(), request.cron()),
                request.timezone(), minIntervalMinutes, now);
            ZoneId zone = AgentCronSchedules.zoneOf(compiled.timezone());
            List<Instant> next = ScheduledTaskSchedule.nextRuns(compiled.cron(), compiled.timezone(), now, NEXT_RUNS_SHOWN);
            out.append(".\nWhen: ").append(ScheduledTaskSchedule.describe(compiled.cron(), compiled.timezone()));
            out.append(".\nNext runs: ");
            for (int i = 0; i < next.size(); i++) {
                out.append(i == 0 ? "" : "; ").append(RUN_TIME.format(next.get(i).atZone(zone)));
            }
        } catch (RuntimeException invalid) {
            out.append(".\nWhen: not valid yet (").append(oneLine(String.valueOf(invalid.getMessage()), 160)).append(')');
        }
        String delivery = request.delivery() == null || request.delivery().isBlank() ? "in_app" : request.delivery().trim();
        out.append(".\nResult goes to: ").append(oneLine(delivery, 20));
        if (request.deliveryTo() != null && !request.deliveryTo().isBlank()) {
            out.append(" (").append(oneLine(request.deliveryTo(), 80)).append(')');
        }
        if (!"in_app".equalsIgnoreCase(delivery)) {
            out.append(".\nNote: sending to ").append(oneLine(delivery, 20)).append(" goes through your workspace approval "
                + "policy - by default each message waits in Approvals until someone releases it, so it will not arrive "
                + "on its own until an admin allows scheduled sends");
        }
        if (request.prompt() != null && !request.prompt().isBlank()) {
            // The whole prompt: it runs unattended on every firing, so the approver must see all of it (create caps a
            // prompt at max-prompt-chars, far under this preview limit).
            String flat = request.prompt().replaceAll("\\s+", " ").trim();
            out.append(".\nIt will run: \"").append(oneLine(flat, PROMPT_PREVIEW_CHARS)).append('"');
            if (flat.length() > PROMPT_PREVIEW_CHARS) {
                out.append(" (+").append(flat.length() - PROMPT_PREVIEW_CHARS + 1)
                    .append(" more characters - the full text is in the approval payload)");
            }
        }
        out.append(".\nIt runs unattended on its own from then on; you can pause or delete it any time from Scheduled tasks.");
        return out.toString();
    }

    private static String oneLine(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, Math.max(0, max - 1)) + "…";
    }
}
