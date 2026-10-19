package com.agenticai.core.agents.job.operator;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.job.operator.dto.AgentJobCountsResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The aggregate half of the operator surface: "how much work is in flight, and
 * how much finished badly lately", across every agent at once.
 *
 * <p><b>There is deliberately no list endpoint here.</b> {@code
 * AgentJobController} already serves {@code GET /api/agent-jobs} with status
 * filtering, an optional {@code agentId} and a page size capped at 200. Two
 * controllers both answering "list jobs" would drift in their filtering and
 * their access rules - that PR's own javadoc says so - so this class adds only
 * the number that page of rows structurally cannot carry: how many rows there
 * are behind it.
 *
 * <p>Nothing here writes. {@code readOnly = true} is not decoration: these
 * counts are polled by a dashboard, and a read-only transaction is what keeps a
 * refresh loop from taking a writable Hikari connection away from the four
 * bounded job workers that actually need one.
 */
@Service
public class AgentJobOperatorService {

    /**
     * The default lookback for the terminal counts. A day rather than an hour
     * because an operator opening the dashboard on Monday needs the weekend's
     * failures to still be visible; an hour would show a clean board and hide a
     * schedule that broke on Saturday.
     */
    static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    /**
     * Clamps, not 400s - same posture as the job submit path's timeout handling.
     * A client asking for a year is asking for "as much as you'll give me", and
     * rejecting it teaches nothing while hard-coding the current maximum into
     * every integrator's source. The response echoes the window actually applied
     * so the clamp is visible rather than silent.
     *
     * <p>Constants rather than {@code app.agent-jobs.*} properties on purpose:
     * the window is already a per-request query parameter, so a second,
     * deployment-wide knob for the same value would only create a way for the
     * two to disagree. It also keeps this PR out of {@code application.yml},
     * which the foundation PR writes exactly once.
     */
    static final Duration MIN_WINDOW = Duration.ofMinutes(1);
    static final Duration MAX_WINDOW = Duration.ofDays(30);

    private final AgentJobOperatorRepository repository;
    private final AgentService agentService;

    public AgentJobOperatorService(AgentJobOperatorRepository repository, AgentService agentService) {
        this.repository = repository;
        this.agentService = agentService;
    }

    /**
     * Rolls up job counts for everything the caller is allowed to see.
     *
     * <p><b>Scope resolution, in the order it is attempted, and why.</b>
     *
     * <ol>
     *   <li>An explicit {@code agentId} is checked with {@link
     *       AgentService#requireAccess} at VIEWER, exactly like every per-agent
     *       job read - so an operator cannot use a counts endpoint to learn that
     *       an agent id exists. {@code requireAccess} 404s anti-enumeration-style
     *       on denial, which is the behaviour we want here unchanged.
     *   <li>A tenant-admin or platform-admin gets the whole tenant schema
     *       unfiltered, in ONE query. This is both the correct answer and the
     *       cheap path, and it is the overwhelmingly common one - an "operator
     *       surface" is a thing tenant-admins open.
     *   <li>Everyone else gets counts narrowed to the agents they can already
     *       see in a list, sourced from {@link AgentService#listAll()}.
     * </ol>
     *
     * <p>Step 3 calls {@code listAll()} rather than reimplementing its rule
     * ("legacy agent, or mine, or admin, or I hold a grant") as a query
     * predicate here. That reimplementation is specifically what {@code
     * AgentJobService}'s tenant list refuses to do, for the reason that applies
     * just as hard to an aggregate: there must be exactly one answer to "which
     * agents may this caller see", or the two answers drift and one of them
     * starts leaking. The cost is bounded and already paid on every agents
     * screen - {@code TenantLimits#maxAgents} caps a tenant in the tens to low
     * hundreds. If this ever becomes hot, the fix is an id-only projection ON
     * {@code AgentService}, not a second copy of the predicate in this file.
     *
     * <p><b>Known, accepted gap:</b> a job whose agent has since been deleted is
     * invisible to a non-admin's counts, because {@code agent_id} is
     * intentionally not a foreign key so run history outlives the definition.
     * Admins still see those rows (step 2 does not join to {@code agent} at
     * all), and they are the ones who would act on them.
     *
     * @param agentIdOrNull  narrow to one agent, or {@code null} for everything
     *                       in scope
     * @param requestedWindow lookback for the terminal counts; {@code null} means
     *                        {@link #DEFAULT_WINDOW}
     */
    @Transactional(readOnly = true)
    public AgentJobCountsResponse counts(UUID agentIdOrNull, Duration requestedWindow) {
        Duration window = clampWindow(requestedWindow);
        Instant asOf = Instant.now();
        Instant since = asOf.minus(window);
        Set<AgentJobStatus> live = AgentJobStatus.live();

        if (agentIdOrNull != null) {
            agentService.requireAccess(agentIdOrNull, PermissionLevel.VIEWER);
            return AgentJobCountsResponse.of(
                tally(repository.rollUpForAgents(Set.of(agentIdOrNull), live, since)),
                window, asOf, agentIdOrNull, false
            );
        }

        if (seesWholeTenant()) {
            return AgentJobCountsResponse.of(
                tally(repository.rollUpTenantWide(live, since)),
                window, asOf, null, true
            );
        }

        Set<UUID> visible = visibleAgentIds();
        if (visible.isEmpty()) {
            // Short-circuited rather than issued: an empty IN list is a question
            // with a known answer, and the response still has to be a complete
            // six-field zero rather than an empty body.
            return AgentJobCountsResponse.of(Map.of(), window, asOf, null, false);
        }
        return AgentJobCountsResponse.of(
            tally(repository.rollUpForAgents(visible, live, since)),
            window, asOf, null, false
        );
    }

    // ------------------------------------------------------------- internals

    private Duration clampWindow(Duration requested) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            return DEFAULT_WINDOW;
        }
        if (requested.compareTo(MIN_WINDOW) < 0) {
            return MIN_WINDOW;
        }
        return requested.compareTo(MAX_WINDOW) > 0 ? MAX_WINDOW : requested;
    }

    /**
     * Whether this caller's counts may span the entire tenant schema. Exactly
     * the two roles {@link AgentService#listAll()} treats as seeing everything,
     * read from the same place, so "admin" cannot come to mean one thing in the
     * list and another in the summary above it.
     */
    private boolean seesWholeTenant() {
        // Same authority AgentService#listAll checks (granular RBAC
        // 2026-09-23): PERM_MANAGE_AGENTS, not the blanket tenant-admin role.
        return TenantContext.current()
            .map(ctx -> ctx.hasRole("PERM_MANAGE_AGENTS") || ctx.hasRole("ROLE_platform-admin"))
            .orElse(false);
    }

    private Set<UUID> visibleAgentIds() {
        return agentService.listAll().stream()
            .map(AgentResponse::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Sparse projection rows to a status-keyed map. An {@link EnumMap} because
     * the key space is six constants and this runs on a polled endpoint; the
     * absent statuses are filled with zero by {@link AgentJobCountsResponse#of}
     * rather than here, so there is one place that decides what a missing group
     * means.
     */
    private static Map<AgentJobStatus, Long> tally(List<AgentJobStatusCount> rows) {
        Map<AgentJobStatus, Long> byStatus = new EnumMap<>(AgentJobStatus.class);
        for (AgentJobStatusCount row : rows) {
            byStatus.put(row.status(), row.count());
        }
        return byStatus;
    }
}
