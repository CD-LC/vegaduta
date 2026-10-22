package com.agenticai.core;

import java.util.Locale;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Normalises {@code app.auth.mode} before any bean or {@code @ConditionalOnProperty} is evaluated.
 *
 * <p>The mode switch is read in many places, and Spring's {@code @ConditionalOnProperty} treats a
 * property that is present but blank (what a Compose line such as {@code
 * APP_AUTH_MODE: ${APP_AUTH_MODE:-}} produces) or padded with whitespace as a value that is neither
 * {@code keycloak} nor {@code local}: the default Keycloak wiring would silently vanish and the boot
 * would die with an unrelated "no JwtDecoder" error. Here a blank value is made to mean what an
 * absent one means ({@code keycloak}), and a padded or upper-case value is trimmed and lower-cased.
 * Anything else is left alone, so a genuine typo still reaches {@code AuthModeValidator}, which
 * names the property and stops the boot.
 *
 * <p>Absent property: untouched, so a default install is unaffected.
 */
public class AuthModeEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY = "app.auth.mode";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty(PROPERTY);
        if (raw == null) {
            return;
        }
        String normalised = raw.isBlank() ? "keycloak" : raw.trim().toLowerCase(Locale.ROOT);
        if (!normalised.equals(raw)) {
            environment.getPropertySources().addFirst(new MapPropertySource("authModeNormalised", Map.of(PROPERTY, normalised)));
        }
    }
}
