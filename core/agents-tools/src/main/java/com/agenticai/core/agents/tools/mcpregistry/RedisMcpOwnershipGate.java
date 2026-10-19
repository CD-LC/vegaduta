package com.agenticai.core.agents.tools.mcpregistry;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.mcp.McpDynamicConnector;
import com.agenticai.core.mcp.dto.McpConnectionTestResult;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.AllowUnlockedSchedule;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * {@code app.mcp.registry-store=redis}. Live clients stay in each JVM; this class only coordinates.
 *
 * <ul>
 *   <li><b>Default (replicated) mode</b> - every replica still builds its own client (today's
 *       steady state and the only way chat turns on any replica see the tools, since a tool call
 *       must never hop to another pod). The owner is the coordinator: it alone runs the retry sweep
 *       and persists health diagnoses, TEST is answered by it (it holds the stderr tail), and a
 *       user connect/disconnect is broadcast so a disabled server does not stay callable through
 *       another replica.
 *   <li><b>{@code exclusive-clients=true}</b> - only the owner connects; a connect landing
 *       elsewhere is routed to the owner. A non-owner replica then has no client and its chat turns
 *       do not see that server's tools, so this is for shapes where MCP-using traffic is pinned to
 *       the owner.
 * </ul>
 *
 * A silent owner is taken over only after its lease TTL has expired, never earlier. Every Redis
 * failure fails OPEN to the local behaviour (logged once). Command handling runs off the request
 * thread and re-applies the tenant.
 */
public final class RedisMcpOwnershipGate implements McpOwnershipGate {

    private static final Logger log = LoggerFactory.getLogger(RedisMcpOwnershipGate.class);
    private static final ObjectMapper JSON = JsonMappers.plain();
    private static final String COMMAND_ACTOR = "system:mcp-command";
    private static final int DRAIN_BATCH = 20;

    /** True while a routed command runs on this thread, so it is never re-routed. */
    private static final ThreadLocal<Boolean> HANDLING = new ThreadLocal<>();

    private final McpOwnershipRegistry registry;
    private final McpCommandBus bus;
    private final boolean exclusiveClients;
    private final Duration commandTimeout;
    private final Duration testTimeout;
    private final Duration connectGrace;
    private final Function<McpCommand, Executor> orderedExecutor;
    private final Executor testExecutor;
    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile McpDynamicConnector local;
    private volatile BiPredicate<String, UUID> hasLiveClient = (t, id) -> false;
    private volatile McpToolCaller toolCaller;
    private volatile boolean routeToolCalls;
    private volatile Duration toolCallTimeout = Duration.ofSeconds(60);

