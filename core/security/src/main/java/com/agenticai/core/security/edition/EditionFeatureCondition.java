package com.agenticai.core.security.edition;

import java.util.Map;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Backs {@link ConditionalOnEditionFeature}: true while the named {@link EditionFeature} is
 * enabled.
 */
public final class EditionFeatureCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes =
                metadata.getAnnotationAttributes(ConditionalOnEditionFeature.class.getName());
        if (attributes == null) {
            return true;
        }
        EditionFeature feature = (EditionFeature) attributes.get("value");
        return feature.isEnabled(context.getEnvironment());
    }
}
