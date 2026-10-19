package com.agenticai.core.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JPA entity whose rows feed a {@link HotpathLookups#tenant} answer, such as "is this
 * credential active". A write to a marked entity on this pod drops the tenant's cached config
 * answers, so the next read sees it. Each module marks its own entities; the code that evicts needs
 * no dependency on any of them, which keeps vendor adapters removable (ADR-0011).
 *
 * <p>When you add a {@code HotpathLookups.tenant(...)} lookup, mark every entity its query reads.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TenantConfigEntity {}
