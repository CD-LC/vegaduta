package com.agenticai.core.mcpserver;

import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Decides whether the tenant-admin grants surface ({@link McpServerAccessController}, {@code
 * /api/mcpserver/grants}) exists. It does on every edition that carries {@link
 * EditionFeature#MCP_SERVER_EXPOSURE}, and on a lean one too when the OpenAI-compatible API is
 * switched on ({@code app.openai-compat.enabled=true}), because the keys that API accepts are
 * minted through these grants.
 *
 * <p>Absent any edition property the feature is on, so lite, full, worker, jobs and every existing
 * test keep the controller. The grant service and the rate limiter are not gated: they back the
 * OpenAI-compatible key filter and stay available either way.
 */
final class McpServerSurfaceCondition implements Condition {

    static final String OPENAI_COMPAT_PROPERTY = "app.openai-compat.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        return EditionFeature.MCP_SERVER_EXPOSURE.isEnabled(environment)
                || Boolean.TRUE.equals(
                        environment.getProperty(
                                OPENAI_COMPAT_PROPERTY, Boolean.class, Boolean.FALSE));
    }
}
