package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.Disposable;

/**
 * The shared cancel poll against a REAL Redis-protocol server: the batched DEL script consumes
 * exactly the flags that exist, each cancels its own turn, an unflagged turn is untouched, a whole
 * pod's watching is one template call per poll AND opens no new TCP connection (checked with {@code
 * INFO stats}), and a server that denies EVAL via ACL still cancels through the per-key fallback.
 * Skipped unless {@code VEGADUTA_TEST_REDIS=host:port} is set (see {@code
 * SessionAppendScriptRedisTest}).
 */
class ChatTurnCancelServiceRedisTest {

    private static final String TARGET = System.getenv("VEGADUTA_TEST_REDIS");
    private static LettuceConnectionFactory factory;
    private static CountingTemplate redis;

    static final class CountingTemplate extends StringRedisTemplate {
        final AtomicInteger roundTrips = new AtomicInteger();

        CountingTemplate(LettuceConnectionFactory f) {
            super(f);
        }

        @Override
        public <T> T execute(RedisCallback<T> action, boolean exposeConnection, boolean pipeline) {
            roundTrips.incrementAndGet();
            return super.execute(action, exposeConnection, pipeline);
        }
    }

    @BeforeAll
    static void connect() {
        assumeTrue(TARGET != null && TARGET.contains(":"), "VEGADUTA_TEST_REDIS=host:port not set");
        String[] hp = TARGET.split(":");
        factory =
                new LettuceConnectionFactory(
                        new RedisStandaloneConfiguration(hp[0], Integer.parseInt(hp[1])));
        factory.afterPropertiesSet();
        redis = new CountingTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void onlyTheFlaggedTurnsAreCancelledAndTheFlagIsConsumed() throws Exception {
        ChatTurnCancelService service = new ChatTurnCancelService(redis, Duration.ofMillis(40));
        try {
            UUID agent = UUID.randomUUID();
            int turns = 50;
            List<String> cancelled = new CopyOnWriteArrayList<>();
            List<Disposable> subs = new ArrayList<>();
            for (int i = 0; i < turns; i++) {
                String session = "s-" + i;
                subs.add(
                        service.watchForCancellation(agent, session)
                                .subscribe(x -> cancelled.add(session)));
            }
            for (int i = 0; i < turns; i += 10) {
                assertTrue(service.requestCancellation(agent, "s-" + i));
            }

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (cancelled.size() < 5 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(150); // let any wrongly-cancelled turn show up
            assertEquals(5, cancelled.size(), cancelled.toString());
            for (int i = 0; i < turns; i += 10) {
                assertTrue(cancelled.contains("s-" + i));
                assertFalse(
                        redis.hasKey("agentic:chat-turn-cancel:" + agent + ":s-" + i),
                        "flag consumed");
            }
            subs.forEach(Disposable::dispose);
        } finally {
            service.shutdown();
        }
    }

    /** Runs {@code ACL <args>} on the test server over a throwaway Lettuce connection. */
    private static void acl(String... args) {
        String[] hp = TARGET.split(":");
        io.lettuce.core.RedisClient client =
                io.lettuce.core.RedisClient.create(
                        io.lettuce.core.RedisURI.create(hp[0], Integer.parseInt(hp[1])));
        try (io.lettuce.core.api.StatefulRedisConnection<String, String> conn = client.connect()) {
            io.lettuce.core.protocol.CommandArgs<String, String> ca =
                    new io.lettuce.core.protocol.CommandArgs<>(
                            io.lettuce.core.codec.StringCodec.UTF8);
            for (String a : args) {
                ca.add(a);
            }
            conn.sync()
                    .dispatch(
                            new io.lettuce.core.protocol.ProtocolKeyword() {
                                @Override
                                public byte[] getBytes() {
                                    return "ACL".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                }

                                public String name() {
                                    return "ACL";
                                }
                            },
                            new io.lettuce.core.output.NestedMultiOutput<>(
                                    io.lettuce.core.codec.StringCodec.UTF8),
                            ca);
        } finally {
            client.shutdown();
        }
    }

    private static long connectionsReceived() {
        String info =
                redis.execute(
                        (RedisCallback<String>)
                                c ->
                                        c.serverCommands()
                                                .info("stats")
                                                .getProperty("total_connections_received"));
        return Long.parseLong(info.trim());
    }

    @Test
    void pollingReusesTheSharedConnectionAndOpensNoNewOnes() throws Exception {
        ChatTurnCancelService service = new ChatTurnCancelService(redis, Duration.ofMillis(1000));
        try {
            UUID agent = UUID.randomUUID();
            List<Disposable> subs = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                subs.add(service.watchForCancellation(agent, "k-" + i).subscribe());
            }
            service.pollOnce(); // warm the shared connection and the script cache
            long before = connectionsReceived();
            for (int i = 0; i < 25; i++) {
                service.pollOnce();
            }
            long opened = connectionsReceived() - before;
            System.out.println("CANCEL-POLL new TCP connections over 25 polls: " + opened);
            assertEquals(0, opened);
            subs.forEach(Disposable::dispose);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void whenTheServerDeniesScriptsTheFallbackStillCancelsTheTurn() throws Exception {
        String[] hp = TARGET.split(":");
        acl("SETUSER", "noscript", "on", "nopass", "~*", "+@all", "-eval", "-evalsha");
        RedisStandaloneConfiguration cfg =
                new RedisStandaloneConfiguration(hp[0], Integer.parseInt(hp[1]));
        cfg.setUsername("noscript");
        LettuceConnectionFactory restricted = new LettuceConnectionFactory(cfg);
        restricted.afterPropertiesSet();
        StringRedisTemplate restrictedRedis = new StringRedisTemplate(restricted);
        restrictedRedis.afterPropertiesSet();
        ChatTurnCancelService service =
                new ChatTurnCancelService(restrictedRedis, Duration.ofMillis(1000));
        try {
            UUID agent = UUID.randomUUID();
            List<String> cancelled = new CopyOnWriteArrayList<>();
            Disposable a =
                    service.watchForCancellation(agent, "a").subscribe(x -> cancelled.add("a"));
            Disposable b =
                    service.watchForCancellation(agent, "b").subscribe(x -> cancelled.add("b"));
            assertTrue(service.requestCancellation(agent, "a"));

            service.pollOnce();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (cancelled.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Thread.sleep(100);
            assertEquals(List.of("a"), cancelled);
            a.dispose();
            b.dispose();
        } finally {
            service.shutdown();
            restricted.destroy();
            acl("DELUSER", "noscript");
        }
    }

    @Test
    void aWholePodsWatchingIsOneRoundTripPerPoll() throws Exception {
        ChatTurnCancelService service = new ChatTurnCancelService(redis, Duration.ofMillis(1000));
        try {
            UUID agent = UUID.randomUUID();
            List<Disposable> subs = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                subs.add(service.watchForCancellation(agent, "c-" + i).subscribe());
            }
            redis.roundTrips.set(0);
            service.pollOnce();
            int perPoll = redis.roundTrips.get();
            System.out.println(
                    "CANCEL-POLL template calls for one poll covering 200 in-flight turns: "
                            + perPoll);
            assertEquals(1, perPoll);
            subs.forEach(Disposable::dispose);
        } finally {
            service.shutdown();
        }
    }
}
