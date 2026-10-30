package com.agenticai.core.rl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.rl.feedback.RlFeedbackProperties;
import com.agenticai.core.rl.policy.RlPolicyProperties;
import com.agenticai.core.rl.reward.RlRewardProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * {@link RlConfiguration} exists only to bind the three {@code rl.*} property classes - there is no
 * application-wide {@code @ConfigurationPropertiesScan}, so if a fourth class is added here (or one
 * drops off) the module silently loses its config.
 */
class RlConfigurationTest {

    @Test
    void theModuleEnablesExactlyItsThreePropertyClasses() {
        EnableConfigurationProperties annotation =
                RlConfiguration.class.getAnnotation(EnableConfigurationProperties.class);

        assertEquals(
                List.of(
                        RlPolicyProperties.class,
                        RlRewardProperties.class,
                        RlFeedbackProperties.class),
                List.of(annotation.value()));
    }

    @Test
    void theConfigurationClassItselfInstantiates() {
        // A @Configuration class with no constructor logic; this pins it stays trivially
        // constructable for component-scan registration.
        assertTrue(new RlConfiguration() != null);
    }
}
