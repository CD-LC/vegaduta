package com.agenticai.core.omnichannel.email.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * IMAP connection details for a tenant's own support mailbox. {@code imapPassword} is most commonly
 * an app-specific password (Gmail/Outlook both require one when 2FA is on, same as any other IMAP
 * client) - the credential help link on the console form points to each provider's guide.
 */
public record EmailActivationRequest(
        @NotBlank String imapHost,
        @NotNull @Min(1) @Max(65535) Integer imapPort,
        @NotBlank String imapUsername,
        @NotBlank @Size(max = 4096) String imapPassword,
        Boolean useSsl) {}
