package com.agenticai.core.agents.budget;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * A throwaway {@code redis-server} on a free port for tests that must run the real Lua scripts.
 * {@link #start()} skips the calling test class (assumption) when no {@code redis-server} binary is
 * on the PATH.
 */
public final class LocalRedis {

    private Process server;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate template;

    public static LocalRedis start() throws Exception {
        assumeTrue(onPath("redis-server"), "redis-server is not installed");
        LocalRedis r = new LocalRedis();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        r.server =
                new ProcessBuilder(
                                "redis-server",
                                "--port",
                                Integer.toString(port),
                                "--save",
                                "",
                                "--appendonly",
                                "no",
                                "--bind",
                                "127.0.0.1")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        r.factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port));
        r.factory.afterPropertiesSet();
        r.template = new StringRedisTemplate(r.factory);
        r.template.afterPropertiesSet();
        for (int i = 0; i < 50; i++) {
            try {
                r.factory.getConnection().ping();
                return r;
            } catch (RuntimeException notUpYet) {
                Thread.sleep(100);
            }
        }
        r.stop();
        throw new IllegalStateException("redis-server did not start");
    }

    public StringRedisTemplate template() {
        return template;
    }

    public void flush() {
        factory.getConnection().serverCommands().flushAll();
    }

    public void stop() throws InterruptedException {
        if (factory != null) {
            factory.destroy();
        }
        if (server != null) {
            server.destroy();
            server.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static boolean onPath(String binary) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, binary).canExecute()) {
                return true;
            }
        }
        return false;
    }
}
