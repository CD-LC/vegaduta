package com.agenticai.core.common.systemstatus;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.platform.reliability.DependencyHealthService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.mock.env.MockEnvironment;

/**
 * The three health surfaces read the sidecar URL through a SpEL-wrapped {@code @Value}: with
 * {@code app.sidecar.enabled=false} they must see "no URL" (NOT_CONFIGURED / UNKNOWN) instead of probing a
 * sidecar that is deliberately absent and reporting DOWN; every other setting keeps today's URL.
 */
class SidecarEnabledValueExpressionTest {

    private static final String URL = "http://sidecar:8001";

    private static String resolve(String expression, MockEnvironment env) {
        String placeholdersResolved = env.resolvePlaceholders(expression);
        return new SpelExpressionParser()
            .parseExpression(placeholdersResolved, new TemplateParserContext())
            .getValue(String.class);
    }

    private static List<String> sidecarBaseUrlExpressions() {
        return List.of(PublicStatusController.class, SystemStatusController.class, DependencyHealthService.class)
            .stream()
            .map(type -> {
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    for (Parameter parameter : constructor.getParameters()) {
                        Value value = parameter.getAnnotation(Value.class);
                        if (value != null && value.value().contains("app.sidecar.base-url")) {
                            return value.value();
                        }
                    }
                }
                throw new AssertionError("no sidecar base-url @Value on " + type);
            })
            .toList();
    }

    @Test
    void theUrlIsHiddenOnlyWhenTheSidecarIsExplicitlyDisabled() {
        for (String expression : sidecarBaseUrlExpressions()) {
            assertThat(resolve(expression, new MockEnvironment().withProperty("app.sidecar.base-url", URL)))
                .as("default (enabled unset)").isEqualTo(URL);
            assertThat(resolve(expression, new MockEnvironment()
                .withProperty("app.sidecar.base-url", URL).withProperty("app.sidecar.enabled", "true")))
                .isEqualTo(URL);
            assertThat(resolve(expression, new MockEnvironment()
                .withProperty("app.sidecar.base-url", URL).withProperty("app.sidecar.enabled", "")))
                .as("empty env var must not break boot").isEqualTo(URL);
            assertThat(resolve(expression, new MockEnvironment()
                .withProperty("app.sidecar.base-url", URL).withProperty("app.sidecar.enabled", "false")))
                .isEmpty();
            assertThat(resolve(expression, new MockEnvironment()))
                .as("nothing configured").isEmpty();
        }
    }
}
