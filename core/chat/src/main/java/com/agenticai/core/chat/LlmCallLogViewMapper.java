package com.agenticai.core.chat;

/** Maps {@link LlmCallLog} rows to the admin/session API view records, keeping entities out of controllers. */
final class LlmCallLogViewMapper {

    private LlmCallLogViewMapper() {}

    static LlmCallLogController.LlmCallLogEntry toAdminEntry(LlmCallLog log) {
        return new LlmCallLogController.LlmCallLogEntry(
                log.getId(), log.getAgentId(), log.getSessionId(), log.getProviderName(), log.getModel(),
                log.getPromptTokens(), log.getCompletionTokens(), log.getTotalTokens(), log.getEstimatedCostUsd(),
                log.isSuccess(), log.getErrorMessage(), log.getLatencyMs(), log.getStartedAt());
    }

    static AgentSessionLlmUsageController.SessionLlmCallEntry toSessionEntry(LlmCallLog log) {
        return new AgentSessionLlmUsageController.SessionLlmCallEntry(
                log.getId(),
                log.getProviderName(),
                log.getModel(),
                log.getPromptTokens(),
                log.getCompletionTokens(),
                log.getTotalTokens(),
                log.getEstimatedCostUsd(),
                log.isSuccess(),
                log.getLatencyMs(),
                log.getStartedAt());
    }
}
