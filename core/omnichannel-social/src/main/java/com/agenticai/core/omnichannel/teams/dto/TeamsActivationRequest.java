package com.agenticai.core.omnichannel.teams.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The Azure Bot registration values a tenant copies out of the Azure portal: the Microsoft App ID
 * and its client secret, plus the Entra tenant the registration lives in ({@code aadTenantId}
 * optional - {@code "botframework.com"} is the right value for multi-tenant registrations, which is
 * what Teams channel bots usually are; a single-tenant registration passes its own GUID). Validated
 * by minting a real Bot Framework token before persisting - the same "prove it before persisting"
 * pattern every other channel credential here uses.
 */
public record TeamsActivationRequest(
        @NotBlank String appId,
        @NotBlank @Size(max = 4096) String appPassword,
        String aadTenantId) {}
