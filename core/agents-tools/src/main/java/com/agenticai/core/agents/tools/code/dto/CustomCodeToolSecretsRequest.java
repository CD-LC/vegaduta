package com.agenticai.core.agents.tools.code.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * Replaces which vault secrets a custom code tool may receive.
 *
 * <p>Names only - a value never travels on this path, and there is no endpoint that would accept or
 * return one. Granting is a reference to something already in the vault (or something that will
 * be), not a place to paste a credential.
 *
 * <p>The whole set is replaced rather than patched, so "remove this grant" is expressible. A
 * partial-update shape would make revoking access require a second verb that someone would
 * eventually forget to call.
 */
public record CustomCodeToolSecretsRequest(

        /** Empty clears every grant. Null is rejected so clearing is always deliberate. */
        @NotNull @Size(max = 32, message = "A tool may reference at most 32 secrets.")
                Set<String> allowedSecretNames) {}
