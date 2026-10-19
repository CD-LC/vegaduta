package com.agenticai.core.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JPA entity whose rows feed a {@link HotpathLookups#platform} answer, such as "does the
 * hosted pool hold an enabled Google row". Platform rows are the same for every tenant, so a write
 * to a marked entity on this pod drops every cached platform config answer. The platform twin of
 * {@link TenantConfigEntity}; the code that evicts needs no dependency on the entities.
 *
 * <p>When you add a {@code HotpathLookups.platform(...)} lookup that reads an entity, mark it.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PlatformConfigEntity {}
