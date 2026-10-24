package com.agenticai.core.agents.lab;

import static com.agenticai.core.agents.lab.AgentLabDtos.KIND_CONSISTENCY;
import static com.agenticai.core.agents.lab.AgentLabDtos.KIND_KNOCKOUT;
import static com.agenticai.core.agents.lab.AgentLabDtos.MODE_JUDGE;
import static com.agenticai.core.agents.lab.AgentLabDtos.MODE_NONE;

import com.agenticai.core.agents.lab.AgentLabDtos.LabCellRequest;
import com.agenticai.core.agents.lab.AgentLabDtos.LabMessage;
import com.agenticai.core.agents.lab.AgentLabDtos.LabRunRequest;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabTurnMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Request validation for {@link AgentLabRunService}: turns a {@link LabRunRequest} into the
 * {@link RunPlan} the worker runs, or rejects it with an {@link IllegalArgumentException} whose
 * message is shown to the caller. Pure and static; the limits live on {@link AgentLabRunService}.
 */
final class LabRunPlanner {

    private LabRunPlanner() {}

    /** The plan-tier limits a request is validated against. */
    record LabCaps(int cells, int calls, int systemPrompts, int similarityCells) {}


    /** One cell as the worker runs it: validated, with history and reference answer already cut to size. */
    record Cell(
        String key, Integer promptIndex, String message, List<LabTurnMessage> history, String expected
    ) {}

    /**
     * Everything the worker needs. Lives only in the worker's memory - the
     * prompts and cell requests are never written to the run document.
     */
    record RunPlan(
        String runId, UUID agentId, String kind, String tenantId, String subject,
        List<String> systemPrompts, List<Cell> cells, boolean judgeScoring, boolean judgeSimilarity,
        int plannedCalls, long createdAtMs, boolean withTools
    ) {}

    /**
     * The server's copy of the call estimate the client shows before start
     * ({@code plannedCalls} in labApi.ts): one call per cell, a second when
     * each answer is judged, and one per unordered pair when answers are
     * compared.
     */
    static int plannedCalls(int cells, boolean judgeScoring, boolean judgeSimilarity) {
        return cells * (judgeScoring ? 2 : 1) + (judgeSimilarity ? cells * (cells - 1) / 2 : 0);
    }

    static RunPlan validate(
        LabRunRequest request, String runId, UUID agentId, String tenantId, String subject, long nowMs,
        LabCaps caps
    ) {
        if (request == null) {
            throw new IllegalArgumentException("A lab run request is required.");
        }
        String kind = request.kind();
        if (!KIND_KNOCKOUT.equals(kind) && !KIND_CONSISTENCY.equals(kind)) {
            throw new IllegalArgumentException("kind must be KNOCKOUT or CONSISTENCY.");
        }
        boolean judgeScoring = judgeMode("scoring", request.scoring());
        boolean judgeSimilarity = judgeMode("similarity", request.similarity());

        List<String> prompts = request.systemPrompts() == null ? List.of() : request.systemPrompts();
        if (prompts.size() > caps.systemPrompts()) {
            throw new IllegalArgumentException(
                "A lab run on your plan takes at most " + caps.systemPrompts() + " system prompts.");
        }
        for (int i = 0; i < prompts.size(); i++) {
            String prompt = prompts.get(i);
            if (prompt == null) {
                // null would silently mean "the agent's stored prompt" to the
                // completion service; an explicit list entry must be a string.
                throw new IllegalArgumentException(
                    "systemPrompts[" + i + "] is missing. Use an empty string for no system prompt.");
            }
            if (prompt.length() > AgentLabRunService.MAX_PROMPT_CHARS) {
                throw new IllegalArgumentException(
                    "systemPrompts[" + i + "] is too long (" + AgentLabRunService.MAX_PROMPT_CHARS + " character maximum).");
            }
        }

        List<LabCellRequest> requested = request.cells();
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("A lab run needs at least one cell.");
        }
        if (requested.size() > caps.cells()) {
            throw new IllegalArgumentException(
                "A lab run on your plan takes at most " + caps.cells() + " cells.");
        }
        if (judgeSimilarity && requested.size() > caps.similarityCells()) {
            throw new IllegalArgumentException(
                "Comparing answers on your plan takes at most " + caps.similarityCells() + " cells.");
        }
        int planned = plannedCalls(requested.size(), judgeScoring, judgeSimilarity);
        if (planned > caps.calls()) {
            throw new IllegalArgumentException(
                "This run would make " + planned + " model calls; the limit on your plan is "
                    + caps.calls() + ".");
        }

