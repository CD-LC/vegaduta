package com.agenticai.core.agents.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.directory.AgentDirectoryController;
import com.agenticai.core.agents.directory.AgentDirectoryService;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The agent-to-agent server, client and directory are one edition feature ({@link
 * EditionFeature#A2A_DIRECTORY}): on the personal tier none of their beans exists and the context
 * still starts; on every other tier, and whenever no edition property is set, all of them are
 * registered as before. Registration only evaluates the conditions, so no bean is instantiated and
 * no database or Redis is needed.
 */
class A2aEditionGateTest {

    /** The eight services and the three controllers that inject them (all wired to each other). */
    private static final List<Class<?>> A2A_BEANS =
            List.of(
                    A2aTaskStore.class,
                    A2aTaskService.class,
                    A2aAgentCardService.class,
                    A2aCardVerifier.class,
                    A2aClientService.class,
                    A2aDeliveryPortImpl.class,
                    AgentDirectoryController.class,
                    AgentDirectoryService.class,
                    A2aAgentCardController.class,
                    A2aRestController.class,
                    A2aJsonRpcController.class);

    private static GenericApplicationContext register(Map<String, Object> props) {
        GenericApplicationContext ctx = new GenericApplicationContext();
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        ctx.setEnvironment(env);
        AnnotatedBeanDefinitionReader reader = new AnnotatedBeanDefinitionReader(ctx);
        A2A_BEANS.forEach(reader::register);
        return ctx;
    }

    private static long registered(Map<String, Object> props) {
        try (GenericApplicationContext ctx = register(props)) {
            return A2A_BEANS.stream()
                    .filter(
                            c ->
                                    Arrays.stream(ctx.getBeanDefinitionNames())
                                            .map(ctx::getBeanDefinition)
                                            .anyMatch(
                                                    d -> c.getName().equals(d.getBeanClassName())))
                    .count();
        }
    }

    @Test
    void everyA2aBeanCarriesTheDirectoryFeatureGate() {
        for (Class<?> bean : A2A_BEANS) {
            ConditionalOnEditionFeature gate =
                    bean.getAnnotation(ConditionalOnEditionFeature.class);
            assertThat(gate).as(bean.getSimpleName()).isNotNull();
            assertThat(gate.value())
                    .as(bean.getSimpleName())
                    .isEqualTo(EditionFeature.A2A_DIRECTORY);
        }
    }

    @Test
    void noEditionPropertyKeepsAllBeans() {
        assertThat(registered(Map.of())).isEqualTo(A2A_BEANS.size());
    }

    @Test
    void liteAndFullKeepAllBeans() {
        assertThat(registered(Map.of("app.edition.tier", "lite"))).isEqualTo(A2A_BEANS.size());
        assertThat(registered(Map.of("app.edition.tier", "full"))).isEqualTo(A2A_BEANS.size());
    }

    @Test
    void personalTierRegistersNone() {
        assertThat(registered(Map.of("app.edition.tier", "personal"))).isZero();
    }

    @Test
    void personalWithTheEscapeHatchRegistersAll() {
        assertThat(
                        registered(
                                Map.of(
                                        "app.edition.tier", "personal",
                                        "app.edition.features.a2a-directory.enabled", "true")))
                .isEqualTo(A2A_BEANS.size());
    }

    @Test
    void theContextStartsOnPersonalWithoutAnyA2aBean() {
        new ApplicationContextRunner()
                .withPropertyValues("app.edition.tier=personal")
                .withUserConfiguration(A2A_BEANS.toArray(Class<?>[]::new))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            for (Class<?> bean : A2A_BEANS) {
                                assertThat(context.getBeanNamesForType(bean))
                                        .as(bean.getSimpleName())
                                        .isEmpty();
                            }
                        });
    }
}
