package com.agenticai.core.rl;

import com.agenticai.core.rl.feedback.RlFeedbackProperties;
import com.agenticai.core.rl.policy.RlPolicyProperties;
import com.agenticai.core.rl.reward.RlRewardProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code rl.policy.*}, {@code rl.reward.*} and {@code rl.feedback.*} into their
 * {@link RlPolicyProperties}/{@link RlRewardProperties}/{@link RlFeedbackProperties} beans. Same
 * {@code @EnableConfigurationProperties} pattern the rest of this codebase
 * uses (see {@code CodeIntelConfiguration}, {@code McpRegistryConfiguration}) -
 * there is no application-wide {@code @ConfigurationPropertiesScan}, so each
 * module enables its own.
 */
@Configuration
@EnableConfigurationProperties({RlPolicyProperties.class, RlRewardProperties.class, RlFeedbackProperties.class})
public class RlConfiguration {
}
