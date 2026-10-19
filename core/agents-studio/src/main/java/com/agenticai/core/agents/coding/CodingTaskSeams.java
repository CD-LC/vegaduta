package com.agenticai.core.agents.coding;

import static com.agenticai.core.agents.coding.CodingTaskDtos.SOURCE_ARCHIVE;
import static com.agenticai.core.agents.coding.CodingTaskDtos.SOURCE_GIT;

import com.agenticai.core.agents.coding.CodingTaskDtos.Budget;
import com.agenticai.core.agents.coding.CodingTaskService.Plan;
import com.agenticai.core.agents.coding.CodingTaskService.StartInput;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import com.agenticai.core.agents.turn.AgentLabCompletionService;
import com.agenticai.core.sdlc.harness.CodingCommandPolicy;
import com.agenticai.core.sdlc.harness.CodingSandbox;
import com.agenticai.core.sdlc.harness.CodingSandboxFactory;
import com.agenticai.core.sdlc.harness.GitUrlPolicy;
import com.agenticai.core.sdlc.harness.RepoArchive;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Production seams and request validation for {@link CodingTaskService}, split out of it to keep the
 * service under the file-size cap: the model a task calls (the agent's own, through the Lab
 * completion service) and the checks that turn a {@link StartInput} into a {@link Plan}.
 */
final class CodingTaskSeams {

    private CodingTaskSeams() {}

    /** Production {@link CodingTaskService.SandboxOpener}: straight through to {@link CodingSandboxFactory}. */
    static CodingTaskService.SandboxOpener sandboxOpener(CodingSandboxFactory sandboxFactory) {
        return new CodingTaskService.SandboxOpener() {
            @Override
            public CodingSandbox open(int ttlSeconds) {
                return sandboxFactory.open(ttlSeconds);
            }

            @Override
            public String notReadyReason() {
                return sandboxFactory.notReadyReason();
            }

            @Override
            public CodingCommandPolicy policy() {
                return sandboxFactory.policy();
            }

            @Override
            public String runnerName() {
                return sandboxFactory.runnerName();
            }
        };
    }

    /** The agent's own model, one tool-less completion per call, attributed to the task in the call log. */
    static final class LabCompletionModel implements CodingModel {
        private final AgentLabCompletionService completions;
        private final UUID agentId;
        private final String subject;
        private final String taskId;
        private int calls;

        LabCompletionModel(AgentLabCompletionService completions, UUID agentId, String subject, String taskId) {
            this.completions = completions;
            this.agentId = agentId;
            this.subject = subject;
            this.taskId = taskId;
        }

        @Override
        public String complete(String systemPrompt, String userMessage) {
            return completions.complete(new LabCompletionRequest(
                agentId, systemPrompt, List.of(), userMessage, 0.2, 8000, subject, "coding:" + taskId + ":" + (++calls)));
        }
    }

    static Plan validate(StartInput in, String taskId, String tenantId, String subject, CodingTaskSettings settings,
                         CodingTaskService.SandboxOpener sandboxes, GitUrlPolicy gitPolicy, LongSupplier clock) {
        String instruction = in.instruction() == null ? "" : in.instruction().strip();
        if (instruction.isEmpty()) {
            throw new IllegalArgumentException("Describe what the code should do.");
        }
        if (instruction.length() > settings.maxInstructionChars()) {
            throw new IllegalArgumentException("The instruction is too long (" + settings.maxInstructionChars()
                + " character maximum).");
        }
        String testCommand = sandboxes.policy().validate(in.testCommand());

        boolean hasUrl = in.repoUrl() != null && !in.repoUrl().isBlank();
        boolean hasArchive = in.archive() != null && in.archive().length > 0;
        if (hasUrl == hasArchive) {
            throw new IllegalArgumentException("Provide either a public https repository URL or an uploaded archive, not both.");
        }
        String sourceKind;
        String repoUrl = null;
        byte[] zip = null;
        if (hasUrl) {
            sourceKind = SOURCE_GIT;
            repoUrl = gitPolicy.check(in.repoUrl());
        } else {
            sourceKind = SOURCE_ARCHIVE;
            RepoArchive.Limits limits = new RepoArchive.Limits(
                settings.maxArchiveBytes(), Math.max(settings.maxArchiveBytes() * 2, 8L << 20), 5L << 20, 20_000);
            zip = RepoArchive.sanitize(in.archive(), limits).zip();
        }
        Budget budget = clampBudget(in, settings);
        return new Plan(taskId, tenantId, subject, in.agentId(), instruction, testCommand, sourceKind, repoUrl, zip,
            budget, clock.getAsLong());
    }

    private static Budget clampBudget(StartInput in, CodingTaskSettings settings) {
        Budget d = settings.defaults();
        Budget c = settings.caps();
        return new Budget(
            clampInt(in.maxSteps(), d.maxSteps(), 1, c.maxSteps()),
            clampInt(in.maxWallSeconds(), d.maxWallSeconds(), 60, c.maxWallSeconds()),
            clampInt(in.maxTokens(), d.maxTokens(), 1000, c.maxTokens()),
            clampInt(in.maxFixIterations(), d.maxFixIterations(), 0, c.maxFixIterations()));
    }

    private static int clampInt(Integer requested, int fallback, int min, int max) {
        int v = requested == null ? fallback : requested;
        return Math.max(min, Math.min(max, v));
    }
}
