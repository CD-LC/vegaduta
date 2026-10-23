package com.agenticai.core.platform.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The userId and name filters are forwarded to Langfuse as query values, so they are allowlisted.
 */
class PlatformTraceFilterTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC);

    private final List<URI> calls = new ArrayList<>();

    private PlatformTraceService service() {
        LangfuseApiClient client =
                new LangfuseApiClient(
                        "http://lf:3000",
                        "Basic x",
                        (uri, auth) -> {
                            calls.add(uri);
                            return new LangfuseApiClient.Response(200, "{\"data\":[],\"meta\":{}}");
                        },
                        CLOCK);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        return new PlatformTraceService(
                client,
                factory.getBeanProvider(com.agenticai.core.tenancy.TenantRepository.class),
                CLOCK);
    }

    @Test
    void ordinaryUserIdsAndNamesAreForwarded() {
        service()
                .list(
                        null,
                        null,
                        null,
                        "3f0a2b1c-4d5e-6f70-8192-a3b4c5d6e7f8",
                        "agent turn.chat-v2",
                        1,
                        10);
        service().list(null, null, null, "ops+qa@example.com", null, 1, 10);
        String q = calls.get(0).getRawQuery();
        assertTrue(q.contains("userId=3f0a2b1c-4d5e-6f70-8192-a3b4c5d6e7f8"), q);
        assertTrue(q.contains("name=agent+turn.chat-v2"), q);
        assertTrue(calls.get(1).getRawQuery().contains("userId=ops%2Bqa%40example.com"));
    }

    @Test
    void blankFiltersMeanNoFilter() {
        service().list(null, null, null, "  ", "", 1, 10);
        String q = calls.get(0).getRawQuery();
        assertEquals(-1, q.indexOf("userId="), q);
        assertEquals(-1, q.indexOf("name="), q);
    }

    @Test
    void valuesOutsideTheAllowlistAreRejectedNotForwarded() {
        PlatformTraceService svc = service();
        for (String bad :
                new String[] {
                    "a&limit=1000",
                    "a=b",
                    "../../x",
                    "a#frag",
                    "a?b",
                    "ünïcode",
                    "a\nb",
                    "x".repeat(129)
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> svc.list(null, null, null, bad, null, 1, 10),
                    bad);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> svc.list(null, null, null, null, bad, 1, 10),
                    bad);
        }
        assertTrue(calls.isEmpty(), "nothing reached Langfuse");
    }
}
