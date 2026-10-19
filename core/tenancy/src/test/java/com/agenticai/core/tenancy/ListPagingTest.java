package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ListPagingTest {

    @AfterEach
    void clearProp() {
        System.clearProperty("agenticai.list.default-cap");
    }

    @Test
    void absentLimitIsUnboundedUnlessDefaultCapEnabled() {
        assertEquals(Integer.MAX_VALUE, ListPaging.limit(null, 200, 1000));
        assertEquals(25, ListPaging.limit(25, 200, 1000));
        System.setProperty("agenticai.list.default-cap", "true");
        assertEquals(200, ListPaging.limit(null, 200, 1000));
    }

    @Test
    void absurdOffsetIsClampedNotLeftToOverflow() {
        assertEquals(ListPaging.MAX_OFFSET, ListPaging.offset(3_000_000_000L));
        assertEquals(ListPaging.MAX_OFFSET, ListPaging.offset(Long.MAX_VALUE));
        var res = ListPaging.respond(List.<String>of(), 5, ListPaging.MAX_OFFSET, () -> 42);
        assertEquals("42", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", res.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void limitClamps() {
        assertEquals(1000, ListPaging.limit(5_000, 200, 1000));
        assertEquals(1, ListPaging.limit(0, 200, 1000));
    }

    @Test
    void limitDefaultsAndClamps() {
        System.setProperty("agenticai.list.default-cap", "true");
        assertEquals(200, ListPaging.limit(null, 200, 1000));
        assertEquals(1000, ListPaging.limit(5_000, 200, 1000));
        assertEquals(1, ListPaging.limit(0, 200, 1000));
        assertEquals(25, ListPaging.limit(25, 200, 1000));
    }

    @Test
    void offsetIsExactNotPageAligned() {
        var page = ListPaging.page(20, 30L, 200, 1000);
        assertEquals(30, page.getOffset());
        assertEquals(20, page.getPageSize());
        assertEquals(0, ListPaging.offset(-4L));
        assertEquals(0, ListPaging.offset(null));
    }

    @Test
    void shortFirstPageNeedsNoCountQuery() {
        var calls = new AtomicInteger();
        var res =
                ListPaging.respond(
                        List.of("a", "b"),
                        5,
                        0,
                        () -> {
                            calls.incrementAndGet();
                            return 99;
                        });
        assertEquals(0, calls.get());
        assertEquals("2", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", res.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void fullPageCountsAndFlagsMore() {
        var calls = new AtomicInteger();
        var res =
                ListPaging.respond(
                        List.of("a", "b"),
                        2,
                        0,
                        () -> {
                            calls.incrementAndGet();
                            return 7;
                        });
        assertEquals(1, calls.get());
        assertEquals("7", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", res.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void pagedInRequestAlwaysCountsAndLastPageHasNoMore() {
        var res = ListPaging.respond(List.of("x"), 5, 6, () -> 7);
        assertEquals("7", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", res.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void staleCountNeverReportsFewerThanSeen() {
        var res = ListPaging.respond(List.of("x", "y"), 2, 0, () -> 1);
        assertEquals("2", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", res.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void windowWithoutParamsReturnsEverythingAndTheTotal() {
        var res = ListPaging.window(List.of(1, 2, 3), null, null, 2, 10);
        assertEquals(List.of(1, 2, 3), res.getBody());
        assertEquals("3", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", res.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void windowSlicesAndReportsHasMore() {
        var all = List.of(1, 2, 3, 4, 5);
        var first = ListPaging.window(all, 2, null, 50, 10);
        assertEquals(List.of(1, 2), first.getBody());
        assertEquals("5", first.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", first.getHeaders().getFirst("X-Has-More"));
        var last = ListPaging.window(all, 2, 4L, 50, 10);
        assertEquals(List.of(5), last.getBody());
        assertEquals("false", last.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void windowClampsLimitAndTreatsHugeOrNegativeOffsetsSafely() {
        var all = List.of(1, 2, 3);
        assertEquals(List.of(1), ListPaging.window(all, 0, -7L, 50, 10).getBody());
        assertEquals(List.of(1, 2, 3), ListPaging.window(all, 999, null, 50, 10).getBody());
        var past = ListPaging.window(all, 5, 9_999_999_999_999L, 50, 10);
        assertEquals(List.of(), past.getBody());
        assertEquals("3", past.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", past.getHeaders().getFirst("X-Has-More"));
    }
}
