package com.agenticai.core.secrets.dto;

import com.agenticai.core.secrets.SecretKind;
import com.agenticai.core.secrets.SecretScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Create-or-rotate payload.
 *
 * <p>Note what is absent: there is no {@code ownerUserId}. A USER-scoped
 * secret always belongs to the authenticated caller, taken from the token.
 * Accepting an owner from the request body would let an admin plant a secret
 * that later resolves under another person's identity - the request shape
 * itself forecloses that, rather than relying on a service-layer check.
 */
public record SecretStoreRequest(

    @NotBlank
    @Pattern(
        regexp = "^[A-Z][A-Z0-9_]{0,63}$",
        message = "Use UPPER_SNAKE_CASE, starting with a letter, max 64 characters")
    String name,

    @NotNull
    SecretScope scope,

    @NotNull
    SecretKind kind,

    @Size(max = 512)
    String description,

    /**
     * The plaintext. Travels once, over TLS, in a request body - never a query
     * parameter, where it would land in access logs and browser history.
     */
    @NotBlank
    @Size(min = 6, max = 8192,
        message = "A secret must be at least 6 characters - shorter values cannot be reliably "
            + "removed from tool output.")
    String value,

    /** Optional. Set it for anything short-lived so the UI can warn before it silently starts failing. */
    Instant expiresAt,

    /**
     * Opt this secret in to use by tenant-authored sandbox code (V228).
     *
     * <p>Defaults to false when omitted, and that default is load-bearing: a
     * program that holds a plaintext value can always return it, so setting
     * this is equivalent to disclosing the value to everyone who can author a
     * code tool in the workspace. Left off, the secret is usable by MCP
     * servers and agents but unreadable from sandboxed code.
     */
    Boolean codeToolAccess,

    /**
     * Optional destination allow-list (SEC-006): host patterns this secret may
     * be sent to by http_request / workflow HTTP nodes - an exact host
     * ({@code api.stripe.com}) or a subdomain wildcard ({@code *.example.com});
     * no bare {@code *}. Omitted on a rotation = keep the current list;
     * omitted on create = none, which means the secret is NEVER sent to any
     * URL-bound destination (MCP env / integrations are unaffected).
     */
    @Size(max = 32)
    List<@Size(max = 255) String> allowedHosts
) {}
