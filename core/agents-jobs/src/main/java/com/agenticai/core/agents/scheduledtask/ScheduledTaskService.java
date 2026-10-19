package com.agenticai.core.agents.scheduledtask;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.Capabilities;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.PreviewRequest;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.PreviewResponse;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.RunResponse;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskRequest;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.tenancy.OffsetLimitRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Configuration (the HTTP half and the {@code schedule_task} tool's create path) of scheduled tasks. Firing and
 * settling live in {@link ScheduledTaskRunner}; this class only decides who may create what, validates it, and
 * reads it back.
 *
 * <p>Throws only types {@code GlobalExceptionHandler} already maps: {@link IllegalArgumentException} (400), {@link
 * TenantLimitExceededException} (403 "upgrade your plan"), {@link NoSuchElementException} (404 - also used for "not
 * yours", so a task's existence is not disclosed to another user) and {@link ResponseStatusException} (404 while the
 * feature flag is off).
 *
 * <p>Access model: creating needs RUNNER on the agent (the same bar as chatting with it - the task runs it as the
 * owner, with the owner's budget); a task is its owner's, visible and editable by them; a holder of {@code
 * PERM_MANAGE_AGENTS} may also list, pause, resume, run and delete anyone's.
 */
@Service
public class ScheduledTaskService {

    private static final int NAME_MAX = 120;
    private static final int MAX_RUNS_CEILING = 10_000;
    private static final int UPCOMING_SHOWN = 3;
    private static final int RUN_LIST_LIMIT = ScheduledTaskRunner.RUN_HISTORY_LIMIT;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{7,15}$");
    private static final Pattern TELEGRAM_CHAT = Pattern.compile("^-?[0-9]{3,20}$");
    private static final Pattern SLACK_TARGET = Pattern.compile("^[A-Za-z0-9_-]{3,40}$");
    private static final Pattern MATRIX_MXID = Pattern.compile("^@[^@\\s:]+:[^@\\s]+$");

    private final ScheduledTaskRepository tasks;
    private final ScheduledTaskRunRepository runs;
    private final AgentService agentService;
    private final ScheduledTaskSettings settings;
    private final TenantLimits tenantLimits;
    private final ScheduledTaskRunner runner;
    private final Clock clock;

    @Autowired
    public ScheduledTaskService(
        ScheduledTaskRepository tasks,
        ScheduledTaskRunRepository runs,
        AgentService agentService,
        ScheduledTaskSettings settings,
        TenantLimits tenantLimits,
        ScheduledTaskRunner runner
    ) {
        this(tasks, runs, agentService, settings, tenantLimits, runner, Clock.systemUTC());
    }

    /** Test seam - a pinned clock makes the next-run arithmetic deterministic. */
    ScheduledTaskService(
        ScheduledTaskRepository tasks,
        ScheduledTaskRunRepository runs,
        AgentService agentService,
        ScheduledTaskSettings settings,
        TenantLimits tenantLimits,
        ScheduledTaskRunner runner,
        Clock clock
    ) {
        this.tasks = tasks;
        this.runs = runs;
        this.agentService = agentService;
        this.settings = settings;
        this.tenantLimits = tenantLimits;
        this.runner = runner;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    /** Answers even with the feature off, so the console can say "not enabled" instead of erroring. */
    public Capabilities capabilities() {
        int maxTasks = settings.getMaxPerTenant();
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && tenantLimits.plan(tenantId) == TenantPlan.FREE) {
            // Report the cap THIS caller will hit, not the paid one, so the console never shows "0 of 10" then 403s at 3.
            maxTasks = Math.min(maxTasks, settings.getFreeMaxPerTenant());
        }
        return new Capabilities(
            settings.isEnabled(), maxTasks, settings.getMinIntervalMinutes(),
            settings.getMaxPromptChars(), settings.getDeliveryChannels().stream().sorted().toList());
    }

    public List<TaskResponse> list() {
        requireEnabled();
        List<ScheduledTask> rows = isManager() ? tasks.findAllByOrderByCreatedAtDesc()
            : tasks.findByOwnerSubjectOrderByCreatedAtDesc(subject());
        return rows.stream().map(this::toResponse).toList();
    }

    public TaskResponse get(UUID id) {
        requireEnabled();
        return toResponse(requireVisible(id));
    }

    public List<RunResponse> runs(UUID id, int limit) {
        return runsPage(id, limit, 0).items();
    }

    /** One page of run history plus the task's total retained run count (for X-Total-Count). */
    public RunsPage runsPage(UUID id, int limit, long offset) {
        requireEnabled();
        requireVisible(id);
        int bounded = Math.max(1, Math.min(limit, RUN_LIST_LIMIT));
        long start = Math.min(Math.max(offset, 0), 1_000_000_000L);
        List<RunResponse> rows =
            runs.findByTaskIdOrderByStartedAtDescIdDesc(id, new OffsetLimitRequest(start, bounded)).stream()
                .map(RunResponse::from)
                .toList();
        long seen = start + rows.size();
        long total = (start == 0 && rows.size() < bounded) ? seen : Math.max(runs.countByTaskId(id), seen);
        return new RunsPage(rows, total, bounded, start);
    }

    /** Rows plus the exact total; {@code pageSize}/{@code offset} are the clamped values actually used. */
    public record RunsPage(List<RunResponse> items, long total, int pageSize, long offset) {
        public boolean hasMore() {
            return total > offset + items.size();
        }
    }

    /** Validates a schedule and returns its description and next three runs, without saving anything. */
    public PreviewResponse preview(PreviewRequest request) {
        requireEnabled();
        Instant now = clock.instant();
        ScheduledTaskSchedule.Compiled compiled = compile(
            request.frequency(), request.time(), request.day(), request.cron(), request.timezone(), now);
        return new PreviewResponse(
            compiled.cron(),
            ScheduledTaskSchedule.describe(compiled.cron(), compiled.timezone()),
            compiled.timezone(),
            ScheduledTaskSchedule.nextRuns(compiled.cron(), compiled.timezone(), now, UPCOMING_SHOWN));
    }

    // ----------------------------------------------------------------- writes

    public TaskResponse create(TaskRequest request) {
        requireEnabled();
        TenantContext context = requireContext();
        String owner = subject();
        Agent agent = agentService.requireAccess(request.agentId(), PermissionLevel.RUNNER);
        requireUnattendedAgent(agent);
        Instant now = clock.instant();
        Validated v = validate(request, context.getTenantId(), now);
        requireCapacity(context.getTenantId());
        Instant next = ScheduledTaskSchedule.nextAfter(v.schedule().cron(), v.schedule().timezone(), now);
        ScheduledTask task = ScheduledTask.create(
            UUID.randomUUID(), request.agentId(), v.name(), v.prompt(), v.schedule(), v.delivery(), v.deliveryTo(),
            request.maxRuns(), request.expiresAt(), owner, next, now);
        return toResponse(tasks.save(task));
    }

    public TaskResponse update(UUID id, TaskRequest request) {
        requireEnabled();
        TenantContext context = requireContext();
        ScheduledTask task = requireOwned(id);
        if (!task.getAgentId().equals(request.agentId())) {
            // A task is bound to the agent it was created for: moving it would hand an existing schedule a new
            // identity to run as, so it is a new task, not an edit.
            throw new IllegalArgumentException(
                "A scheduled task cannot be moved to another agent - create a new task for that agent instead.");
        }
        requireUnattendedAgent(agentService.requireAccess(task.getAgentId(), PermissionLevel.RUNNER));
        Instant now = clock.instant();
        Validated v = validate(request, context.getTenantId(), now);
        Instant next = ScheduledTaskSchedule.nextAfter(v.schedule().cron(), v.schedule().timezone(), now);
        task.reconfigure(v.name(), v.prompt(), v.schedule(), v.delivery(), v.deliveryTo(), request.maxRuns(),
            request.expiresAt(), next, now);
        return toResponse(tasks.save(task));
    }

    public void delete(UUID id) {
        requireEnabled();
        ScheduledTask task = requireVisible(id);
        tasks.delete(task);
    }

    public TaskResponse pause(UUID id) {
        requireEnabled();
        ScheduledTask task = requireVisible(id);
        task.pause(clock.instant());
        return toResponse(tasks.save(task));
    }

    public TaskResponse resume(UUID id) {
        requireEnabled();
        ScheduledTask task = requireVisible(id);
        Instant now = clock.instant();
        if (task.runCapReached()) {
            throw new IllegalArgumentException(
                "This task has used all " + task.getMaxRuns() + " of its runs. Edit it to raise the limit first.");
        }
        if (task.expired(now)) {
            throw new IllegalArgumentException("This task's end date has passed. Edit it to extend the end date first.");
        }
        requireUnattendedAgent(agentService.requireAccess(task.getAgentId(), PermissionLevel.RUNNER));
        Instant next = ScheduledTaskSchedule.nextAfter(task.getCron(), task.getTimezone(), now);
        if (next == null) {
            throw new IllegalArgumentException("This task's schedule has no future runs. Edit the schedule first.");
        }
        task.resume(next, now);
        return toResponse(tasks.save(task));
    }

    /**
     * Fires the task once, right now, through the same guards as the tick (overlap, usable agent). Does not touch
     * {@code next_run_at}. A refusal comes back as a SKIPPED run row, not an error. Works on a paused task.
     */
    public RunResponse runNow(UUID id) {
        requireEnabled();
        ScheduledTask task = requireVisible(id);
        if (task.runCapReached()) {
            throw new IllegalArgumentException(
                "This task has used all " + task.getMaxRuns() + " of its runs. Edit it to raise the limit first.");
        }
        Instant now = clock.instant();
        return RunResponse.from(runner.fire(task, now, now, ScheduledTaskRun.TRIGGER_MANUAL, 1));
    }

    // ------------------------------------------------------------ validation

    /** A request after validation: trimmed text, the compiled schedule and the normalized delivery. */
    record Validated(String name, String prompt, ScheduledTaskSchedule.Compiled schedule,
                     ScheduledTaskDelivery delivery, String deliveryTo) {
    }

    Validated validate(TaskRequest request, String tenantId, Instant now) {
        String name = request.name() == null ? "" : request.name().replaceAll("\\s+", " ").trim();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw new IllegalArgumentException("Give the task a short name (1-" + NAME_MAX + " characters).");
        }
        String prompt = request.prompt() == null ? "" : request.prompt().strip();
        if (prompt.isEmpty()) {
            throw new IllegalArgumentException("Say what the task should do - the prompt is empty.");
        }
        if (prompt.length() > settings.getMaxPromptChars()) {
            throw new IllegalArgumentException(
                "The prompt is too long (" + prompt.length() + " characters; the limit is " + settings.getMaxPromptChars() + ").");
        }
        ScheduledTaskSchedule.Compiled schedule = compile(
            request.frequency(), request.time(), request.day(), request.cron(), request.timezone(), now);
        if (request.maxRuns() != null && (request.maxRuns() < 1 || request.maxRuns() > MAX_RUNS_CEILING)) {
            throw new IllegalArgumentException("The run limit must be between 1 and " + MAX_RUNS_CEILING + ".");
        }
        if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("The end date must be in the future.");
        }
        ScheduledTaskDelivery delivery = ScheduledTaskDelivery.parse(request.delivery());
        if (!settings.isDeliveryAllowed(delivery)) {
            throw new IllegalArgumentException("Delivery by " + delivery.key() + " is not enabled in this workspace.");
        }
        String deliveryTo = normalizeDeliveryTo(delivery, request.deliveryTo());
        if ((delivery == ScheduledTaskDelivery.WHATSAPP
                        || delivery == ScheduledTaskDelivery.WHATSAPP_QR)
                && !tenantLimits.whatsappAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                "Delivering by WhatsApp is a Premium feature - upgrade your plan, or choose in-app delivery.");
        }
        return new Validated(name, prompt, schedule, delivery, deliveryTo);
    }

    private ScheduledTaskSchedule.Compiled compile(
        String frequency, String time, String day, String cron, String timezone, Instant now
    ) {
        ScheduledTaskSchedule.Spec spec = new ScheduledTaskSchedule.Spec(
            ScheduledTaskSchedule.Kind.parse(frequency), time, day, cron);
        return ScheduledTaskSchedule.compile(spec, timezone, settings.getMinIntervalMinutes(), now);
    }

    /**
     * The recipient in each channel's own address form: required for every external channel, absent for in-app.
     * Deliberately strict - a malformed target would only fail at the first run, long after anyone is watching.
     */
    static String normalizeDeliveryTo(ScheduledTaskDelivery delivery, String raw) {
        if (!delivery.isExternal()) {
            return null;
        }
        String to = raw == null ? "" : raw.trim();
        if (to.isEmpty()) {
            throw new IllegalArgumentException("Delivery by " + delivery.key() + " needs a recipient (deliveryTo).");
        }
        switch (delivery) {
            case EMAIL -> {
                if (!EMAIL.matcher(to).matches()) {
                    throw new IllegalArgumentException("'" + to + "' is not a valid email address.");
                }
                return to;
            }
            case WHATSAPP -> {
                String digits = to.replaceAll("[\\s()-]", "");
                if (!PHONE.matcher(digits).matches()) {
                    throw new IllegalArgumentException(
                        "WhatsApp needs a phone number with country code, for example +919876543210.");
                }
                return digits;
            }
            case WHATSAPP_QR -> {
                // The linked-device bridge addresses bare digits (country code, no + needed)
                // or a paired chat id in LID form - see WhatsAppQrAddress.isAddressable.
                String digits = to.replaceAll("[\\s()+-]", "");
                if (to.matches("[0-9]{5,30}@lid")) {
                    return to;
                }
                if (!digits.matches("[0-9]{5,20}")) {
                    throw new IllegalArgumentException(
                        "WhatsApp QR needs a phone number with country code, for example 919876543210.");
                }
                return digits;
            }
            case TELEGRAM -> {
                if (!TELEGRAM_CHAT.matcher(to).matches()) {
                    throw new IllegalArgumentException("Telegram needs the numeric chat id to send to.");
                }
                return to;
            }
            case SLACK -> {
                if (!SLACK_TARGET.matcher(to).matches()) {
                    throw new IllegalArgumentException("Slack needs a channel or user id such as C0123456789.");
                }
                return to;
            }
            case SIGNAL -> {
                String digits = to.replaceAll("[\\s()-]", "");
                if (!PHONE.matcher(digits).matches()) {
                    throw new IllegalArgumentException(
                        "Signal needs a phone number with country code, for example +919876543210.");
                }
                return digits;
            }
            case MATRIX -> {
                if (!MATRIX_MXID.matcher(to).matches()) {
                    throw new IllegalArgumentException("Matrix needs a full user id, for example @you:matrix.org.");
                }
                return to;
            }
            case IMESSAGE -> {
                String digits = to.replaceAll("[\\s()-]", "");
                if (PHONE.matcher(digits).matches() || EMAIL.matcher(to).matches()) {
                    return to;
                }
                throw new IllegalArgumentException(
                    "iMessage needs a phone number with country code or the Apple ID email.");
            }
            case A2A -> {
                if (!to.matches("^https?://\\S+$")) {
                    throw new IllegalArgumentException(
                        "A2A delivery needs the remote agent's card URL, for example https://agent.example/.well-known/agent-card.json.");
                }
                return to;
            }
            case VOICE -> {
                String digits = to.replaceAll("[\\s()-]", "");
                if (!PHONE.matcher(digits).matches()) {
                    throw new IllegalArgumentException(
                        "Voice delivery needs a phone number with country code, for example +919876543210.");
                }
                return digits;
            }
            default -> throw new IllegalArgumentException("Unsupported delivery " + delivery.key() + ".");
        }
    }

    /**
     * Only SEMI_AUTONOMOUS agents may run unattended. SUPERVISED would park on its first tool call and hold the task
     * shut; FULLY_AUTONOMOUS turns the confirmation gate and the tenant approval policy OFF (GuardedToolCallback
     * NEVER_GATE), so email-send, payment and other confirmation-required tools would run with nobody present.
     * Returns the refusal text, or null when the tier is acceptable. Shared with the runner so create, update,
     * resume and every firing apply the same rule.
     */
    static String unattendedTierProblem(AgentAutonomyTier tier) {
        if (tier == AgentAutonomyTier.SUPERVISED) {
            return "That agent is on the SUPERVISED tier, which waits for a person to approve every tool call, so it "
                + "cannot run unattended on a schedule. Move it to the SEMI_AUTONOMOUS tier first.";
        }
        if (tier == AgentAutonomyTier.FULLY_AUTONOMOUS) {
            return "That agent is on the FULLY_AUTONOMOUS tier, which skips every approval, so it cannot run unattended "
                + "on a schedule - nobody would be there to stop a risky action. Move it to the SEMI_AUTONOMOUS tier first.";
        }
        return null;
    }

    /** See {@link #unattendedTierProblem}. */
    private static void requireUnattendedAgent(Agent agent) {
        String problem = unattendedTierProblem(agent.getAutonomyTier());
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
    }

    /**
     * Plan gate + per-tenant cap (conventions #8: FREE-usable with tighter caps, never hard-locked by default). Reads
     * the EFFECTIVE plan, so a free-for-all promo that unlocks everything else unlocks the paid cap here too.
     */
    private void requireCapacity(String tenantId) {
        if (tenantLimits.isSharedDemoTenant(tenantId)) {
            throw new TenantLimitExceededException("Scheduled tasks are not available in the shared demo workspace.");
        }
        TenantPlan plan = tenantLimits.plan(tenantId);
        boolean free = plan == TenantPlan.FREE;
        if (free && !settings.isFreePlanEnabled()) {
            throw new TenantLimitExceededException(
                "Scheduled tasks are a Premium feature - upgrade your plan to run prompts on a schedule.");
        }
        int cap = free ? settings.getFreeMaxPerTenant() : settings.getMaxPerTenant();
        if (tasks.count() >= cap) {
            throw new TenantLimitExceededException(
                "This workspace already has the maximum of " + cap + " scheduled tasks"
                    + (free ? " on the free plan - upgrade for more, or" : " -") + " delete one before adding another.");
        }
    }

    // --------------------------------------------------------------- internals

    private void requireEnabled() {
        if (!settings.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scheduled tasks are not enabled.");
        }
    }

    private static TenantContext requireContext() {
        return TenantContext.current().orElseThrow(() -> new IllegalStateException(
            "A scheduled task cannot be configured without an authenticated tenant context - every run uses the "
                + "configuring person's identity and budget, so there has to be one"));
    }

    private static String subject() {
        String subject = requireContext().getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalStateException("A scheduled task needs an authenticated user to own it.");
        }
        return subject;
    }

    private static boolean isManager() {
        return TenantContext.current()
            .map(c -> c.hasRole("PERM_MANAGE_AGENTS") || c.hasRole("ROLE_tenant-admin"))
            .orElse(false);
    }

    /** Owner or manager; anyone else gets the same 404 as a missing task. */
    private ScheduledTask requireVisible(UUID id) {
        ScheduledTask task = tasks.findById(id).orElseThrow(() -> new NoSuchElementException("Scheduled task not found"));
        if (!isManager() && !subject().equals(task.getOwnerSubject())) {
            throw new NoSuchElementException("Scheduled task not found");
        }
        return task;
    }

    /** Owner only - editing changes what runs with the owner's authority. */
    private ScheduledTask requireOwned(UUID id) {
        ScheduledTask task = tasks.findById(id).orElseThrow(() -> new NoSuchElementException("Scheduled task not found"));
        if (!subject().equals(task.getOwnerSubject())) {
            throw new NoSuchElementException("Scheduled task not found");
        }
        return task;
    }

    private TaskResponse toResponse(ScheduledTask t) {
        List<Instant> upcoming = List.of();
        if (t.isEnabled()) {
            try {
                upcoming = ScheduledTaskSchedule.nextRuns(t.getCron(), t.getTimezone(), clock.instant(), UPCOMING_SHOWN);
            } catch (IllegalArgumentException unreadable) {
                upcoming = List.of();
            }
        }
        return new TaskResponse(
            t.getId(), t.getAgentId(), t.getName(), t.getPrompt(), t.getScheduleKind().name().toLowerCase(java.util.Locale.ROOT),
            t.getScheduleTime(), t.getScheduleDay(), t.getCron(), t.getTimezone(),
            ScheduledTaskSchedule.describe(t.getCron(), t.getTimezone()),
            t.getDelivery().key(), t.getDeliveryTo(), t.isEnabled(), t.getDisabledReason(), t.getNextRunAt(), upcoming,
            t.getLastRunAt(), t.getLastStatus(), t.getLastJobId(), t.getConsecutiveFailures(), t.getRunCount(),
            t.getMaxRuns(), t.getExpiresAt(), t.getOwnerSubject(), t.getCreatedAt(), t.getUpdatedAt());
    }

}
