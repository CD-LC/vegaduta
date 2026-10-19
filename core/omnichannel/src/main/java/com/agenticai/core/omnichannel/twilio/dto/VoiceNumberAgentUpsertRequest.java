package com.agenticai.core.omnichannel.twilio.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** {@code PUT /api/channels/voice/numbers/{number}} body - {@code label} is an optional admin-facing note ("Mumbai sales line"), never spoken to the caller. */
public record VoiceNumberAgentUpsertRequest(@NotNull UUID agentId, String label) {
}
