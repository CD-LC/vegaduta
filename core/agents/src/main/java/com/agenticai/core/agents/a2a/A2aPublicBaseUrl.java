package com.agenticai.core.agents.a2a;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The public base URL this server advertises in Agent Cards and capability manifests: {@code
 * app.a2a.public-base-url} when set, otherwise derived from the current request (which honours
 * {@code X-Forwarded-*} through {@code server.forward-headers-strategy=native}). No trailing
 * slash either way.
 *
 * <p>Stays in {@code core/agents} (ADR-0023) because the delegation controller in {@code
 * agents.trust} needs it, while the Agent Card service that also uses it moved to {@code
 * core/agents-interop}.
 */
public final class A2aPublicBaseUrl {

    private A2aPublicBaseUrl() {}

    public static String resolve(A2aProperties properties, HttpServletRequest request) {
        String configured = properties.getPublicBaseUrl();
        if (StringUtils.hasText(configured)) {
            return configured.replaceAll("/+$", "");
        }
        return ServletUriComponentsBuilder.fromContextPath(request)
                .build()
                .toUriString()
                .replaceAll("/+$", "");
    }
}
