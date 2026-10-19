package com.agenticai.core.agents.dto;

import jakarta.validation.constraints.Min;

/**
 * POST /api/agents/{id}/chat/sessions/{sessionId}/rewind - time-travel for a
 * conversation: keep only the first {@code keepTurns} stored {@link
 * com.agenticai.core.agents.memory.ChatTurn} entries (a user/assistant
 * exchange is two entries) and drop everything after, so the next message
 * continues the session as if the dropped tail never happened. The client
 * then resends or edits the message it wants to branch from - the platform
 * deliberately does NOT re-run a turn, so no tool call or side effect the
 * original turn fired is repeated.
 */
public record SessionRewindRequest(
    @Min(0) int keepTurns
) {}