    /**
     * Off by default ({@code app.mcp.registry-route-tool-calls}); a setter keeps the constructors'
     * arity.
     */
    public void configureToolCallRouting(boolean enabled, Duration timeout) {
        this.routeToolCalls = enabled;
        if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            this.toolCallTimeout = timeout;
        }
    }

    @Override
    public void attachToolCaller(McpToolCaller caller) {
        this.toolCaller = caller;
    }

    @Override
    public Optional<String> routeToolCall(
            String tenant, UUID id, String toolName, String argsJson) {
        if (!routeToolCalls || HANDLING.get() != null || hasLiveClient.test(tenant, id)) {
            return Optional.empty();
        }
        Optional<String> owner;
        try {
            owner = registry.ownerOf(tenant, id).filter(o -> !o.equals(registry.replicaId()));
        } catch (RuntimeException e) {
            failOpen(e);
            return Optional.empty();
        }
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        try {
            String payload =
                    JSON.writeValueAsString(
                            java.util.Map.of(
                                    "tool", toolName, "args", argsJson == null ? "" : argsJson));
            Optional<String> reply =
                    bus.sendAndAwait(
                            owner.get(), McpCommand.Op.CALL, tenant, id, payload, toolCallTimeout);
            if (reply.isEmpty()) {
                throw new IllegalStateException(
                        "MCP owner replica did not answer the routed tool call within "
                                + toolCallTimeout);
            }
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(reply.get());
            if (node.path("ok").asBoolean(false)) {
                return Optional.of(node.path("result").asText(""));
            }
            throw new IllegalStateException(
                    "MCP owner replica failed the tool call: "
                            + node.path("error").asText("unknown"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while routing an MCP tool call", e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Malformed routed MCP tool call or reply", e);
        }
    }

    public RedisMcpOwnershipGate(
            McpOwnershipRegistry registry,
            McpCommandBus bus,
            boolean exclusiveClients,
            Duration commandTimeout,
            Duration testTimeout,
            Duration connectGrace,
            Executor orderedExecutor,
            Executor testExecutor) {
        this(
                registry,
                bus,
                exclusiveClients,
                commandTimeout,
                testTimeout,
                connectGrace,
                c -> orderedExecutor,
                testExecutor);
    }

    /**
     * {@code orderedExecutor} must map every command of one registration to the same single-thread
     * lane.
     */
    public RedisMcpOwnershipGate(
            McpOwnershipRegistry registry,
            McpCommandBus bus,
            boolean exclusiveClients,
            Duration commandTimeout,
            Duration testTimeout,
            Duration connectGrace,
            Function<McpCommand, Executor> orderedExecutor,
            Executor testExecutor) {
        this.registry = registry;
        this.bus = bus;
        this.exclusiveClients = exclusiveClients;
        this.commandTimeout = commandTimeout;
        this.testTimeout = testTimeout;
        this.connectGrace = connectGrace;
        this.orderedExecutor = orderedExecutor;
        this.testExecutor = testExecutor;
    }

    @Override
    public void attach(McpDynamicConnector local, BiPredicate<String, UUID> hasLiveClient) {
        this.local = local;
        this.hasLiveClient = hasLiveClient;
    }

    // ---- routing (request thread) ----

    @Override
    public boolean routeConnect(String tenant, UUID id) {
        if (HANDLING.get() != null) {
            return false;
        }
        try {
            boolean owner = registry.claim(tenant, id);
            if (!exclusiveClients && isUserRequest()) {
                broadcast(McpCommand.Op.CONNECT, tenant, id);
            }
            if (owner || !exclusiveClients) {
                return false;
            }
            Optional<String> ownerId = registry.ownerOf(tenant, id);
            if (ownerId.isEmpty()) {
                return !registry.claim(tenant, id);
            }
            if (bus.sendAndAwait(ownerId.get(), McpCommand.Op.CONNECT, tenant, id, commandTimeout)
                    .isPresent()) {
                return true;
            }
            // Owner silent: only a lapsed lease lets us take over (no double subprocess).
            if (registry.claim(tenant, id)) {
                return false;
            }
            log.warn(
                    "MCP registration {} owner {} did not answer within {} but its lease is still valid; "
                            + "not connecting locally (takeover only after lease expiry)",
                    id,
                    ownerId.get(),
                    commandTimeout);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            return failOpen(e);
        }
    }

    @Override
    public void broadcastDisconnect(String tenant, UUID id) {
        if (HANDLING.get() != null) {
            return;
        }
        try {
            broadcast(McpCommand.Op.DISCONNECT, tenant, id);
        } catch (RuntimeException e) {
            failOpen(e);
        }
    }

    @Override
    public Optional<McpConnectionTestResult> routeTest(String tenant, UUID id) {
        if (HANDLING.get() != null) {
            return Optional.empty();
        }
        try {
            Optional<String> owner =
                    registry.ownerOf(tenant, id).filter(o -> !o.equals(registry.replicaId()));
            if (owner.isEmpty()) {
                return Optional.empty();
            }
            Optional<String> reply =
                    bus.sendAndAwait(
                            owner.get(),
                            McpCommand.Op.TEST,
                            tenant,
                            id,
                            testTimeout.plus(commandTimeout));
            if (reply.isEmpty()) {
                // A test uses a throwaway client and registers nothing, so running it here is safe.
                log.warn(
                        "MCP test for {} routed to owner {} timed out; running the throwaway test locally",
                        id,
                        owner.get());
                return Optional.empty();
            }
            return Optional.of(JSON.readValue(reply.get(), McpConnectionTestResult.class));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            failOpen(e);
            return Optional.empty();
        }
    }

    @Override
    public boolean mayRetry(String tenant, UUID id) {
        return registry.claim(tenant, id);
    }

    @Override
    public boolean mayPersistHealth(String tenant, UUID id) {
        return registry.ownerOf(tenant, id).map(o -> o.equals(registry.replicaId())).orElse(true);
    }

    // ---- scheduled: heartbeat + command poller (off the request thread) ----

    @AllowUnlockedSchedule(
            reason =
                    "Renews THIS replica's own leases and replica registration; must run on every replica by design.")
    @Scheduled(fixedDelayString = "${app.mcp.registry-heartbeat-ms:15000}")
    public void heartbeat() {
        registry.registerReplica();
        registry.renewOwned((t, id) -> hasLiveClient.test(t, id), connectGrace);
    }

    @AllowUnlockedSchedule(
            reason =
                    "Drains THIS replica's own per-replica Redis command queue; must run on every replica by design.")
    @Scheduled(fixedDelayString = "${app.mcp.registry-poll-ms:250}")
    public void pollCommands() {
        List<McpCommand> commands;
        try {
            commands = bus.drain(registry.replicaId(), DRAIN_BATCH);
        } catch (RuntimeException e) {
            failOpen(e);
            return;
        }
        for (McpCommand c : commands) {
            (c.op() == McpCommand.Op.TEST || c.op() == McpCommand.Op.CALL
                            ? testExecutor
                            : orderedExecutor.apply(c))
                    .execute(() -> handle(c));
        }
    }

    /**
     * Runs one routed command locally; tenant is re-applied explicitly because this is not the
     * request thread.
     */
    void handle(McpCommand c) {
        McpDynamicConnector connector = local;
        if (c.op() == McpCommand.Op.CALL) {
            handleCall(c);
            return;
        }
        if (connector == null || c.tenant() == null || c.registrationId() == null) {
            return;
        }
        HANDLING.set(Boolean.TRUE);
        TenantContext.set(TenantContext.of(c.tenant(), COMMAND_ACTOR, Set.of()));
        try {
            String payload = "ok";
            switch (c.op()) {
                case CONNECT -> connector.connect(c.registrationId());
                case DISCONNECT -> connector.disconnect(c.registrationId());
                case CALL -> {} // handled above
                case TEST -> payload = JSON.writeValueAsString(connector.test(c.registrationId()));
            }
            bus.reply(c, payload);
        } catch (Throwable t) {
            log.warn(
                    "Routed MCP command {} for registration {} failed",
                    c.op(),
                    c.registrationId(),
                    t);
        } finally {
            TenantContext.clear();
            HANDLING.remove();
        }
    }

    /**
     * Owner side of a routed tool call. Replies {"ok":true,"result":..} or {"ok":false,"error":..};
     * never throws.
     */
    private void handleCall(McpCommand c) {
        McpToolCaller caller = toolCaller;
        if (caller == null
                || !routeToolCalls
                || c.tenant() == null
                || c.registrationId() == null
                || c.payload() == null) {
            return; // not enabled here: the sender times out rather than being answered with a
            // guess
        }
        HANDLING.set(Boolean.TRUE);
        TenantContext.set(TenantContext.of(c.tenant(), COMMAND_ACTOR, Set.of()));
        try {
            com.fasterxml.jackson.databind.node.ObjectNode out = JSON.createObjectNode();
            try {
                com.fasterxml.jackson.databind.JsonNode in = JSON.readTree(c.payload());
                out.put(
                        "result",
                        caller.call(
                                c.tenant(),
                                c.registrationId(),
                                in.path("tool").asText(),
                                in.path("args").asText("")));
                out.put("ok", true);
            } catch (Exception e) {
                out.put("ok", false);
                out.put("error", String.valueOf(e.getMessage()));
            }
            bus.reply(c, JSON.writeValueAsString(out));
        } catch (Throwable t) {
            log.warn("Routed MCP tool call for registration {} failed", c.registrationId(), t);
        } finally {
            TenantContext.clear();
            HANDLING.remove();
        }
    }

    private void broadcast(McpCommand.Op op, String tenant, UUID id) {
        for (String replica : registry.liveReplicas()) {
            if (!replica.equals(registry.replicaId())) {
                bus.send(replica, new McpCommand(op, tenant, id, null));
            }
        }
    }

    /**
     * Startup/retry/routed work runs as a "system:" subject; only a real user request is broadcast
     * to peers.
     */
    private static boolean isUserRequest() {
        return TenantContext.current()
                .map(TenantContext::getSubject)
                .filter(s -> s != null && !s.startsWith("system:"))
                .isPresent();
    }

    private boolean failOpen(Exception e) {
        if (warned.compareAndSet(false, true)) {
            log.warn(
                    "MCP command routing: Redis unavailable, failing open to local behaviour (logged once): {}",
                    e.toString());
        }
        return false;
    }
}
