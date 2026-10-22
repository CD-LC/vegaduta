package com.agenticai.core.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.mcpserver.expose.McpExposureConfig;
import com.agenticai.core.mcpserver.expose.McpToolExposureController;
import com.agenticai.core.mcpserver.expose.McpToolExposureService;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.ArrayList;
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
 * Edition gating of the inbound MCP server surface: the developer API and the exposure controller/service
 * follow {@link EditionFeature#MCP_SERVER_EXPOSURE}; the grants controller also stays while the
 * OpenAI-compatible API is on, because its keys are minted through the grants. Registration only evaluates
 * the conditions, so no bean is instantiated.
 */
class McpServerEditionGateTest {

    private static final List<Class<?>> EXPOSURE_BEANS =
            List.of(
                    DeveloperApiController.class,
                    McpToolExposureController.class,
                    McpToolExposureService.class);

    private static GenericApplicationContext register(Map<String, Object> props, Class<?>... classes) {
        GenericApplicationContext ctx = new GenericApplicationContext();
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        ctx.setEnvironment(env);
        AnnotatedBeanDefinitionReader reader = new AnnotatedBeanDefinitionReader(ctx);
        for (Class<?> c : classes) {
            reader.register(c);
        }
        return ctx;
    }

    private static boolean registered(Class<?> bean, Map<String, Object> props) {
        try (GenericApplicationContext ctx = register(props, bean)) {
            return Arrays.stream(ctx.getBeanDefinitionNames())
                    .map(ctx::getBeanDefinition)
                    .anyMatch(d -> bean.getName().equals(d.getBeanClassName()));
        }
    }

    @Test
    void exposureBeansCarryTheMcpFeatureGate() {
        // McpExposureConfig is also profile- and property-gated, so only its edition gate is asserted: it
        // injects McpToolExposureService, so it must follow the same feature (EditionClosureRulesTest).
        List<Class<?>> gated = new ArrayList<>(EXPOSURE_BEANS);
        gated.add(McpExposureConfig.class);
        for (Class<?> bean : gated) {
            ConditionalOnEditionFeature gate = bean.getAnnotation(ConditionalOnEditionFeature.class);
            assertThat(gate).as(bean.getSimpleName()).isNotNull();
            assertThat(gate.value()).as(bean.getSimpleName()).isEqualTo(EditionFeature.MCP_SERVER_EXPOSURE);
        }
    }

    @Test
    void exposureBeansExistWithoutAnEditionProperty() {
        for (Class<?> bean : EXPOSURE_BEANS) {
            assertThat(registered(bean, Map.of())).as(bean.getSimpleName()).isTrue();
            assertThat(registered(bean, Map.of("app.edition.tier", "lite"))).as(bean.getSimpleName()).isTrue();
        }
    }

    @Test
    void exposureBeansAreAbsentOnPersonalEvenWithOpenAiCompat() {
        Map<String, Object> personal = Map.of("app.edition.tier", "personal");
        Map<String, Object> personalCompat =
                Map.of("app.edition.tier", "personal", "app.openai-compat.enabled", "true");
        for (Class<?> bean : EXPOSURE_BEANS) {
            assertThat(registered(bean, personal)).as(bean.getSimpleName()).isFalse();
            assertThat(registered(bean, personalCompat)).as(bean.getSimpleName()).isFalse();
        }
    }

    @Test
    void exposureBeansReturnWithTheEscapeHatch() {
        Map<String, Object> props =
                Map.of("app.edition.tier", "personal", "app.edition.features.mcp-server-exposure.enabled", "true");
        for (Class<?> bean : EXPOSURE_BEANS) {
            assertThat(registered(bean, props)).as(bean.getSimpleName()).isTrue();
        }
    }

    @Test
    void grantsControllerFollowsTheSurfaceCondition() {
        Class<?> grants = McpServerAccessController.class;
        assertThat(registered(grants, Map.of())).isTrue();
        assertThat(registered(grants, Map.of("app.edition.tier", "lite"))).isTrue();
        assertThat(registered(grants, Map.of("app.edition.tier", "full"))).isTrue();
        assertThat(registered(grants, Map.of("app.edition.tier", "personal"))).isFalse();
        assertThat(registered(grants, Map.of("app.edition.tier", "personal", "app.openai-compat.enabled", "false")))
                .isFalse();
        assertThat(registered(grants, Map.of("app.edition.tier", "personal", "app.openai-compat.enabled", "true")))
                .isTrue();
        assertThat(
                        registered(
                                grants,
                                Map.of(
                                        "app.edition.tier", "personal",
                                        "app.edition.features.mcp-server-exposure.enabled", "true")))
                .isTrue();
    }

    @Test
    void theContextStartsOnPersonalWithoutTheGatedSurface() {
        new ApplicationContextRunner()
                .withPropertyValues("app.edition.tier=personal")
                .withUserConfiguration(
                        DeveloperApiController.class,
                        McpToolExposureController.class,
                        McpToolExposureService.class,
                        McpServerAccessController.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeanNamesForType(DeveloperApiController.class)).isEmpty();
                            assertThat(context.getBeanNamesForType(McpToolExposureController.class)).isEmpty();
                            assertThat(context.getBeanNamesForType(McpToolExposureService.class)).isEmpty();
                            assertThat(context.getBeanNamesForType(McpServerAccessController.class)).isEmpty();
                        });
    }
}