        Set<String> keys = new HashSet<>();
        List<Cell> cells = new ArrayList<>(requested.size());
        for (int i = 0; i < requested.size(); i++) {
            LabCellRequest cell = requested.get(i);
            if (cell == null || cell.key() == null || cell.key().isBlank()) {
                throw new IllegalArgumentException("cells[" + i + "] needs a key.");
            }
            String key = cell.key();
            if (key.length() > AgentLabRunService.MAX_KEY_CHARS) {
                throw new IllegalArgumentException(
                    "cells[" + i + "] has a key longer than " + AgentLabRunService.MAX_KEY_CHARS + " characters.");
            }
            if (!keys.add(key)) {
                throw new IllegalArgumentException("Cell key '" + key + "' is used more than once.");
            }
            if (cell.message() == null || cell.message().isBlank()) {
                throw new IllegalArgumentException("Cell '" + key + "' has no message.");
            }
            if (cell.message().length() > AgentLabRunService.MAX_MESSAGE_CHARS) {
                throw new IllegalArgumentException(
                    "Cell '" + key + "' has a message that is too long (" + AgentLabRunService.MAX_MESSAGE_CHARS
                        + " character maximum).");
            }
            Integer promptIndex = cell.promptIndex();
            if (promptIndex != null && (promptIndex < 0 || promptIndex >= prompts.size())) {
                throw new IllegalArgumentException(
                    "Cell '" + key + "' points at systemPrompts[" + promptIndex + "], which does not exist.");
            }
            cells.add(new Cell(
                key, promptIndex, cell.message(), normaliseHistory(key, cell.history()),
                normaliseExpected(cell.expected())));
        }
        return new RunPlan(
            runId, agentId, kind, tenantId, subject, List.copyOf(prompts), List.copyOf(cells),
            judgeScoring, judgeSimilarity, planned, nowMs, Boolean.TRUE.equals(request.withTools()));
    }

    private static boolean judgeMode(String field, String value) {
        if (MODE_JUDGE.equals(value)) {
            return true;
        }
        if (MODE_NONE.equals(value)) {
            return false;
        }
        throw new IllegalArgumentException(field + " must be NONE or JUDGE.");
    }

    /**
     * Rejects an unknown role; everything else is cut to size, never
     * rejected: blank entries dropped, the last {@link #MAX_HISTORY} kept,
     * each content truncated. A long conversation must not make the
     * Consistency check unusable.
     *
     * <p>What is kept never starts with an assistant turn. Cutting to the
     * last 20 (or dropping a blank user turn) can leave a reply at the head
     * with nothing before it, and providers that require the conversation to
     * open with the user reject every such call. Same boundary rule as the
     * chat path's replay window ({@code SessionMemoryService}).
     */
    private static List<LabTurnMessage> normaliseHistory(String key, List<LabMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<LabTurnMessage> kept = new ArrayList<>(history.size());
        for (LabMessage message : history) {
            if (message == null) {
                continue;
            }
            String role = message.role();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new IllegalArgumentException(
                    "Cell '" + key + "' has a history entry whose role is not user or assistant.");
            }
            String content = message.content();
            if (content == null || content.isBlank()) {
                continue;
            }
            kept.add(new LabTurnMessage(
                role, content.length() <= AgentLabRunService.MAX_HISTORY_CHARS ? content : content.substring(0, AgentLabRunService.MAX_HISTORY_CHARS)));
        }
        int from = Math.max(0, kept.size() - AgentLabRunService.MAX_HISTORY);
        while (from < kept.size() && "assistant".equals(kept.get(from).role())) {
            from++;
        }
        return List.copyOf(kept.subList(from, kept.size()));
    }

    private static String normaliseExpected(String expected) {
        if (expected == null || expected.isBlank()) {
            return null;
        }
        return expected.length() <= AgentLabRunService.MAX_EXPECTED_CHARS ? expected : expected.substring(0, AgentLabRunService.MAX_EXPECTED_CHARS);
    }
}
