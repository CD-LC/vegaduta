package com.agenticai.core.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/**
 * Optional {@code ?limit=} on GET /api/approvals: default cap, explicit limit, hard max, status
 * filter.
 */
class ApprovalControllerListPagingTest {

    @org.junit.jupiter.api.BeforeEach
    void defaultCapOn() {
        System.setProperty("agenticai.list.default-cap", "true");
    }

    @org.junit.jupiter.api.AfterEach
    void defaultCapOff() {
        System.clearProperty("agenticai.list.default-cap");
    }

    private ApprovalRequestRepository repo;
    private ApprovalController controller;

    @BeforeEach
    void setUp() {
        repo = mock(ApprovalRequestRepository.class);
        when(repo.findAllByOrderByCreatedAtDescIdDesc(any(Pageable.class))).thenReturn(List.of());
        when(repo.findByStatusOrderByCreatedAtDescIdDesc(
                        any(ApprovalStatus.class), any(Pageable.class)))
                .thenReturn(List.of());
        controller =
                new ApprovalController(
                        mock(HumanApprovalGateService.class),
                        repo,
                        mock(ApprovalPolicyService.class),
                        com.agenticai.core.json.JsonMappers.plain(),
                        List.of());
    }

    private int pageSizeFor(Integer limit) {
        controller.list(null, limit, null);
        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(repo, org.mockito.Mockito.atLeastOnce())
                .findAllByOrderByCreatedAtDescIdDesc(c.capture());
        Pageable p = c.getValue();
        assertEquals(0, p.getOffset());
        return p.getPageSize();
    }

    @Test
    void defaultsToTwoHundred() {
        assertEquals(200, pageSizeFor(null));
    }

    @Test
    void explicitLimitIsHonoured() {
        assertEquals(25, pageSizeFor(25));
    }

    @Test
    void limitIsClampedToMaxAndMin() {
        assertEquals(1000, pageSizeFor(50_000));
        assertEquals(1, pageSizeFor(0));
        assertEquals(1, pageSizeFor(-5));
    }

    @Test
    void headersReportTotalAndTruncationOnlyWhenPageIsFull() {
        // under the cap: total is the row count, no count query, no truncation
        var small = controller.list(null, 5, null);
        assertEquals("0", small.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", small.getHeaders().getFirst("X-Has-More"));
        verify(repo, org.mockito.Mockito.never()).count();

        // full page: the real total comes from the count query and flags truncation
        when(repo.findAllByOrderByCreatedAtDescIdDesc(any(Pageable.class)))
                .thenReturn(List.of(new ApprovalRequest("a", "s", "{}", "u")));
        when(repo.count()).thenReturn(42L);
        var full = controller.list(null, 1, null);
        assertEquals("42", full.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", full.getHeaders().getFirst("X-Has-More"));

        when(repo.findByStatusOrderByCreatedAtDescIdDesc(
                        any(ApprovalStatus.class), any(Pageable.class)))
                .thenReturn(List.of(new ApprovalRequest("a", "s", "{}", "u")));
        when(repo.countByStatus(ApprovalStatus.PENDING)).thenReturn(1L);
        var exact = controller.list(ApprovalStatus.PENDING, 1, null);
        assertEquals("1", exact.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", exact.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void statusFilterKeepsNewestFirstQueryAndCap() {
        controller.list(ApprovalStatus.PENDING, null, null);
        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(repo)
                .findByStatusOrderByCreatedAtDescIdDesc(eq(ApprovalStatus.PENDING), c.capture());
        assertEquals(200, c.getValue().getPageSize());
    }

    @Test
    void offsetIsExactAndPagedInRequestsAlwaysCount() {
        when(repo.findAllByOrderByCreatedAtDescIdDesc(any(Pageable.class))).thenReturn(List.of());
        when(repo.count()).thenReturn(30L);
        var res = controller.list(null, 20, 30L);
        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(repo, org.mockito.Mockito.atLeastOnce())
                .findAllByOrderByCreatedAtDescIdDesc(c.capture());
        assertEquals(30, c.getValue().getOffset());
        assertEquals("30", res.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", res.getHeaders().getFirst("X-Has-More"));
    }
}
