package com.agenticai.core.security.edition;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Backs {@link ConditionalOnBillingEdition}: true unless the property converts to boolean {@code false}. */
final class BillingEditionCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Boolean.TRUE.equals(context.getEnvironment()
            .getProperty(ConditionalOnBillingEdition.PROPERTY, Boolean.class, Boolean.TRUE));
    }
}
