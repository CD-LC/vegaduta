package com.agenticai.core.omnichannel.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class LeadServiceTest {

    private LeadRepository leads;
    private LeadActivityRepository activities;
    private LeadService service;
    private final List<LeadActivity> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        leads = mock(LeadRepository.class);
        activities = mock(LeadActivityRepository.class);
        service = new LeadService(leads, activities);
        when(leads.save(any(Lead.class))).thenAnswer(inv -> {
            Lead l = inv.getArgument(0);
            if (l.getId() == null) {
                ReflectionTestUtils.setField(l, "id", UUID.randomUUID());
            }
            return l;
        });
        when(activities.save(any(LeadActivity.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    private static LeadService.LeadInput input(String name, String email) {
        return new LeadService.LeadInput(name, email, null, null, null, null, null, null, null, null, null, null);
    }

    private Lead existing(String email, LeadStage stage) {
        Lead l = new Lead("Ann", email);
        l.setStage(stage);
        ReflectionTestUtils.setField(l, "id", UUID.randomUUID());
        when(leads.findById(l.getId())).thenReturn(Optional.of(l));
        when(leads.findByEmail(email)).thenReturn(Optional.of(l));
        return l;
    }

    @Test
    void createNormalizesEmailAndDefaultsToNew() {
        when(leads.findByEmail("a@b.com")).thenReturn(Optional.empty());
        Lead l = service.create(new LeadService.LeadInput(
            "Ann", "  A@B.com ", "555", "Acme", null, "web", null, 250, null, new BigDecimal("1200.50"),
            List.of("vip", "vip", "eu"), null));
        assertEquals("a@b.com", l.getEmail());
        assertEquals(LeadStage.NEW, l.getStage());
        assertEquals(100, l.getScore());
        assertEquals(List.of("vip", "eu"), l.tagList());
        assertNotNull(l.getId());
    }

    @Test
    void createRejectsEmptyAndDuplicate() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () -> service.create(input(" ", null))).getStatusCode());
        existing("dup@x.com", LeadStage.NEW);
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class, () -> service.create(input("Dup", "DUP@x.com"))).getStatusCode());
        verify(leads, never()).save(any());
    }

    @Test
    void changeStageRecordsTimelineAndStamps() {
        Lead l = existing("a@x.com", LeadStage.NEW);
        service.changeStage(l.getId(), LeadStage.CONTACTED, null);
        assertEquals(LeadStage.CONTACTED, l.getStage());
        assertNotNull(l.getLastContactedAt());
        assertEquals(1, saved.size());
        assertEquals(LeadActivityType.STAGE_CHANGE, saved.get(0).getType());
        assertEquals("NEW", saved.get(0).getMeta().get("from"));
        assertEquals("CONTACTED", saved.get(0).getMeta().get("to"));
    }

    @Test
    void changeToSameStageIsNoOp() {
        Lead l = existing("a@x.com", LeadStage.QUALIFIED);
        service.changeStage(l.getId(), LeadStage.QUALIFIED, null);
        assertTrue(saved.isEmpty());
    }

    @Test
    void emailReplyAdvancesNewAndContactedButNotLaterStages() {
        Lead fresh = existing("n@x.com", LeadStage.NEW);
        service.addActivity(fresh.getId(), LeadActivityType.EMAIL_REPLY, "thanks", null);
        assertEquals(LeadStage.REPLIED, fresh.getStage());
        assertNotNull(fresh.getLastRepliedAt());

        Lead advanced = existing("q@x.com", LeadStage.PROPOSAL);
        service.addActivity(advanced.getId(), LeadActivityType.EMAIL_REPLY, "ok", null);
        assertEquals(LeadStage.PROPOSAL, advanced.getStage());
        assertNotNull(advanced.getLastRepliedAt());
    }

    @Test
    void emailSentMovesNewToContacted() {
        Lead l = existing("s@x.com", LeadStage.NEW);
        service.addActivity(l.getId(), LeadActivityType.EMAIL_SENT, "hi", null);
        assertEquals(LeadStage.CONTACTED, l.getStage());
        assertNotNull(l.getLastContactedAt());
    }

    @Test
    void recordEmailReplyIgnoresUnknownSender() {
        when(leads.findByEmail("who@x.com")).thenReturn(Optional.empty());
        assertTrue(service.recordEmailReply("Who@x.com", "hi").isEmpty());
        assertTrue(service.recordEmailReply(null, "hi").isEmpty());
        assertTrue(saved.isEmpty());
    }

    @Test
    void recordEmailReplyMatchesCaseInsensitively() {
        Lead l = existing("known@x.com", LeadStage.CONTACTED);
        Optional<Lead> hit = service.recordEmailReply("KNOWN@x.com", "yes please");
        assertTrue(hit.isPresent());
        assertEquals(LeadStage.REPLIED, l.getStage());
        assertTrue(saved.stream().anyMatch(a -> a.getType() == LeadActivityType.EMAIL_REPLY));
    }

    @Test
    void upsertUpdatesExistingByEmailAndKeepsUnsetFields() {
        Lead l = existing("u@x.com", LeadStage.NEW);
        l.setCompany("Old Co");
        Lead out = service.upsert(new LeadService.LeadInput(
            "New Name", "u@x.com", null, null, null, null, null, null, null, null, null, null));
        assertEquals("New Name", out.getName());
        assertEquals("Old Co", out.getCompany());
    }

    @Test
    void updateBlankStringClearsField() {
        Lead l = existing("c@x.com", LeadStage.NEW);
        l.setPhone("123");
        service.update(l.getId(), new LeadService.LeadInput(
            null, null, "", null, null, null, null, null, null, null, null, null));
        assertNull(l.getPhone());
    }

    @Test
    void updateStageGoesThroughTimeline() {
        Lead l = existing("t@x.com", LeadStage.NEW);
        service.update(l.getId(), new LeadService.LeadInput(
            null, null, null, null, null, null, LeadStage.WON, null, null, null, null, null));
        assertEquals(LeadStage.WON, l.getStage());
        assertEquals(1, saved.size());
    }

    @Test
    void updateEmailClashConflicts() {
        Lead a = existing("a@x.com", LeadStage.NEW);
        existing("b@x.com", LeadStage.NEW);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> service.update(a.getId(), input(null, "b@x.com"))).getStatusCode());
    }

    @Test
    void getUnknownIs404() {
        UUID id = UUID.randomUUID();
        when(leads.findById(id)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () -> service.get(id)).getStatusCode());
    }

    @Test
    void pipelineIsZeroFilledInBoardOrder() {
        when(leads.pipelineTotals()).thenReturn(List.<Object[]>of(
            new Object[] {LeadStage.QUALIFIED, 2L, new BigDecimal("300.00")}));
        List<LeadService.StageTotal> p = service.pipeline();
        assertEquals(LeadStage.values().length, p.size());
        assertEquals(LeadStage.NEW, p.get(0).stage());
        assertEquals(0, p.get(0).count());
        assertEquals(2, p.get(3).count());
        assertEquals(new BigDecimal("300.00"), p.get(3).value());
    }

    @Test
    void importCountsCreatedUpdatedAndSkipped() {
        when(leads.findByEmail("new@x.com")).thenReturn(Optional.empty());
        existing("old@x.com", LeadStage.NEW);
        List<LeadCsv.ParsedRow> rows = LeadCsv.parse("name,email\nNew,new@x.com\nOld,old@x.com\n,\n");
        LeadService.ImportResult r = service.importRows(rows);
        assertEquals(1, r.created());
        assertEquals(1, r.updated());
        assertEquals(1, r.skipped());
        assertEquals(1, r.errors().size());
        assertEquals(Map.of("from", "NEW"), Map.of("from", "NEW"));
    }
}
