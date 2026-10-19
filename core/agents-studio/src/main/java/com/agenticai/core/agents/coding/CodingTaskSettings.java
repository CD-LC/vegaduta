package com.agenticai.core.agents.coding;

import com.agenticai.core.agents.coding.CodingTaskDtos.Budget;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code app.coding-harness.*} as the coding-task layer reads it. Everything has a hard server-side
 * ceiling that configuration cannot raise past ({@link #HARD_MAX_STEPS} and friends): a
 * misconfigured environment variable must not turn a bounded loop into an unbounded one.
 *
 * <p>There is deliberately NO setting that skips, shortens or pre-answers the human approval gate.
 */
@Component
public class CodingTaskSettings {

    static final int HARD_MAX_STEPS = 100;
    static final int HARD_MAX_WALL_SECONDS = 3600;
    static final int HARD_MAX_TOKENS = 2_000_000;
    static final int HARD_MAX_FIX_ITERATIONS = 10;
    static final long HARD_MAX_ARCHIVE_BYTES = 50L << 20;

    private final boolean enabled;
    private final boolean freePlanEnabled;
    private final Budget defaults;
    private final Budget caps;
    private final long maxArchiveBytes;
    private final int maxInstructionChars;
    private final int approvalTimeoutSeconds;
    private final int rollbackWindowSeconds;
    private final int toolWaitSeconds;
    private final boolean networkEnabled;
    private final String runner;
    private final List<String> gitAllowedHosts;
    private final int testTimeoutSeconds;
    private final int maxOutputChars;

    public CodingTaskSettings(
        @Value("${app.coding-harness.enabled:false}") boolean enabled,
        @Value("${app.coding-harness.free-plan-enabled:false}") boolean freePlanEnabled,
        @Value("${app.coding-harness.max-fix-iterations:3}") int defaultFixIterations,
        @Value("${app.coding-harness.default-steps:20}") int defaultSteps,
        @Value("${app.coding-harness.default-wall-seconds:900}") int defaultWallSeconds,
        @Value("${app.coding-harness.default-tokens:150000}") int defaultTokens,
        @Value("${app.coding-harness.max-steps:40}") int maxSteps,
        @Value("${app.coding-harness.max-wall-seconds:1800}") int maxWallSeconds,
        @Value("${app.coding-harness.max-tokens:400000}") int maxTokens,
        @Value("${app.coding-harness.max-fix-iterations-cap:5}") int maxFixIterations,
        @Value("${app.coding-harness.max-archive-bytes:26214400}") long maxArchiveBytes,
        @Value("${app.coding-harness.max-instruction-chars:4000}") int maxInstructionChars,
        @Value("${app.coding-harness.approval-timeout-seconds:900}") int approvalTimeoutSeconds,
        @Value("${app.coding-harness.rollback-window-seconds:600}") int rollbackWindowSeconds,
        @Value("${app.coding-harness.tool-wait-seconds:240}") int toolWaitSeconds,
        @Value("${app.coding-harness.network-enabled:false}") boolean networkEnabled,
        @Value("${app.coding-harness.runner:e2b}") String runner,
        @Value("${app.coding-harness.git-allowed-hosts:}") String gitAllowedHosts,
        @Value("${app.coding-harness.test-timeout-seconds:300}") int testTimeoutSeconds,
        @Value("${app.coding-harness.max-output-chars:8000}") int maxOutputChars
    ) {
        this.enabled = enabled;
        this.freePlanEnabled = freePlanEnabled;
        this.caps = new Budget(
            clamp(maxSteps, 1, HARD_MAX_STEPS), clamp(maxWallSeconds, 60, HARD_MAX_WALL_SECONDS),
            clamp(maxTokens, 1000, HARD_MAX_TOKENS), clamp(maxFixIterations, 0, HARD_MAX_FIX_ITERATIONS));
        this.defaults = new Budget(
            clamp(defaultSteps, 1, caps.maxSteps()), clamp(defaultWallSeconds, 60, caps.maxWallSeconds()),
            clamp(defaultTokens, 1000, caps.maxTokens()), clamp(defaultFixIterations, 0, caps.maxFixIterations()));
        this.maxArchiveBytes = Math.max(1024, Math.min(HARD_MAX_ARCHIVE_BYTES, maxArchiveBytes));
        this.maxInstructionChars = clamp(maxInstructionChars, 100, 20_000);
        this.approvalTimeoutSeconds = clamp(approvalTimeoutSeconds, 30, 3600);
        this.rollbackWindowSeconds = clamp(rollbackWindowSeconds, 0, 3600);
        this.toolWaitSeconds = clamp(toolWaitSeconds, 5, 600);
        this.networkEnabled = networkEnabled;
        this.testTimeoutSeconds = testTimeoutSeconds;
        this.maxOutputChars = maxOutputChars;
        this.runner = runner == null ? "e2b" : runner.strip().toLowerCase(java.util.Locale.ROOT);
        this.gitAllowedHosts = gitAllowedHosts == null || gitAllowedHosts.isBlank() ? List.of()
            : Arrays.stream(gitAllowedHosts.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isFreePlanEnabled() {
        return freePlanEnabled;
    }

    public Budget defaults() {
        return defaults;
    }

    public Budget caps() {
        return caps;
    }

    public long maxArchiveBytes() {
        return maxArchiveBytes;
    }

    public int maxInstructionChars() {
        return maxInstructionChars;
    }

    public int approvalTimeoutSeconds() {
        return approvalTimeoutSeconds;
    }

    public int rollbackWindowSeconds() {
        return rollbackWindowSeconds;
    }

    public int toolWaitSeconds() {
        return toolWaitSeconds;
    }

    public boolean networkEnabled() {
        return networkEnabled;
    }

    public String runner() {
        return runner;
    }

    public int testTimeoutSeconds() {
        return testTimeoutSeconds;
    }

    public int maxOutputChars() {
        return maxOutputChars;
    }

    public List<String> gitAllowedHosts() {
        return gitAllowedHosts;
    }
}
