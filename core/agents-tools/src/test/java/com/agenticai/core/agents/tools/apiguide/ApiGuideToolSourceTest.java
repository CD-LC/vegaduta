package com.agenticai.core.agents.tools.apiguide;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agentspi.ApiSpecProvider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The jobs and worker shapes scan no REST controllers, and the only {@link ApiSpecProvider} is one,
 * so the tool source has to start without a provider (it once failed the whole context there).
 */
class ApiGuideToolSourceTest {

    private static StaticListableBeanFactory beans(ApiSpecProvider provider) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (provider != null) {
            factory.addBean("apiSpecController", provider);
        }
        return factory;
    }

    @Test
    void offersTheGuideWhenTheApiSpecIsPresent() {
        ApiGuideToolSource source =
                new ApiGuideToolSource(
                        beans(() -> "{}").getBeanProvider(ApiSpecProvider.class), true);

        assertThat(source.rawTools()).hasSize(1);
    }

    @Test
    void offersNothingWithoutAnApiSpecInsteadOfFailingTheStart() {
        ApiGuideToolSource source =
                new ApiGuideToolSource(beans(null).getBeanProvider(ApiSpecProvider.class), true);

        assertThat(source.rawTools()).isEmpty();
    }

    @Test
    void offersNothingWhenSwitchedOff() {
        ApiGuideToolSource source =
                new ApiGuideToolSource(
                        beans(() -> "{}").getBeanProvider(ApiSpecProvider.class), false);

        assertThat(source.rawTools()).isEmpty();
    }
}
