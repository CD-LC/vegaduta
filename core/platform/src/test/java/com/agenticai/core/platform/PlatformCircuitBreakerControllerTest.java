package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

class PlatformCircuitBreakerControllerTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final PlatformCircuitBreakerController controller = new PlatformCircuitBreakerController(redis, 5, 5, 10);

    @SuppressWarnings("unchecked")
    private static Cursor<String> cursor(List<String> keys) {
        Cursor<String> c = mock(Cursor.class);
        Iterator<String> it = keys.iterator();
        when(c.hasNext()).thenAnswer(i -> it.hasNext());
        when(c.next()).thenAnswer(i -> it.next());
        return c;
    }

    @Test
    void listReportsOpenCircuitsWithTtlAndStreaksAndConfig() {
        String open = PlatformCircuitBreakerController.OPEN_KEY_PREFIX + "t1:web_search";
        String streak = PlatformCircuitBreakerController.FAILURE_KEY_PREFIX + "t2:crm:lookup";
        Cursor<String> firstScan = cursor(List.of(open));
        Cursor<String> secondScan = cursor(List.of(streak));
        when(redis.scan(any(ScanOptions.class))).thenReturn(firstScan, secondScan);
        when(redis.getExpire(open, TimeUnit.SECONDS)).thenReturn(300L);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(streak)).thenReturn("3");
        when(ops.get(PlatformCircuitBreakerController.LAST_ERROR_KEY_PREFIX + "t1:web_search"))
            .thenReturn("connection refused");

        var listing = controller.list();

        assertEquals(1, listing.openCount());
        assertEquals(2, listing.entries().size());
        assertTrue(listing.entries().get(0).open());
        assertEquals("t1", listing.entries().get(0).tenantId());
        assertEquals(300L, listing.entries().get(0).cooldownSecondsRemaining());
        assertEquals("connection refused", listing.entries().get(0).lastError());
        var second = listing.entries().get(1);
        assertFalse(second.open());
        assertEquals("crm:lookup", second.tool()); // tool names may contain ':'
        assertEquals(3L, second.consecutiveFailures());
        assertEquals(10, listing.config().cooldownMinutes());
    }

    @Test
    void listSkipsKeysThatExpiredBetweenScanAndTtl() {
        String open = PlatformCircuitBreakerController.OPEN_KEY_PREFIX + "t1:x";
        Cursor<String> firstScan = cursor(List.of(open));
        Cursor<String> secondScan = cursor(List.of());
        when(redis.scan(any(ScanOptions.class))).thenReturn(firstScan, secondScan);
        when(redis.getExpire(open, TimeUnit.SECONDS)).thenReturn(-2L);
        assertEquals(0, controller.list().entries().size());
    }

    @Test
    void resetDeletesAllKeysAndReportsWasOpen() {
        when(redis.delete(PlatformCircuitBreakerController.OPEN_KEY_PREFIX + "t1:web_search")).thenReturn(true);
        var r = controller.reset(new PlatformCircuitBreakerController.ResetRequest(" t1 ", "web_search"));
        assertTrue(r.wasOpen());
        verify(redis).delete(PlatformCircuitBreakerController.FAILURE_KEY_PREFIX + "t1:web_search");
        verify(redis).delete(PlatformCircuitBreakerController.LAST_ERROR_KEY_PREFIX + "t1:web_search");
    }

    @Test
    void resetRequiresTenantAndTool() {
        assertThrows(ResponseStatusException.class,
            () -> controller.reset(new PlatformCircuitBreakerController.ResetRequest("t1", " ")));
        assertThrows(ResponseStatusException.class, () -> controller.reset(null));
    }

    @Test
    void listReturns503WhenRedisIsDown() {
        when(redis.scan(any(ScanOptions.class))).thenThrow(new IllegalStateException("down"));
        var ex = assertThrows(ResponseStatusException.class, controller::list);
        assertEquals(503, ex.getStatusCode().value());
    }
}
