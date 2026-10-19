package com.agenticai.core.agents.tools.openapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Create / update request. On create, one of {@code specUrl} / {@code specText} is required and
 * {@code enabledOperationKeys} (the {@code "METHOD /path"} keys from the preview) selects which
 * operations start enabled - {@code null} means all of them. On update ({@code PUT /{id}}) only
 * {@code name}, {@code baseUrl}, {@code authType}, {@code authHeaderName} and {@code secret} are
 * read; a blank {@code secret} keeps the stored credential, so the console never has to round-trip
 * a value it was never given.
 *
 * <p>{@code secret} is the ONLY field on this record that is ever sensitive; it is encrypted before
 * the request thread returns and never logged or echoed. For {@code OAUTH2_CLIENT_CREDENTIALS} it
 * carries the client SECRET while the (non-sensitive) {@code oauth2ClientId} travels on its own
 * field.
 */
public record OpenApiToolSourceRequest(
        @NotBlank String name,
        String toolPrefix,
        String specUrl,
        String specText,
        String baseUrl,
        String authType,
        String authHeaderName,
        @Size(max = 4096) String secret,
        String oauth2TokenUrl,
        String oauth2ClientId,
        String oauth2Scope,
        String oauth2TokenStyle,
        List<String> enabledOperationKeys) {}
