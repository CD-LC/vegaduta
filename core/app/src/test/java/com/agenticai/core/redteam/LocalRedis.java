package com.agenticai.core.redteam;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** A throw-away {@code redis-server} on a free port. {@link #start} returns null when the binary is not installed. */
final class LocalRedis implements AutoCloseable {

    private final Process process;
    private final LettuceConnectionFactory factory;
    final StringRedisTemplate template;

    private LocalRedis(Process process, LettuceConnectionFactory factory) {
        this.process = process;
        this.factory = factory;
        this.template = new StringRedisTemplate(factory);
        this.template.afterPropertiesSet();
    }

    static boolean binaryAvailable() {
        try {
            Process p = new ProcessBuilder("redis-server", "--version").redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    static LocalRedis start() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Path dir = Files.createTempDirectory("redteam-redis");
        Process p = new ProcessBuilder("redis-server", "--port", String.valueOf(port), "--bind", "127.0.0.1",
                        "--save", "", "--appendonly", "no", "--dir", dir.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        LettuceConnectionFactory f = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", port));
        f.afterPropertiesSet();
        LocalRedis r = new LocalRedis(p, f);
        for (int i = 0; i < 50; i++) {
            try {
                if ("PONG".equals(r.template.getConnectionFactory().getConnection().ping())) {
                    return r;
                }
            } catch (RuntimeException notYet) {
                Thread.sleep(100);
            }
        }
        r.close();
        throw new IllegalStateException("redis-server did not come up");
    }

    @Override
    public void close() {
        factory.destroy();
        process.destroy();
    }
}
