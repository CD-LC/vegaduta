package com.agenticai.core.security.http;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a REST handler (or every handler of a controller) as deprecated under the API deprecation
 * policy (docs/api/DEPRECATION.md). At runtime {@code ApiDeprecationInterceptor} adds the {@code
 * Deprecation} (RFC 9745), {@code Sunset} (RFC 8594) and {@code Link} headers to every response
 * of the handler; the OpenAPI generator marks the operation {@code deprecated: true} with {@code
 * x-sunset} and {@code x-replacement}.
 *
 * <p>Dates are ISO-8601 calendar dates ({@code 2027-04-30}), interpreted as 00:00 UTC. The sunset
 * must be at least the policy's notice period after {@code since}; {@code ApiDeprecationPolicyTest}
 * checks every use.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ApiDeprecated {

    /** The date the deprecation was announced (ISO-8601 date). */
    String since();

    /** The date after which the route may be removed (ISO-8601 date), or empty if not yet set. */
    String sunset() default "";

    /** The route clients should move to, e.g. {@code GET /api/agents/{agentId}/versions}. */
    String replacement() default "";

    /** Why the route is going away, shown in the API reference. */
    String reason() default "";
}
