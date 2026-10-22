package com.agenticai.core.imagegen.demo;

import com.agenticai.core.imagegen.GeminiImageGenerationClient;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.demo.DemoImageAvailability;
import org.springframework.stereotype.Component;

/**
 * The {@link DemoImageAvailability} implementation for the demo image
 * surface - lives here because this module is the one that can see both the
 * tenancy SPI and the platform-pool credential resolver (the module-graph
 * split {@link DemoImageController}'s class javadoc documents).
 *
 * <p>Answers the ONE question tenancy needs for the session response's
 * {@code imageEnabled} flag and the controller's pre-flight: is there an
 * enabled Google row in the platform pool at all. It deliberately delegates
 * to {@link GeminiImageGenerationClient#isAvailable()} rather than
 * duplicating the pool lookup, so "configured" means the same thing in both
 * places.
 */
@ConditionalOnEditionFeature(EditionFeature.VIDEO_DEMO_MEDIA)
@Component
public class DemoImagePoolAvailability implements DemoImageAvailability {

    private final GeminiImageGenerationClient imageClient;

    public DemoImagePoolAvailability(GeminiImageGenerationClient imageClient) {
        this.imageClient = imageClient;
    }

    @Override
    public boolean available() {
        return imageClient.isAvailable();
    }
}
