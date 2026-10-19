package com.agenticai.core.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class StartupWarmupEnvironmentPostProcessorTest {

    private final StartupWarmupEnvironmentPostProcessor processor = new StartupWarmupEnvironmentPostProcessor();

    private static StandardEnvironment environmentWith(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }

    @Test
    void doesNothingWhenTheFlagIsUnset() {
        StandardEnvironment environment = environmentWith(Map.of());

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.kafka.admin.auto-create")).isNull();
    }

    @Test
    void doesNothingWhenTheFlagIsFalse() {
        StandardEnvironment environment = environmentWith(Map.of("app.startup.background-warmup.enabled", "false"));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.kafka.admin.auto-create")).isNull();
    }

    @Test
    void switchesOffKafkaAdminTopicAutoCreateWhenTheFlagIsOn() {
        StandardEnvironment environment = environmentWith(Map.of("app.startup.background-warmup.enabled", "true"));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.kafka.admin.auto-create")).isEqualTo("false");
    }

    @Test
    void anOperatorsExplicitValueStillWins() {
        StandardEnvironment environment = environmentWith(Map.of(
            "app.startup.background-warmup.enabled", "true",
            "spring.kafka.admin.auto-create", "true"));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.kafka.admin.auto-create")).isEqualTo("true");
    }
}
