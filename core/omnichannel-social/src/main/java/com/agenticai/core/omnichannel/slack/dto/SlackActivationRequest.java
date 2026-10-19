package com.agenticai.core.omnichannel.slack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SlackActivationRequest(
        @NotBlank @Size(max = 4096) String botToken,
        @NotBlank @Size(max = 4096) String signingSecret) {}
