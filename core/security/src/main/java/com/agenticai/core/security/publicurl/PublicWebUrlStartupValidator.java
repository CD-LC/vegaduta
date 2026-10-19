package com.agenticai.core.security.publicurl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard for {@code app.web.public-url} (AGENTS.md rule 4).
 *
 * <p>The property used to default to {@code http://localhost:3000} in nine separate {@code @Value}
 * placeholders, so a deployment that forgot {@code APP_WEB_PUBLIC_URL} silently mailed {@code
 * localhost} share links and bounced every OAuth callback to the operator's own machine. It is now
 * defined once (application.yml, empty default) and checked here, in every core shape (api, jobs,
 * worker all component-scan this package):
 *
 * <ul>
 *   <li>{@code security} profile active and the value blank: start-up fails with a message naming
 *       the variable. Production always runs with {@code security}, and Compose/Helm always pass
 *       the variable, so this only ever fires on a hand-rolled environment that is wrong.
 *   <li>otherwise (e.g. {@code insecure} local runs) a blank value is logged as a WARNING; the
 *       classes that build browser links then refuse at the point of use with the same message
 *       ({@link PublicWebUrl#require}) rather than emitting a {@code localhost} link.
 * </ul>
 *
 * <p>A {@code localhost} / loopback value is accepted (single-machine Compose installs are
 * legitimate) but logged as a warning, since users on any other machine cannot follow such links.
 */
@Component
public class PublicWebUrlStartupValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(PublicWebUrlStartupValidator.class);

    private final Environment environment;
    private final String appPublicUrl;

    public PublicWebUrlStartupValidator(
            Environment environment, @Value("${app.web.public-url:}") String appPublicUrl) {
        this.environment = environment;
        this.appPublicUrl = appPublicUrl;
    }

    @Override
    public void afterPropertiesSet() {
        validate(
                environment.acceptsProfiles(Profiles.of("security")),
                appPublicUrl,
                environment.acceptsProfiles(Profiles.of("personal")));
    }

    /**
     * The check itself, separated from Spring wiring so it is unit-testable.
     *
     * @throws IllegalStateException when {@code securityProfileActive} and the value is blank
     */
    static void validate(boolean securityProfileActive, String appPublicUrl) {
        validate(securityProfileActive, appPublicUrl, false);
    }

    /**
     * @param personalProfileActive one person on one machine: a localhost origin is the correct setting there,
     *     not a misconfiguration, so it is reported at INFO instead of WARN.
     */
    static void validate(boolean securityProfileActive, String appPublicUrl, boolean personalProfileActive) {
        if (PublicWebUrl.isBlank(appPublicUrl)) {
            String message =
                    PublicWebUrl.PROPERTY
                            + " is not set: export "
                            + PublicWebUrl.ENV_HINT
                            + " to the public origin of the tenant console (e.g."
                            + " https://app.example.com). There is deliberately no localhost"
                            + " default (AGENTS.md rule 4).";
            if (securityProfileActive) {
                throw new IllegalStateException(
                        "Refusing to start under the 'security' profile: " + message);
            }
            log.warn(
                    "{} Share links, e-mail links and OAuth console redirects will fail until it"
                            + " is set.",
                    message);
            return;
        }
        String lower = appPublicUrl.trim().toLowerCase();
        if (lower.contains("//localhost")
                || lower.contains("//127.0.0.1")
                || lower.contains("//[::1]")) {
            if (personalProfileActive) {
                log.info(
                        "{} is {} - fine for a personal install: only a browser on this machine follows the links"
                                + " core generates.",
                        PublicWebUrl.PROPERTY,
                        appPublicUrl);
                return;
            }
            log.warn(
                    "{} is {} - only a browser on this same machine can follow the links core"
                        + " generates. Set {} to a public origin for any multi-machine install.",
                    PublicWebUrl.PROPERTY,
                    appPublicUrl,
                    PublicWebUrl.ENV_HINT);
        }
    }
}
