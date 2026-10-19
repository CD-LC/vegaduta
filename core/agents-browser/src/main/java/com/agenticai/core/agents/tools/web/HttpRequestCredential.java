package com.agenticai.core.agents.tools.web;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A credential for one {@code http_request}, named by the vault secret that holds it - the model
 * only ever supplies the NAME ({@code STRIPE_KEY}); {@link HttpRequestExecutor#executeSafe}
 * resolves the value server-side through {@code SecretReferenceResolver}, injects it as a header,
 * redacts it from everything that travels back, and strips it on any redirect that leaves the
 * original origin.
 *
 * @param secretName vault secret name (upper-cased on resolution, same charset as {@code
 *     SecretVaultService})
 * @param type how the secret becomes a header
 * @param headerName header to carry it for {@link Type#HEADER}; ignored for the other types
 */
public record HttpRequestCredential(String secretName, Type type, String headerName) {

    /**
     * {@code BEARER}: {@code Authorization: Bearer <secret>}; {@code BASIC}: secret is {@code
     * user:password}, sent Base64 as {@code Authorization: Basic ...}; {@code HEADER}: secret is
     * the whole value of {@code headerName}.
     */
    public enum Type {
        BEARER,
        BASIC,
        HEADER
    }

    private static final Pattern SECRET_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    /** RFC 9110 token characters - the only legal header-name alphabet. */
    private static final Pattern HEADER_TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}");

    public HttpRequestCredential {
        if (secretName == null || !SECRET_NAME.matcher(secretName.strip()).matches()) {
            throw new WebPageFetchException(
                    "the 'auth' value must be the NAME of a vault secret (letters, digits and underscores), never the secret itself");
        }
        secretName = secretName.strip();
        type = type == null ? Type.BEARER : type;
        if (type == Type.HEADER) {
            if (headerName == null || !HEADER_TOKEN.matcher(headerName.strip()).matches()) {
                throw new WebPageFetchException(
                        "authType 'header' needs a valid authHeader name, for example X-API-Key");
            }
            headerName = headerName.strip();
        } else {
            headerName = "Authorization";
        }
    }

    /**
     * Builds a credential from the tool's raw arguments, or returns null when no {@code auth} was
     * given. Lenient on spelling of the type ({@code bearer}, {@code Bearer}, {@code api-key} /
     * {@code header}); anything unrecognised is a clear failure, not a silent default to a
     * different scheme.
     */
    public static HttpRequestCredential fromArguments(
            String auth, String authType, String authHeader) {
        if (auth == null || auth.isBlank()) {
            return null;
        }
        String normalised =
                authType == null ? "" : authType.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        Type type =
                switch (normalised) {
                    case "", "bearer" -> Type.BEARER;
                    case "basic" -> Type.BASIC;
                    case "header", "api-key", "apikey" -> Type.HEADER;
                    default ->
                            throw new WebPageFetchException(
                                    "authType must be one of bearer, basic or header");
                };
        return new HttpRequestCredential(auth, type, authHeader);
    }
}
