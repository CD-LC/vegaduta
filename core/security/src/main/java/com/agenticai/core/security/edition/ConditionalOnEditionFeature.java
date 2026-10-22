package com.agenticai.core.security.edition;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Registers the annotated bean only while the given {@link EditionFeature} is on for this install. Every
 * profile, shape and test that never sets an edition property keeps every feature, so adding the
 * annotation changes nothing for lite, full, worker and jobs.
 *
 * <p>Put it on the CLASS (the bean-wiring and closure tests discover gated classes reflectively by this
 * annotation, so no table needs editing). A gated bean may be injected only by beans gated by the SAME
 * feature, or through {@code ObjectProvider}, {@code Optional} or a collection; see {@link EditionFeature}.
 * Beans that exist purely to sell (payment webhooks and the like) may keep {@link ConditionalOnBillingEdition}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(EditionFeatureCondition.class)
public @interface ConditionalOnEditionFeature {

    /** The feature that must be on for the annotated bean to exist. */
    EditionFeature value();
}
