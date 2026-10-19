package com.agenticai.core.mcp.oauth;

import com.agenticai.core.crypto.EncryptionService;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Shared fixtures for the provider-registry OAuth tests. */
final class OAuthProviderTestSupport {

    static final String CALLBACK_BASE = "https://api.example.com";
    static final String CONSOLE_REDIRECT = "https://app.example.com/tools";

    private OAuthProviderTestSupport() {
    }

    /** A working EncryptionService (its init() is package-private in another module's package, hence reflection). */
    static EncryptionService encryption(byte keyFill) {
        try {
            byte[] key = new byte[32];
            java.util.Arrays.fill(key, keyFill);
            EncryptionService service = new EncryptionService(Base64.getEncoder().encodeToString(key), "");
            Method init = EncryptionService.class.getDeclaredMethod("init");
            init.setAccessible(true);
            init.invoke(service);
            return service;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Feature on, callback base set, and client credentials for the given provider ids. */
    static Map<String, String> props(String... configuredProviderIds) {
        Map<String, String> p = new HashMap<>();
        p.put("app.mcp.oauth.providers-enabled", "true");
        p.put("app.mcp.oauth.provider-callback-base-url", CALLBACK_BASE);
        p.put("app.mcp.oauth.provider-console-redirect-uri", CONSOLE_REDIRECT);
        for (String id : configuredProviderIds) {
            p.put("app.mcp.oauth.providers." + id + ".client-id", "cid-" + id);
            p.put("app.mcp.oauth.providers." + id + ".client-secret", "secret-" + id);
        }
        return p;
    }

    static OAuthProviderRegistry registry(Map<String, String> props) {
        return new OAuthProviderRegistry(props::get);
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Thread-safe {@link McpOAuthHttp} that records every request and answers through a function. */
    static final class ScriptedHttp implements McpOAuthHttp {

        record Sent(String method, String url, Map<String, String> headers, String body) {
        }

        private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
        private volatile Function<Sent, Response> responder = s -> new Response(404, Map.of(), "");
        private volatile long delayMillis;

        ScriptedHttp respond(Function<Sent, Response> f) {
            this.responder = f;
            return this;
        }

        ScriptedHttp delay(long millis) {
            this.delayMillis = millis;
            return this;
        }

        @Override
        public Response send(String method, String url, Map<String, String> headers, String body) {
            Sent s = new Sent(method, url, new HashMap<>(headers), body);
            sent.add(s);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return responder.apply(s);
        }

        List<Sent> sent() {
            synchronized (sent) {
                return new ArrayList<>(sent);
            }
        }

        long count(String method, String urlPrefix) {
            return sent().stream().filter(s -> s.method().equals(method) && s.url().startsWith(urlPrefix)).count();
        }

        Sent last() {
            List<Sent> all = sent();
            return all.get(all.size() - 1);
        }
    }

    static McpOAuthHttp.Response json(int status, String body) {
        return new McpOAuthHttp.Response(status, Map.of(), body);
    }
}
