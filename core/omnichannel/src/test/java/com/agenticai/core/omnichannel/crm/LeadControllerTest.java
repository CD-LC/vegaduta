package com.agenticai.core.omnichannel.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class LeadControllerTest {

    private LeadService service;
    private LeadController controller;

    @BeforeEach
    void setUp() {
        service = mock(LeadService.class);
        controller = new LeadController(service);
    }

    @Test
    void listParsesStageAndClampsPageSize() {
        Page<Lead> page = new PageImpl<>(List.of(new Lead("Ann", "a@x.com")));
        when(service.list(any(), any(), any(), any(Pageable.class))).thenReturn(page);
        LeadController.PageResponse r = controller.list("qualified", "web", "ann", -3, 9999);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).list(eq(LeadStage.QUALIFIED), eq("web"), eq("ann"), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(LeadController.MAX_PAGE_SIZE, pageable.getValue().getPageSize());
        assertEquals(1, r.items().size());
        assertEquals("a@x.com", r.items().get(0).email());
    }

    @Test
    void boardColumnPagesIndependentlyAndReportsItsOwnTotal() {
        // The board asks for one stage at a time and walks page 0,1,2... : the stage filter, the
        // page index and the per-column total in the body are the column-paging contract.
        List<Lead> column = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            column.add(new Lead("Ann" + i, "a" + i + "@x.com"));
        }
        Page<Lead> page = new PageImpl<>(column, org.springframework.data.domain.PageRequest.of(2, 100), 451);
        when(service.list(any(), any(), any(), any(Pageable.class))).thenReturn(page);
        LeadController.PageResponse r = controller.list("won", null, null, 2, 100);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).list(eq(LeadStage.WON), eq(null), eq(null), pageable.capture());
        assertEquals(2, pageable.getValue().getPageNumber());
        assertEquals(100, pageable.getValue().getPageSize());
        assertEquals(451, r.total());
        assertEquals(2, r.page());
        assertEquals(100, r.size());
    }

    @Test
    void unknownStageIs400() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> controller.list("nonsense", null, null, 0, 10));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void unknownActivityTypeIs400() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
            () -> controller.addActivity(UUID.randomUUID(), new LeadController.ActivityRequest("dance", "x", null)));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    @Test
    void stageEndpointDelegates() {
        UUID id = UUID.randomUUID();
        Lead l = new Lead("Ann", "a@x.com");
        l.setStage(LeadStage.WON);
        when(service.changeStage(id, LeadStage.WON, "closed")).thenReturn(l);
        assertEquals(LeadStage.WON, controller.stage(id, new LeadController.StageRequest("won", "closed")).stage());
    }

    @Test
    void importRejectsEmptyFileAndMapsCsvErrors() {
        ResponseStatusException empty = assertThrows(ResponseStatusException.class,
            () -> controller.importFile(new MockMultipartFile("file", "l.csv", "text/csv", new byte[0])));
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());

        when(service.importRows(any())).thenReturn(new LeadService.ImportResult(1, 0, 0, List.of()));
        LeadService.ImportResult r = controller.importFile(
            new MockMultipartFile("file", "l.csv", "text/csv", "name,email\nA,a@x.com\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, r.created());
    }

    @Test
    void exportSetsCsvHeaders() {
        when(service.exportAll()).thenReturn(List.of(new Lead("Ann", "a@x.com")));
        ResponseEntity<byte[]> r = controller.export();
        assertTrue(r.getHeaders().getFirst("Content-Disposition").contains("leads.csv"));
        assertTrue(new String(r.getBody(), StandardCharsets.UTF_8).contains("a@x.com"));
    }
}
