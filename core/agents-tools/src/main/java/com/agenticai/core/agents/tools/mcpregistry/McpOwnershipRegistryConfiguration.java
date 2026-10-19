package com.agenticai.core.agents.tools.mcpregistry;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code app.mcp.registry-store=memory|redis} (default {@code memory}: the no-op registry and gate,
 * today's behaviour, Redis never resolved). Redis mode without a usable {@link StringRedisTemplate}
 * degrades to memory with a warning.
 */
// Named to avoid clashing with core/mcp's McpRegistryConfiguration: Spring derives bean names from
// the
// simple class name, and two @Configuration classes named alike in different modules fail context
// start.
@Configuration
public class McpOwnershipRegistryConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(McpOwnershipRegistryConfiguration.class);

    /**
     * One stable id per JVM: hostname (the pod name on Container Apps / k8s) plus a random suffix.
     */
    static final String REPLICA_ID =
            hostname() + "-" + UUID.randomUUID().toString().substring(0, 8);

    private static final int ORDERED_LANES = 4;

    /**
     * Field-injected (not a bean-method parameter) so the bean method's signature stays stable.
     * Default OFF.
     */
    @Value("${app.mcp.registry-route-tool-calls:false}")
    private boolean routeToolCalls;

    @Value("${app.mcp.registry-tool-call-timeout:PT60S}")
    private Duration toolCallTimeout = Duration.ofSeconds(60);

    static boolean redisMode(String store, StringRedisTemplate redis) {
        if (!"redis".equalsIgnoreCase(store == null ? "" : store.trim())) {
            return false;
        }
        if (redis == null) {
            log.warn(
                    "app.mcp.registry-store=redis but no StringRedisTemplate is available - using in-memory MCP registry");
            return false;
        }
        return true;
    }

    @Bean
    public McpOwnershipRegistry mcpOwnershipRegistry(
            @Value("${app.mcp.registry-store:memory}") String store,
            @Value("${app.mcp.registry-lease-ttl:PT60S}") Duration leaseTtl,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        if (!"redis".equalsIgnoreCase(store == null ? "" : store.trim())) {
            return new MemoryMcpOwnershipRegistry(); // never resolves the Redis template
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        return redisMode(store, redis)
                ? new RedisMcpOwnershipRegistry(redis, REPLICA_ID, leaseTtl)
                : new MemoryMcpOwnershipRegistry();
    }

    @Bean
    public McpOwnershipGate mcpOwnershipGate(
            McpOwnershipRegistry registry,
            @Value("${app.mcp.registry-exclusive-clients:false}") boolean exclusiveClients,
            @Value("${app.mcp.registry-command-timeout:PT5S}") Duration commandTimeout,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        if (!registry.active()) {
            return McpOwnershipGate.NOOP;
        }
        // Per-registration lanes: commands for one registration stay ordered, but a slow
        // connect only stalls the lane it hashes to, not every tenant's disconnects.
        ExecutorService[] lanes = new ExecutorService[ORDERED_LANES];
        for (int i = 0; i < lanes.length; i++) {
            String name = "mcp-cmd-ordered-" + i;
            lanes[i] = track(TenantAwareExecutors.newSingleThreadExecutor(name, true));
        }
        Function<McpCommand, Executor> ordered =
                c ->
                        lanes[
                                Math.floorMod(
                                        Objects.hash(c.tenant(), c.registrationId()),
                                        lanes.length)];
        ExecutorService tests =
                track(
                        TenantAwareExecutors.newFixedThreadPool(
                                4, TenantAwareExecutors.threadFactory("mcp-cmd-test", true)));
        log.info(
                "MCP registry store=redis replica={} exclusiveClients={}",
                registry.replicaId(),
                exclusiveClients);
        RedisMcpOwnershipGate gate =
                new RedisMcpOwnershipGate(
                        registry,
                        new McpCommandBus(redisProvider.getObject()),
                        exclusiveClients,
                        commandTimeout,
                        Duration.ofSeconds(20),
                        Duration.ofMinutes(5),
                        ordered,
                        tests);
        gate.configureToolCallRouting(routeToolCalls, toolCallTimeout);
        return gate;
    }

    /** Executors this configuration created; shut down with the context (JAVA-026). */
    private final java.util.List<ExecutorService> ownedExecutors =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private ExecutorService track(ExecutorService executor) {
        ownedExecutors.add(executor);
        return executor;
    }

    @jakarta.annotation.PreDestroy
    void shutdownOwnedExecutors() {
        ownedExecutors.forEach(ExecutorService::shutdownNow);
    }


    private static String hostname() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "core";
        }
    }
}
