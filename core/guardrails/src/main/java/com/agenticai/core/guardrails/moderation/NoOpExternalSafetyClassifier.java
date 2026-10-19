package com.agenticai.core.guardrails.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase P6 - the shipped default {@link ExternalSafetyClassifier}: it detects
 * NOTHING and always returns {@link ExternalSafetyVerdict#noMatch()}.
 *
 * <p>This is intentional and load-bearing, not a placeholder to be quietly
 * replaced with real detection logic in this repo. Real CSAM / credible-
 * violence detection is a legal-obligation, specialist-vendor, build-with-
 * counsel undertaking (see {@link ExternalSafetyClassifier}'s javadoc). Until
 * a deployment has done that work and dropped in its own bean, the highest-
 * stakes layer is a well-documented no-op rather than a false sense of
 * coverage.
 *
 * <p><b>The pluggable-seam mechanism</b>: this class is NOT a Spring bean.
 * {@link ModerationService} injects an {@code ObjectProvider<ExternalSafetyClassifier>}
 * and uses a real bean if the deployment has registered exactly one, falling
 * back to a {@code new NoOpExternalSafetyClassifier()} otherwise. That makes
 * the swap fully deterministic (no {@code @ConditionalOnMissingBean} ordering
 * caveats): register a real {@code @Component}/{@code @Bean} implementing
 * {@link ExternalSafetyClassifier} and it wins automatically; register none
 * and this no-op is used.
 */
public class NoOpExternalSafetyClassifier implements ExternalSafetyClassifier {

    private static final Logger log = LoggerFactory.getLogger(NoOpExternalSafetyClassifier.class);

    public NoOpExternalSafetyClassifier() {
        log.info(
            "External safety classifier: NO-OP implementation active. The highest-stakes categories "
                + "(CSAM, credible violence/terrorism) are NOT being detected. This is the shipped default - "
                + "enabling real detection requires a vetted vendor and legal counsel (mandatory reporting "
                + "obligations apply). See ExternalSafetyClassifier's javadoc."
        );
    }

    @Override
    public ExternalSafetyVerdict classify(String content, ModerationContext context) {
        return ExternalSafetyVerdict.noMatch();
    }

    @Override
    public boolean isOperational() {
        return false;
    }
}
