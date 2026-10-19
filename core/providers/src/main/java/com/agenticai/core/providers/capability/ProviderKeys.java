package com.agenticai.core.providers.capability;

/**
 * The well-known {@code provider_key} values {@link ProviderCredential} rows
 * carry - deliberately plain strings, not an enum, matching {@link
 * ProviderCredential}'s javadoc (the design doc's "superset enum" is deferred
 * until enough providers exist to need it) and {@code PlatformToolKey}'s
 * constants-class shape. Tenant-scoped provider credentials keyed by these
 * strings are the BYOK counterpart of the platform-scoped
 * {@code platform_tool_credential} keys - the same vendor name is used in
 * both places (a platform {@code TYPESAFE} key and a tenant's own {@code
 * TYPESAFE} key pay the same upstream account type).
 */
public final class ProviderKeys {

    /** TypeSafe AI - today serves {@link Capability#DECISION} via its Jev/System One model. */
    public static final String TYPESAFE = "TYPESAFE";

    private ProviderKeys() {
    }
}
