package com.agenticai.core.guardrails.procedures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * REQUIRE_TOOL_BEFORE across two "replicas" ({@code
 * app.guardrails.procedures.session-calls-store=redis}): two evaluators sharing one in-memory fake
 * of the Redis set commands they use. Not run against a real Redis.
 */
class ProcedureGuardEvaluatorRedisTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String SESSION = "session-1";

    private final Map<String, Set<String>> redisSets = new HashMap<>();
    private StringRedisTemplate redis;
    private ProcedureSessionCallsRedis sharedA;
    private ProcedureSessionCallsRedis sharedB;
    private ProcedureGuardEvaluator replicaA;
    private ProcedureGuardEvaluator replicaB;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void twoReplicasOneRedis() {
        ProcedureService service = mock(ProcedureService.class);
        ProcedureRule rule =
                new ProcedureRule(
                        UUID.randomUUID(),
                        1,
                        "Verify identity before refunds",
                        ProcedureGuardKind.REQUIRE_TOOL_BEFORE,
                        "process_refund",
                        "{\"prior\":\"verify_identity\"}");
        when(service.activeRulesForAgent(AGENT))
                .thenReturn(
                        List.of(
                                new ProcedureService.ResolvedProcedure(
                                        new Procedure("t1", "refunds", "d", "u"), List.of(rule))));

        SetOperations<String, String> fakeSet =
                (SetOperations<String, String>)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {SetOperations.class},
                                (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "add" -> {
                                            Object[] values = (Object[]) args[1];
                                            redisSets
                                                    .computeIfAbsent(
                                                            (String) args[0], k -> new HashSet<>())
                                                    .addAll(
                                                            Arrays.stream(values)
                                                                    .map(String::valueOf)
                                                                    .toList());
                                            return (long) values.length;
                                        }
                                        case "isMember" -> {
                                            return redisSets
                                                    .getOrDefault((String) args[0], Set.of())
                                                    .contains(String.valueOf(args[1]));
                                        }
                                        default ->
                                                throw new UnsupportedOperationException(
                                                        method.getName());
                                    }
                                });
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForSet()).thenReturn(fakeSet);

        sharedA = new ProcedureSessionCallsRedis(redis);
        sharedB = new ProcedureSessionCallsRedis(redis);
        replicaA = new ProcedureGuardEvaluator(service, new ObjectMapper());
        replicaB = new ProcedureGuardEvaluator(service, new ObjectMapper());
        replicaA.useSharedCalls(sharedA);
        replicaB.useSharedCalls(sharedB);
    }

    @AfterEach
    void stopWriters() {
        sharedA.shutdown();
        sharedB.shutdown();
    }

    @Test
    void aPrerequisiteThatRanOnAnotherReplicaSatisfiesTheRule() throws Exception {
        assertThat(replicaB.check(AGENT, SESSION, "process_refund").outcome())
                .isEqualTo(ProcedureGuardEvaluator.Outcome.DENY);

        replicaA.recordCall(SESSION, "verify_identity");
        sharedA.awaitQueuedWrites();

        assertThat(replicaB.check(AGENT, SESSION, "process_refund").outcome())
                .isEqualTo(ProcedureGuardEvaluator.Outcome.ALLOW);
    }

    @Test
    void anotherSessionsHistoryDoesNotCount() throws Exception {
        replicaA.recordCall(SESSION, "verify_identity");
        sharedA.awaitQueuedWrites();

        assertThat(replicaB.check(AGENT, "other-session", "process_refund").outcome())
                .isEqualTo(ProcedureGuardEvaluator.Outcome.DENY);
    }

    @Test
    void aRedisFailureKeepsTodaysFailClosedAnswer() throws Exception {
        replicaA.recordCall(SESSION, "verify_identity");
        sharedA.awaitQueuedWrites();
        when(redis.opsForSet()).thenThrow(new IllegalStateException("redis down"));

        assertThat(replicaB.check(AGENT, SESSION, "process_refund").outcome())
                .isEqualTo(ProcedureGuardEvaluator.Outcome.DENY);
    }

    @SuppressWarnings("unchecked")
    @Test
    void aHangingRedisIsBoundedAndKeepsTodaysFailClosedAnswer() {
        CountDownLatch neverReleased = new CountDownLatch(1);
        SetOperations<String, String> hanging = mock(SetOperations.class);
        when(hanging.isMember(anyString(), any()))
                .thenAnswer(
                        call -> {
                            neverReleased
                                    .await(); // black-holed connection: the real Lettuce call would
                            // wait 60 s
                            return true;
                        });
        when(redis.opsForSet()).thenReturn(hanging);

        long started = System.nanoTime();
        ProcedureGuardEvaluator.Outcome outcome =
                replicaB.check(AGENT, SESSION, "process_refund").outcome();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(outcome).isEqualTo(ProcedureGuardEvaluator.Outcome.DENY);
        assertThat(elapsedMillis).isLessThan(5000L);
    }

    @Test
    void withoutRedisTheEvaluatorBehavesAsBefore() {
        ProcedureService service = mock(ProcedureService.class);
        ProcedureRule rule =
                new ProcedureRule(
                        UUID.randomUUID(),
                        1,
                        "Verify identity before refunds",
                        ProcedureGuardKind.REQUIRE_TOOL_BEFORE,
                        "process_refund",
                        "{\"prior\":\"verify_identity\"}");
        when(service.activeRulesForAgent(AGENT))
                .thenReturn(
                        List.of(
                                new ProcedureService.ResolvedProcedure(
                                        new Procedure("t1", "refunds", "d", "u"), List.of(rule))));
        ProcedureGuardEvaluator plain = new ProcedureGuardEvaluator(service, new ObjectMapper());

        assertThat(plain.check(AGENT, SESSION, "process_refund").outcome())
                .isEqualTo(ProcedureGuardEvaluator.Outcome.DENY);
        plain.recordCall(SESSION, "verify_identity");
        assertThat(plain.check(AGENT, SESSION, "process_refund").outcome())
                .isEqualTo(ProcedureGuardEvaluator.Outcome.ALLOW);
    }
}
