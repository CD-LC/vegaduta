package com.agenticai.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;

/**
 * {@link AuditPaging}: the {@code limit}/{@code offset} clamps, the {@code X-Total-Count} /
 * {@code X-Has-More} header contract (the count only runs when it can change the answer), and the
 * exact-offset {@link Pageable} {@code PageRequest} cannot express.
 */
class AuditPagingTest {

    @Test
    void clampLimitDefaultsFloorsAtOneAndCapsAtMax() {
        assertEquals(50, AuditPaging.clampLimit(null, 50, 200));
        assertEquals(1, AuditPaging.clampLimit(0, 50, 200));
        assertEquals(1, AuditPaging.clampLimit(-7, 50, 200));
        assertEquals(200, AuditPaging.clampLimit(10_000, 50, 200));
        assertEquals(37, AuditPaging.clampLimit(37, 50, 200));
    }

    @Test
    void clampOffsetDefaultsFloorsAtZeroAndCapsAtMaxOffset() {
        assertEquals(0L, AuditPaging.clampOffset(null));
        assertEquals(0L, AuditPaging.clampOffset(-5L));
        assertEquals(42L, AuditPaging.clampOffset(42L));
        assertEquals(AuditPaging.MAX_OFFSET, AuditPaging.clampOffset(Long.MAX_VALUE));
    }

    @Test
    void aShortFirstPageIsItsOwnTotalAndSkipsTheCount() {
        AtomicBoolean counted = new AtomicBoolean(false);

        ResponseEntity<List<String>> response =
                AuditPaging.respond(
                        List.of("a", "b"), 50, 0, () -> {
                            counted.set(true);
                            return 999L;
                        });

        assertEquals("2", response.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", response.getHeaders().getFirst("X-Has-More"));
        assertFalse(counted.get(), "count must not run for a short first page");
    }

    @Test
    void aFullFirstPageConsultsTheCountForHasMore() {
        ResponseEntity<List<String>> response =
                AuditPaging.respond(List.of("a", "b"), 2, 0, () -> 5L);

        assertEquals("5", response.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", response.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void anEmptyPageStillRunsTheCount() {
        ResponseEntity<List<String>> response =
                AuditPaging.respond(List.of(), 50, 200, () -> 210L);

        assertEquals("210", response.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", response.getHeaders().getFirst("X-Has-More"),
            "210 total rows still sit ahead of the 200 offset - there is more to page");
    }

    @Test
    void totalIsNeverReportedBelowWhatTheCallerHasAlreadySeen() {
        // A shrinking table: count says 3 but the page already exposed 10
        // rows - the header must not lie below seen.
        ResponseEntity<List<String>> response =
                AuditPaging.respond(List.of("x"), 5, 9, () -> 3L);

        assertEquals("10", response.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", response.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void respondWithTotalUsesTheKnownCountAndStillFloorsAtSeen() {
        ResponseEntity<List<String>> response =
                AuditPaging.respondWithTotal(List.of("a", "b"), 0, 100L);
        assertEquals("100", response.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", response.getHeaders().getFirst("X-Has-More"));

        ResponseEntity<List<String>> floored =
                AuditPaging.respondWithTotal(List.of("a", "b"), 200, 50L);
        assertEquals("202", floored.getHeaders().getFirst("X-Total-Count"));
    }

    @Test
    void offsetLimitCarriesAnExactRowOffset() {
        Pageable pageable = AuditPaging.offsetLimit(30, 25, Sort.by("createdAt"));

        assertEquals(30L, pageable.getOffset());
        assertEquals(25, pageable.getPageSize());
        assertEquals(1, pageable.getPageNumber());
        assertEquals(Sort.by("createdAt"), pageable.getSort());
        assertTrue(pageable.hasPrevious());
        assertTrue(pageable.toOptional().isPresent());
    }

    @Test
    void offsetLimitDefaultsToUnsortedAndNavigatesByExactOffsets() {
        Pageable pageable = AuditPaging.offsetLimit(10, 25, null);

        assertEquals(Sort.unsorted(), pageable.getSort());
        assertEquals(35L, pageable.next().getOffset());
        assertEquals(0L, pageable.previousOrFirst().getOffset());
        assertEquals(0L, pageable.first().getOffset());
        assertEquals(50L, pageable.withPage(2).getOffset());
        assertFalse(AuditPaging.offsetLimit(0, 25, null).hasPrevious());
    }

    @Test
    void offsetLimitRejectsNegativeOffsetAndZeroLimit() {
        assertThrows(IllegalArgumentException.class, () -> AuditPaging.offsetLimit(-1, 25, null));
        assertThrows(IllegalArgumentException.class, () -> AuditPaging.offsetLimit(0, 0, null));
    }
}
