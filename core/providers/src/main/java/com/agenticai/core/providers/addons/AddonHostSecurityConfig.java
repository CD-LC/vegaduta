package com.agenticai.core.providers.addons;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A dedicated chain for {@code /api/addons/host/**}, ordered ahead of the main chain: the same fix
 * as the Alertmanager and MCP chains in {@code SecurityConfig}. The installer authenticates with a
 * shared secret in {@code Authorization: Bearer ...}; on the main chain the resource server would
 * try to decode that as a JWT and answer 401 "malformed token" before any controller code runs.
 *
 * <p>This chain is not "open": it omits the JWT filter so the header reaches the controller, and
 * {@link AddonController} then calls {@link AddonHostAuth#require} on every handler, which fails
 * closed on a missing, wrong or unconfigured token. It touches nothing else: the person-facing
 * {@code /api/addons} paths stay on the main chain with their roles.
 */
@Configuration
@Profile("!insecure")
@ConditionalOnClass(HttpSecurity.class)
public class AddonHostSecurityConfig {

    /** Ant pattern of the installer-only surface. */
    public static final String HOST_PATTERN = "/api/addons/host/**";

    @Bean
    @Order(1)
    public SecurityFilterChain addonHostFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(HOST_PATTERN)
                .csrf(csrf -> csrf.disable()) // stateless shared-secret API, no cookies
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
