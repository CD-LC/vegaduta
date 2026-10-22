package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

/**
 * The environment variables the Helm chart and Compose set for managed Redis-protocol and Kafka
 * services must land in the Spring properties the auto-configured clients read. This loads the real
 * {@code application.yml} and {@code application-workflow.yml} on top of a fake process environment
 * and binds {@link DataRedisProperties} / {@link KafkaProperties} exactly as Spring Boot does, so a
 * renamed placeholder or a wrong env-variable spelling fails here instead of on a managed instance.
 */
class ManagedServiceEnvBindingTest {

    private static Binder binder(Map<String, Object> env) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        // Boot applies its env-variable name mapping only to a source with the standard name.
        environment
                .getPropertySources()
                .addLast(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String file : new String[] {"application.yml", "application-workflow.yml"}) {
            for (PropertySource<?> ps : loader.load(file, new ClassPathResource(file))) {
                environment.getPropertySources().addLast(ps);
            }
        }
        ConfigurationPropertySources.attach(environment);
        return Binder.get(environment);
    }

    @Test
    void redisDefaultsStayPlainWithTheDefaultUser() throws IOException {
        DataRedisProperties redis =
                binder(Map.of()).bind("spring.data.redis", DataRedisProperties.class).get();
        assertThat(redis.getSsl().isEnabled()).isFalse();
        assertThat(redis.getUsername()).isNullOrEmpty();
    }

    @Test
    void redisTlsAndUsernameComeFromTheChartVariables() throws IOException {
        DataRedisProperties redis =
                binder(
                                Map.of(
                                        "REDIS_HOST", "cache.example.com",
                                        "REDIS_PORT", "6380",
                                        "REDIS_SSL", "true",
                                        "REDIS_USERNAME", "app",
                                        "REDIS_PASSWORD", "pw"))
                        .bind("spring.data.redis", DataRedisProperties.class)
                        .get();
        assertThat(redis.getHost()).isEqualTo("cache.example.com");
        assertThat(redis.getPort()).isEqualTo(6380);
        assertThat(redis.getSsl().isEnabled()).isTrue();
        assertThat(redis.getUsername()).isEqualTo("app");
        assertThat(redis.getPassword()).isEqualTo("pw");
    }

    @Test
    void kafkaSaslSettingsBindFromTheChartVariables() throws IOException {
        String jaas =
                "org.apache.kafka.common.security.plain.PlainLoginModule required "
                        + "username=\"$ConnectionString\" password=\"secret\";";
        KafkaProperties kafka =
                binder(
                                Map.of(
                                        "WORKFLOW_KAFKA_BOOTSTRAP_SERVERS",
                                                "ns.servicebus.windows.net:9093",
                                        "SPRING_KAFKA_SECURITY_PROTOCOL", "SASL_SSL",
                                        "SPRING_KAFKA_PROPERTIES_SASL_MECHANISM", "PLAIN",
                                        "SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG", jaas))
                        .bind("spring.kafka", KafkaProperties.class)
                        .get();
        assertThat(kafka.getBootstrapServers()).containsExactly("ns.servicebus.windows.net:9093");
        Map<String, Object> admin = kafka.buildAdminProperties();
        assertThat(admin).containsEntry("security.protocol", "SASL_SSL");
        assertThat(admin).containsEntry("sasl.mechanism", "PLAIN");
        assertThat(admin).containsEntry("sasl.jaas.config", jaas);
        // The producer and consumer the workflow outbox relay and logger use get the same settings.
        assertThat(kafka.buildProducerProperties()).containsEntry("security.protocol", "SASL_SSL");
        assertThat(kafka.buildConsumerProperties()).containsEntry("sasl.mechanism", "PLAIN");
    }

    @Test
    void kafkaStaysPlaintextWhenNothingIsSet() throws IOException {
        KafkaProperties kafka = binder(Map.of()).bind("spring.kafka", KafkaProperties.class).get();
        assertThat(kafka.buildAdminProperties()).doesNotContainKey("security.protocol");
        assertThat(kafka.buildAdminProperties()).doesNotContainKey("sasl.mechanism");
    }
}
