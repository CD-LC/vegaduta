package com.agenticai.core.providers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Applies {@code app.providers.small-model-max-billions} to {@link PlatformHostedLlmProvider#effectiveSmallModel()}: a
 * pool row whose model has this many billions of parameters or fewer runs in small-model mode (few tools, compact
 * directives, fast depth) unless the row's own "Small-model mode" control says otherwise. The value lives on the
 * entity because the decision is made there, from the row alone.
 */
@Configuration
class SmallModelThresholdConfiguration {

    SmallModelThresholdConfiguration(
            @Value("${app.providers.small-model-max-billions:" + PlatformHostedLlmProvider.SMALL_MODEL_MAX_BILLIONS + "}")
                    double maxBillions) {
        PlatformHostedLlmProvider.useSmallModelMaxBillions(maxBillions);
    }
}
