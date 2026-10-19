package com.agenticai.core.omnichannel.matrix;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Changes who may talk to the bot and which invites it accepts, without re-entering the token. */
public record MatrixPolicyRequest(
        @NotNull MatrixJoinPolicy joinPolicy,
        @NotNull MatrixSenderMode senderMode,
        @Size(max = 20000) String allowedSenders) {}
