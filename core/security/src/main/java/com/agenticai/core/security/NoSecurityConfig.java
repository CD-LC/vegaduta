package com.agenticai.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The permissive, everything-open filter chain - dev/test ONLY, and since the
 * 2026-07-29 security-audit fix it must be EXPLICITLY opted into with the
 * {@code insecure} Spring profile. It is no longer the default for "no
 * profile set".
 *
 * <h2>Why the opt-in was inverted (audit finding, 2026-07-29)</h2>
 * <p>This class used to be {@code @Profile("!security")}: any run that did
 * not spell the {@code security} profile exactly right got EVERY endpoint
 * {@code permitAll()}, silently. One typo in a compose file's
 * {@code SPRING_PROFILES_ACTIVE} ("securty,knowledge,...") would have opened
 * the entire production API with no warning of any kind - the classic
 * fail-open-by-default trap. The gate is now inverted so absence of intent
 * fails CLOSED:
 * <ul>
 *   <li>{@code insecure} active -> this permissive chain (plus the loud WARN
 *       and the production-marker guard below).</li>
 *   <li>{@code insecure} NOT active -> {@link SecurityConfig}'s real chain
 *       ({@code @Profile("!insecure")}). If the {@code security} profile is
 *       also missing (e.g. the typo scenario), {@link SecurityConfig} still
 *       registers but application-security.yml never loads, so its required
 *       {@code issuer-uri}/{@code jwk-set-uri} placeholders cannot resolve
 *       and the app REFUSES TO BOOT with an unresolved-placeholder error -
 *       a loud startup failure instead of a silently open API.</li>
 * </ul>
 *
 * <h2>Why a permissive chain has to exist at all</h2>
 * <p>{@code spring-boot-starter-security} being on the classpath makes Spring
 * Boot's {@code SecurityAutoConfiguration} activate regardless of Spring
 * profile - it only checks whether any {@link SecurityFilterChain} bean
 * already exists in the context. If none does, it registers its own default
 * chain that requires HTTP Basic auth with a randomly-generated password on
 * EVERY endpoint (logged once at startup), which would silently break every
 * unauthenticated Stage 1-6 / Phase 0 Stage 1-2 flow - a strictly worse trap
 * than an honest boot failure, since it looks like a real production security
 * posture while actually just being an unusable accident. {@code insecure}
 * and {@code !insecure} are mutually exclusive, so exactly one of the two
 * chains is ever registered and the auto-configured Basic chain never
 * appears.
 */
@Configuration
@Profile("insecure")
public class NoSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(NoSecurityConfig.class);

    @Bean
    public SecurityFilterChain permissiveFilterChain(HttpSecurity http, Environment environment) throws Exception {
        failFastIfProductionMarkerPresent(environment);
        // Deliberately WARN, not info: this line existing in a log is the
        // one trace that an environment is running with zero authentication.
        // If you are reading this in a production log, treat it as an
        // incident, not a curiosity.
        log.warn("##############################################################################");
        log.warn("## INSECURE MODE: the 'insecure' Spring profile is active.                  ##");
        log.warn("## EVERY endpoint is permitAll() - no authentication, no roles, no tenants  ##");
        log.warn("## are enforced. This is only acceptable for local dev and CI smoke tests.  ##");
        log.warn("##############################################################################");
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Startup guard (audit finding #1, 2026-07-29): refuse to build the
     * permissive chain at all when the active profiles carry any marker that
     * says "this environment intended real security". Two markers:
     * <ul>
     *   <li>{@code security} - the operator asked for the real Keycloak
     *       resource-server posture AND for no security at once. That is a
     *       contradiction, almost certainly a copy-paste error in
     *       {@code SPRING_PROFILES_ACTIVE}, and the only safe resolution is
     *       to stop the boot rather than guess which one was meant.</li>
     *   <li>{@code prod}/{@code production} - no such profile exists in this
     *       repo today (deployment intent travels through the {@code security}
     *       profile instead), but the guard covers it anyway so a future
     *       conventionally-named production profile can never coexist with
     *       the open chain either.</li>
     * </ul>
     * Throwing here aborts context startup - fail fast, matching
     * {@code EncryptionService}'s posture on a missing APP_ENCRYPTION_KEY.
     */
    static void failFastIfProductionMarkerPresent(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("security".equalsIgnoreCase(profile)) {
                throw new IllegalStateException(
                    "Refusing to start: both the 'insecure' (permitAll) and 'security' (Keycloak "
                        + "resource server) Spring profiles are active at once. These are contradictory - "
                        + "remove 'insecure' for any real deployment, or remove 'security' if this is "
                        + "genuinely a throwaway local/CI run. See NoSecurityConfig's javadoc.");
            }
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                throw new IllegalStateException(
                    "Refusing to start: the 'insecure' (permitAll) Spring profile is active alongside "
                        + "the production marker profile '" + profile + "'. The permissive chain must "
                        + "never run in a production environment. See NoSecurityConfig's javadoc.");
            }
        }
    }
}
