package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Who may talk to the agent over iMessage. {@code allowedSenders} are pre-approved phone numbers
 * or emails; everyone else is default-denied (pairing code, or silence with IGNORE). Group chats
 * are off unless {@code allowGroups} is set, and even then only allow-listed senders are served.
 */
public record ImessageSettingsRequest(
        @NotNull ImessageInboundMode inboundMode,
        @NotNull @Size(max = 200) List<@Size(max = 320) String> allowedSenders,
        boolean allowGroups,
        @NotNull UnknownSenderPolicy unknownSenderPolicy) {}
