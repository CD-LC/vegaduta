package com.agenticai.core.security.localauth;

import java.util.Locale;
import org.springframework.core.env.Environment;

/**
 * The {@code app.auth.mode} switch. {@code keycloak} (the default, and the value for any property
 * that is absent) is the historical behaviour: tokens come from an external OIDC provider and
 * nothing in this package is wired. {@code local} makes core issue and validate its own tokens for
 * a single owner, with no Keycloak. Anything else is a configuration error and stops the boot, so
 * a typo can never silently select the other mode.
 */
public final class LocalAuth {

    public static final String PROPERTY = "app.auth.mode";
    public static final String KEYCLOAK = "keycloak";
    public static final String LOCAL = "local";

    private LocalAuth() {}

    /** The normalised mode; {@code keycloak} when unset or blank. Throws on an unknown value. */
    public static String mode(Environment env) {
        String raw = env.getProperty(PROPERTY, KEYCLOAK);
        String mode = raw == null || raw.isBlank() ? KEYCLOAK : raw.trim().toLowerCase(Locale.ROOT);
        if (!KEYCLOAK.equals(mode) && !LOCAL.equals(mode)) {
            throw new IllegalStateException(
                    PROPERTY + " must be 'keycloak' or 'local', but is '" + raw + "'");
        }
        return mode;
    }

    public static boolean isLocal(Environment env) {
        return LOCAL.equals(mode(env));
    }
}
