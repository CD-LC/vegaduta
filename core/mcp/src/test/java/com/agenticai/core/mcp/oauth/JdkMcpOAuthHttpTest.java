package com.agenticai.core.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.mcp.McpRemoteUrlGuard;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Where the SSRF guarantees live: https-only, guard rejection, no redirects, body cap and timeout. */
class JdkMcpOAuthHttpTest {

    private final JdkMcpOAuthHttp http = new JdkMcpOAuthHttp(new McpRemoteUrlGuard(), 5, 4096);

    @Test
    void refusesNonHttpsBeforeAnyNetworkCall() {
        assertThrows(McpOAuthBlockedException.class, () -> http.send("GET", "http://example.com/x", Map.of(), null));
        assertThrows(McpOAuthBlockedException.class, () -> http.send("GET", "file:///etc/passwd", Map.of(), null));
        assertThrows(McpOAuthBlockedException.class, () -> http.send("GET", null, Map.of(), null));
    }

    @Test
    void guardRejectionsSurfaceAsBlocked() {
        for (String url : new String[] {
            "https://127.0.0.1/x", "https://169.254.169.254/latest/meta-data", "https://10.0.0.5/token", "https://[::1]/x"
        }) {
            McpOAuthBlockedException e = assertThrows(McpOAuthBlockedException.class,
                () -> http.send("GET", url, Map.of(), null), url);
            assertTrue(e.getMessage().contains("blocked"), url);
        }
    }

    @Test
    void clientNeverFollowsRedirects() {
        assertEquals(HttpClient.Redirect.NEVER, JdkMcpOAuthHttp.newClient(Duration.ofSeconds(1)).followRedirects());
        assertEquals(HttpClient.Redirect.NEVER, http.clientRedirectPolicy());
    }

    @Test
    void bodyOverTheCapIsRejected() {
        InputStream big = new ByteArrayInputStream(new byte[2048]);
        assertThrows(McpOAuthException.class, () -> JdkMcpOAuthHttp.readBounded(big, "https://x", 1024, Duration.ofSeconds(2)));
        assertEquals("abc", JdkMcpOAuthHttp.readBounded(new ByteArrayInputStream("abc".getBytes()), "https://x", 1024, Duration.ofSeconds(2)));
    }

    @Test
    void stalledBodyReadTimesOutAndClosesStream() {
        AtomicBoolean closed = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        InputStream never = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                return -1;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        try {
            assertThrows(McpOAuthException.class, () -> JdkMcpOAuthHttp.readBounded(never, "https://x", 1024, Duration.ofMillis(200)));
            assertTrue(closed.get());
        } finally {
            release.countDown();
        }
    }
}
