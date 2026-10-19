package com.agenticai.core.omnichannel.twilio.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** {@code PUT /api/channels/voice/dtmf-menu/{digit}} body - {@code label} is an optional admin-facing note ("Sales", "Support"), never spoken to the caller verbatim. */
public record VoiceDtmfMappingUpsertRequest(@NotNull UUID agentId, String label) {
}
