package com.agenticai.core.security.edition;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Registers the annotated bean only while the billing edition is on
 * ({@value #PROPERTY}, default {@code true} = today's behaviour for every existing profile and install).
 *
 * <p>Use it on leaf beans that exist purely to SELL or COLLECT: payment webhooks, checkout and invoice
 * controllers, plan-lifecycle schedulers. Never put it on a bean another bean injects: plan and limit
 * ports stay registered in both editions and answer "unlimited" instead (see {@code FreeModeService},
 * which reports itself enabled whenever the edition is off), so every module still wires.
 *
 * <p>The personal profile sets {@code app.edition.billing-enabled=false}. The value is read through the
 * environment's boolean conversion, exactly like the {@code @Value("${app.edition.billing-enabled:true}")}
 * injections that make the plan ports answer "unlimited", so {@code 0}, {@code off} and {@code no} switch
 * both halves off together rather than dropping the beans in one place and leaving billing on in the other.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(BillingEditionCondition.class)
public @interface ConditionalOnBillingEdition {

    /** The single switch for the billing edition. */
    String PROPERTY = "app.edition.billing-enabled";
}
