package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.tenancy.RedisTenantDataPurgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link RedisTenantDataPurgeService} against a real Redis-protocol server (Valkey) through the
 * Lettuce client: the single-node path of the cursor-based scan. Redis Cluster is NOT covered here
 * (no cluster is started); that path is unit-tested with mocks only.
 *
 * <p>Image override: {@code vegaduta.test.redis.image}.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class RedisTenantDataPurgeIT {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RedisTenantDataPurgeService service;

    @BeforeAll
    static void start() {
        GenericContainer<?> container = IntegrationTestContainers.redis();
        factory =
                new LettuceConnectionFactory(
                        new RedisStandaloneConfiguration(
                                container.getHost(), container.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        service = new RedisTenantDataPurgeService(redis, new ObjectMapper());
    }

    @AfterAll
    static void stop() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void purgesOnlyTheTenantsKeysAcrossManyScanPages() {
        int perTenant = 1500; // several SCAN pages and several delete batches
        for (int i = 0; i < perTenant; i++) {
            redis.opsForValue().set("agentic:workflow-trigger-rate:acme:wf-" + i, "1");
            redis.opsForValue().set("agentic:workflow-trigger-rate:globex:wf-" + i, "1");
        }
        redis.opsForValue().set("agentic:per-user-budget:chat:acme:u1", "1");
        redis.opsForValue().set("agentic:per-user-budget:chat:globex:u1", "1");
        redis.opsForValue().set("agentic:free-tier-usage:daily:acme", "1");
        redis.opsForValue().set("agentic:free-tier-usage:daily:globex", "1");
        redis.opsForValue().set("agentic:vision-session:s-acme", "{\"tenantId\":\"acme\"}");
        redis.opsForValue().set("agentic:vision-session:s-globex", "{\"tenantId\":\"globex\"}");
        redis.opsForValue().set("agentic:vision-session:s-bad", "not-json{{{");
        redis.opsForValue().set("unrelated:acme:key", "1");

        service.purgeForTenant("acme", List.of(), List.of());

        assertThat(redis.keys("agentic:workflow-trigger-rate:acme:*")).isEmpty();
        assertThat(redis.keys("agentic:workflow-trigger-rate:globex:*")).hasSize(perTenant);
        assertThat(redis.hasKey("agentic:per-user-budget:chat:acme:u1")).isFalse();
        assertThat(redis.hasKey("agentic:per-user-budget:chat:globex:u1")).isTrue();
        assertThat(redis.hasKey("agentic:free-tier-usage:daily:acme")).isFalse();
        assertThat(redis.hasKey("agentic:free-tier-usage:daily:globex")).isTrue();
        assertThat(redis.hasKey("agentic:vision-session:s-acme")).isFalse();
        assertThat(redis.hasKey("agentic:vision-session:s-globex")).isTrue();
        assertThat(redis.hasKey("agentic:vision-session:s-bad")).isTrue();
        assertThat(redis.hasKey("unrelated:acme:key")).isTrue();
    }
}
