package com.agenticai.core.security.edition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.env.PropertyResolver;

/**
 * What this install runs as, for the public status endpoint and the web console: the tier, whether billing
 * is on, how users sign in, and the on/off state of every {@link EditionFeature} (keyed by its kebab-case
 * {@link EditionFeature#id()}). Computed from the environment with the same code the bean conditions use,
 * so it cannot disagree with what was actually registered.
 *
 * @param tier {@code personal}, {@code lite} or {@code full}
 * @param billingEnabled the {@code billing-payments} feature
 * @param authMode {@code local} or {@code keycloak}
 * @param features feature id to on/off
 */
public record EditionInfo(String tier, boolean billingEnabled, String authMode, Map<String, Boolean> features) {

    /** The auth mode property; absent or blank means the default Keycloak wiring. */
    static final String AUTH_MODE_PROPERTY = "app.auth.mode";

    public EditionInfo {
        features = Collections.unmodifiableMap(new LinkedHashMap<>(features));
    }

    /** Reads the edition from the environment. */
    public static EditionInfo from(PropertyResolver environment) {
        Map<String, Boolean> features = new LinkedHashMap<>();
        for (EditionFeature feature : EditionFeature.values()) {
            features.put(feature.id(), feature.isEnabled(environment));
        }
        String authMode = environment.getProperty(AUTH_MODE_PROPERTY);
        authMode = authMode == null || authMode.isBlank() ? "keycloak" : authMode.trim().toLowerCase(Locale.ROOT);
        return new EditionInfo(
                EditionTier.of(environment).id(),
                EditionFeature.BILLING_PAYMENTS.isEnabled(environment),
                authMode,
                features);
    }
}
