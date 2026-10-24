package com.agenticai.core.quality.dataset;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The platform's rules for running an eval turn through the real tool pipeline: which mode a run uses when nobody
 * chose one, which tools the operator vouches for, and who may use the sandbox auto-approval.
 *
 * <ul>
 *   <li>{@code app.quality.eval.default-mode} (default {@code full}): the {@link EvalMode} of a run that names none
 *       and whose dataset pins none. {@code full} replays through the production pipeline in the eval sandbox;
 *       {@code quick} withholds the agent's tools. A workspace can change its own default with the catalog setting
 *       {@code quality.eval.defaultMode}; this is the deployment's value under it.
 *   <li>{@code app.quality.eval.sandbox.read-only-tools} (default empty): tool names or {@code *} globs the
 *       operator vouches are read-only. In a full run a tool runs for real only if its classification says it only
 *       reads; this list lets a tool with NO classification (an MCP or custom tool nobody classified) run too. It
 *       never overrides a declared classification, so it cannot make a write run.
 *   <li>{@code app.quality.eval.auto-approve.tenant-ids} (default empty): the tenants the OPERATOR declares
 *       non-production, i.e. tenants whose gated tool calls may be approved by the eval sandbox instead of a
 *       person. There is no tenant-level "production" flag to infer this from, so it is an explicit list;
 *       empty means no tenant may auto-approve. A dataset's auto-approve setting, a run trigger and every
 *       replay each check it, so removing a tenant takes effect on the next call.
 *   <li>{@code app.quality.eval.auto-approve.actor} (default {@code eval-auto-approve}): the name the
 *       auto-approvals are attributed to in logs and in the turn journal.
 *   <li>{@code app.quality.eval.auto-approve.excluded-tools}: tool names or {@code *} globs that are never
 *       auto-approved (a gated call to one still parks, as in production): shell, coding, scheduling and
 *       agent-to-agent calls, and anything that reads as moving money or deleting.
 * </ul>
 */
@Component
public class EvalSandboxPolicy {

    /** What the excluded-tools default is, spelled once so the YAML and the docs can be checked against it. */
    public static final String DEFAULT_EXCLUDED_TOOLS =
        "local_shell,coding_task,schedule_task,a2a_call_agent,run_in_code_workspace,apply_code_changes,"
            + "*payment*,*refund*,*charge*,*transfer*,*delete*";

    private final EvalMode defaultMode;
    private final Set<String> tenantIds;
    private final String actor;
    private final List<String> excludedTools;
    private final List<String> readOnlyTools;

    @org.springframework.beans.factory.annotation.Autowired
    public EvalSandboxPolicy(
        @Value("${app.quality.eval.default-mode:full}") String defaultMode,
        @Value("${app.quality.eval.auto-approve.tenant-ids:}") String tenantIds,
        @Value("${app.quality.eval.auto-approve.actor:eval-auto-approve}") String actor,
        @Value("${app.quality.eval.auto-approve.excluded-tools:"
            + "local_shell,coding_task,schedule_task,a2a_call_agent,run_in_code_workspace,apply_code_changes,"
            + "*payment*,*refund*,*charge*,*transfer*,*delete*}") String excludedTools,
        @Value("${app.quality.eval.sandbox.read-only-tools:}") String readOnlyTools
    ) {
        this.defaultMode = EvalMode.parse(defaultMode).orElse(EvalMode.FULL);
        this.tenantIds = csv(tenantIds, false);
        this.actor = actor == null || actor.isBlank() ? "eval-auto-approve" : actor.strip();
        this.excludedTools = List.copyOf(csv(excludedTools, true));
        this.readOnlyTools = List.copyOf(csv(readOnlyTools, true));
    }

    /** The shipped defaults: the full pipeline, no tenant may auto-approve, no unclassified tool vouched for. */
    public static EvalSandboxPolicy defaults() {
        return new EvalSandboxPolicy("full", "", "eval-auto-approve", DEFAULT_EXCLUDED_TOOLS, "");
    }

    /** A policy for tests and callers that need to name every setting. */
    public static EvalSandboxPolicy of(
        EvalMode defaultMode, Set<String> autoApproveTenantIds, String actor, List<String> excludedTools
    ) {
        return new EvalSandboxPolicy(defaultMode.name(), String.join(",", autoApproveTenantIds),
            actor, String.join(",", excludedTools), "");
    }

    /** As above, also naming the unclassified tools the operator vouches are read-only. */
    public static EvalSandboxPolicy of(
        EvalMode defaultMode, Set<String> autoApproveTenantIds, String actor, List<String> excludedTools,
        List<String> readOnlyTools
    ) {
        return new EvalSandboxPolicy(defaultMode.name(), String.join(",", autoApproveTenantIds),
            actor, String.join(",", excludedTools), String.join(",", readOnlyTools));
    }

    private static Set<String> csv(String value, boolean lowerCase) {
        Set<String> out = new LinkedHashSet<>();
        if (value != null) {
            Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> lowerCase ? s.toLowerCase(Locale.ROOT) : s)
                .forEach(out::add);
        }
        return out;
    }

    /** The mode of a run that names none and whose dataset pins none (before the workspace's own setting). */
    public EvalMode defaultMode() {
        return defaultMode;
    }

    /** Tool names or globs the operator vouches are read-only; each applies only to an unclassified tool. */
    public List<String> readOnlyTools() {
        return readOnlyTools;
    }

    /** Whether the operator declared this tenant non-production, so it may use the sandbox auto-approval. */
    public boolean autoApproveAllowedFor(String tenantId) {
        return tenantId != null && tenantIds.contains(tenantId);
    }

    /** @throws EvalSandboxRefusedException when {@code tenantId} is not a declared non-production tenant */
    public void requireAutoApproveAllowed(String tenantId) {
        if (!autoApproveAllowedFor(tenantId)) {
            throw new EvalSandboxRefusedException(
                "Auto-approving tool calls in evals is off for this workspace. It is only available to tenants the"
                    + " operator lists in app.quality.eval.auto-approve.tenant-ids (non-production workspaces);"
                    + " run this dataset with tool approvals left to a person, or text-only.");
        }
    }

    public String actor() {
        return actor;
    }

    public List<String> excludedTools() {
        return excludedTools;
    }

    /** The auto-approval refusal; a bad request, not a server error. */
    public static class EvalSandboxRefusedException extends IllegalArgumentException {
        public EvalSandboxRefusedException(String message) {
            super(message);
        }
    }
}
