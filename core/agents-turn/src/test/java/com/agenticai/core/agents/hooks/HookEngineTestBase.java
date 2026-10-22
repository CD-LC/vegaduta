package com.agenticai.core.agents.hooks;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.audit.AuditLogService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared fixture for the {@link HookEngine} tests: a fake webhook transport, a fake clock and an
 * inline executor for informational points.
 */
abstract class HookEngineTestBase {

    static final UUID AGENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    static final String ALLOW = "{\"decision\":\"allow\"}";

    final AuditLogService audit = mock(AuditLogService.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final AtomicLong clock = new AtomicLong(1_000_000L);
    final List<String> payloads = new CopyOnWriteArrayList<>();
    volatile Function<String, HookWebhookClient.Response> responder =
            body -> new HookWebhookClient.Response(200, ALLOW);

    AgentStepEventEmitter emitter;
    HookCtx ctx;

    @BeforeEach
    void setUp() {
        emitter = AgentStepEventEmitter.active();
        ctx = new HookCtx(AGENT, "sess-1", "tenant-a", emitter, null, List.of());
    }

    // ------------------------------------------------------------------ fixtures

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    HookEngine engine(long budgetMillis) {
        HookWebhookClient client =
                new HookWebhookClient(
                        url -> Optional.empty(),
                        List.of("hooks.example.com"),
                        "",
                        (uri, payload, headers, timeout) -> {
                            payloads.add(payload);
                            return responder.apply(payload);
                        });
        ObjectProvider<MeterRegistry> meterProvider = provider(meters);
        return new HookEngine(
                client,
                new HookRateLimiter(clock::get),
                new HookDecisionSink(provider(audit), meterProvider),
                budgetMillis,
                5_000,
                Runnable::run);
    }

    HookEngine engine() {
        return engine(2_000);
    }

    static EffectiveHook hook(
            String id,
            HookPoint point,
            HookActionType action,
            HookMatch match,
            Map<String, Object> params,
            FailMode failMode) {
        return new EffectiveHook(
                "agent",
                new HookDefinition(id, true, point, match, action, params, failMode, 2_000));
    }

    static EffectiveHook hook(
            String id, HookPoint point, HookActionType action, Map<String, Object> params) {
        return hook(id, point, action, HookMatch.ANY, params, FailMode.CLOSED);
    }

    static EffectiveHook webhook(
            String id, HookPoint point, FailMode failMode, boolean includeData) {
        return hook(
                id,
                point,
                HookActionType.WEBHOOK,
                HookMatch.ANY,
                Map.of("url", "https://hooks.example.com/vet", "includeData", includeData),
                failMode);
    }

    List<String> events() {
        emitter.complete();
        return emitter.asFlux().collectList().block();
    }

    boolean sawEvent(String decision) {
        return events().stream()
                .anyMatch(
                        e ->
                                e.contains("\"type\":\"hook\"")
                                        && e.contains("\"decision\":\"" + decision + "\""));
    }

    double decisions(String decision) {
        return meters.getMeters().stream()
                .filter(
                        m ->
                                m.getId().getName().equals(HookDecisionSink.METRIC)
                                        && decision.equals(m.getId().getTag("decision")))
                .mapToDouble(m -> ((io.micrometer.core.instrument.Counter) m).count())
                .sum();
    }

    void throwOnWebhook(String message) {
        responder =
                body -> {
                    throw new HookWebhookClient.WebhookException(message);
                };
    }
}
