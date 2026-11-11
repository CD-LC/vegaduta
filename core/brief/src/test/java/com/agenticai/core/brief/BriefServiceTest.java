package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.brief.BriefValidationResult.Issue;
import com.agenticai.core.brief.dto.ApplyDtos.ApplyResult;
import com.agenticai.core.brief.dto.BriefDtos.CreateResponse;
import com.agenticai.core.brief.dto.BriefDtos.Detail;
import com.agenticai.core.brief.dto.BriefDtos.ListItem;
import com.agenticai.core.brief.dto.BriefDtos.UpdateResponse;
import com.agenticai.core.brief.dto.LedgerDtos.LedgerSummary;
import com.agenticai.core.brief.dto.ReadinessRow;
import com.agenticai.core.brief.model.Brief;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Persistence and lifecycle rules of {@link BriefService} against mocked repositories. */
class BriefServiceTest {

    private static final UUID ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private final ObjectMapper mapper = new ObjectMapper();
    private BriefRepository briefs;
    private BriefVersionRepository versions;
    private BriefBindingRepository bindings;
    private BriefValidator validator;
    private BriefReadinessService readiness;
    private AuditLogService audit;
    private BriefApplyService apply;
    private ProofLedgerService ledger;
    private BriefService service;

    @BeforeEach
    void setUp() {
        briefs = mock(BriefRepository.class);
        versions = mock(BriefVersionRepository.class);
        bindings = mock(BriefBindingRepository.class);
        validator = mock(BriefValidator.class);
        readiness = mock(BriefReadinessService.class);
        audit = mock(AuditLogService.class);
        apply = mock(BriefApplyService.class);
        ledger = mock(ProofLedgerService.class);
        service =
                new BriefService(
                        briefs, versions, bindings, validator, readiness, audit, mapper, apply,
                        ledger);
        when(validator.validate(any(), anyBoolean()))
                .thenReturn(new BriefValidationResult(List.of(), List.of(), List.of()));
    }

    private BriefEntity entity(String status, int version) {
        BriefEntity e =
                new BriefEntity(ID, "Invoice chaser", "goal", "en", "UNDER_WATCH", "tester");
        e.setStatus(status);
        e.touch("Invoice chaser", "goal", "en", version);
        return e;
    }

    private void storedVersion(int version) throws Exception {
        when(versions.findByBriefIdAndVersion(ID, version))
                .thenReturn(
                        Optional.of(
                                new BriefVersionEntity(
                                        ID,
                                        version,
                                        mapper.writeValueAsString(BriefFixtures.valid().build()),
                                        null,
                                        "n",
                                        "tester")));
    }

    // --- create ----------------------------------------------------------

    @Test
    void createSavesVersionOneAtTheLowestRungAndAudits() {
        CreateResponse r = service.create(BriefFixtures.valid().build(), "first");

        assertThat(r.version()).isEqualTo(1);
        ArgumentCaptor<BriefEntity> saved = ArgumentCaptor.forClass(BriefEntity.class);
        verify(briefs).save(saved.capture());
        assertThat(saved.getValue().getRung()).isEqualTo("UNDER_WATCH");
        ArgumentCaptor<BriefVersionEntity> version =
                ArgumentCaptor.forClass(BriefVersionEntity.class);
        verify(versions).save(version.capture());
        assertThat(version.getValue().getVersion()).isEqualTo(1);
        assertThat(version.getValue().getNote()).isEqualTo("first");
        verify(audit)
                .record(
                        anyString(),
                        eq("BRIEF_CREATED"),
                        eq("brief"),
                        anyString(),
                        eq(AuditOutcome.SUCCESS),
                        eq("version 1"));
    }

    @Test
    void aSetOfQuestionsIsNotSavable() {
        Brief questions =
                new Brief(
                        1,
                        null,
                        null,
                        null,
                        List.of(new Brief.Clarification("Which tool?", List.of("a", "b"))),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertThatThrownBy(() -> service.create(questions, null))
                .isInstanceOf(BriefValidationException.class);
        verify(briefs, never()).save(any());
    }

    @Test
    void aBriefAboveTheLowestRungIsRefusedWithTheTrustRungError() {
        Brief b = BriefFixtures.valid().build();
        Brief trusted = BriefVersionStore.withRung(b, TrustRung.TRUSTED);

        assertThatThrownBy(() -> service.create(trusted, null))
                .isInstanceOfSatisfying(
                        BriefValidationException.class,
                        e ->
                                assertThat(e.getErrors())
                                        .extracting(Issue::section)
                                        .contains("trust.rung"));
    }

    @Test
    void validatorErrorsBlockTheSave() {
        when(validator.validate(any(), anyBoolean()))
                .thenReturn(
                        new BriefValidationResult(
                                List.of(new Issue("title", "required")), List.of(), List.of()));

        assertThatThrownBy(() -> service.create(BriefFixtures.valid().build(), null))
                .isInstanceOf(BriefValidationException.class);
        verify(briefs, never()).save(any());
    }

    // --- list ------------------------------------------------------------

    @Test
    void listCarriesProofNumbersOnlyForAppliedBriefsAndSurvivesAnUnreadableLedger() {
        BriefEntity applied = entity(BriefEntity.APPLIED, 2);
        BriefEntity draft = entity(BriefEntity.DRAFT, 1);
        when(briefs.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(applied, draft));
        when(ledger.summary(ID, false))
                .thenReturn(new LedgerSummary("MEASURED", null, 0.6, 0.8, 0.2, true, 3, 0.05));

        List<ListItem> items = service.list();

        assertThat(items).hasSize(2);
        assertThat(items.get(0).baseline()).isEqualTo(0.6);
        assertThat(items.get(0).lastDelta()).isEqualTo(0.2);
        assertThat(items.get(1).baseline()).isNull();

        when(ledger.summary(ID, false)).thenThrow(new IllegalStateException("ledger down"));
        assertThat(service.list().get(0).baseline()).isNull();
    }

    @Test
    void listBoundToReturnsEachOwningBriefOnce() {
        when(bindings.findByEntityId("agent-1"))
                .thenReturn(
                        List.of(
                                new BriefBindingEntity(ID, "a1", "AGENT", "agent-1"),
                                new BriefBindingEntity(ID, "a2", "AGENT", "agent-1")));
        when(briefs.findAllById(List.of(ID))).thenReturn(List.of(entity(BriefEntity.DRAFT, 1)));

        assertThat(service.listBoundTo("agent-1")).hasSize(1);
    }

    // --- get / readiness -------------------------------------------------

    @Test
    void getReturnsTheCurrentManifestWithLiveReadinessAndBindings() throws Exception {
        when(briefs.findById(ID)).thenReturn(Optional.of(entity(BriefEntity.DRAFT, 2)));
        storedVersion(2);
        when(bindings.findByBriefId(ID))
                .thenReturn(List.of(new BriefBindingEntity(ID, "a1", "AGENT", "agent-1")));
        when(versions.findByBriefIdOrderByVersionDesc(ID)).thenReturn(List.of());
        ReadinessRow row = mock(ReadinessRow.class);
        when(readiness.compute(any(Brief.class), any())).thenReturn(List.of(row));
        when(ledger.summary(ID, true)).thenThrow(new IllegalStateException("ledger down"));

        Detail d = service.get(ID);

        assertThat(d.brief().title()).isEqualTo("Invoice chaser");
        assertThat(d.readiness()).containsExactly(row);
        assertThat(d.bindings()).hasSize(1);
        assertThat(d.bindings().get(0).entityId()).isEqualTo("agent-1");
        assertThat(d.rung()).isEqualTo("UNDER_WATCH");
    }

    @Test
    void readinessUsesTheBindingMap() throws Exception {
        when(briefs.findById(ID)).thenReturn(Optional.of(entity(BriefEntity.DRAFT, 1)));
        storedVersion(1);
        when(bindings.findByBriefId(ID))
                .thenReturn(List.of(new BriefBindingEntity(ID, "a1", "AGENT", "agent-1")));
        when(readiness.compute(any(Brief.class), any())).thenReturn(List.of());

        assertThat(service.readiness(ID)).isEmpty();
        verify(readiness).compute(any(Brief.class), eq(java.util.Map.of("a1", "agent-1")));
    }

    @Test
    void anUnknownBriefIsNotFound() {
        when(briefs.findById(ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(ID)).isInstanceOf(BriefNotFoundException.class);
    }

    @Test
    void aMissingOrCorruptStoredManifestFailsLoudly() {
        when(briefs.findById(ID)).thenReturn(Optional.of(entity(BriefEntity.DRAFT, 1)));
        when(versions.findByBriefIdAndVersion(ID, 1)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.readiness(ID)).isInstanceOf(BriefNotFoundException.class);

        when(versions.findByBriefIdAndVersion(ID, 1))
                .thenReturn(
                        Optional.of(new BriefVersionEntity(ID, 1, "{not json", null, null, "t")));
        assertThatThrownBy(() -> service.readiness(ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be read");
    }

    // --- update ----------------------------------------------------------

    @Test
    void updateWritesTheNextVersionAfterTheHighestExistingOne() {
        BriefEntity e = entity(BriefEntity.DRAFT, 2);
        when(briefs.findById(ID)).thenReturn(Optional.of(e));
        when(versions.findByBriefIdOrderByVersionDesc(ID))
                .thenReturn(
                        List.of(
                                new BriefVersionEntity(ID, 5, "{}", 4, null, "t"),
                                new BriefVersionEntity(ID, 2, "{}", 1, null, "t")));

        UpdateResponse r = service.update(ID, BriefFixtures.valid().build(), "tweak");

        assertThat(r.version()).isEqualTo(6);
        assertThat(e.getCurrentVersion()).isEqualTo(6);
        ArgumentCaptor<BriefVersionEntity> v = ArgumentCaptor.forClass(BriefVersionEntity.class);
        verify(versions).save(v.capture());
        assertThat(v.getValue().getParentVersion()).isEqualTo(2);
        verify(audit)
                .record(
                        anyString(),
                        eq("BRIEF_UPDATED"),
                        eq("brief"),
                        anyString(),
                        eq(AuditOutcome.SUCCESS),
                        eq("version 6"));
    }

    @Test
    void anAppliedBriefCannotBeEditedDirectly() {
        when(briefs.findById(ID)).thenReturn(Optional.of(entity(BriefEntity.APPLIED, 1)));

        assertThatThrownBy(() -> service.update(ID, BriefFixtures.valid().build(), null))
                .isInstanceOf(BriefConflictException.class)
                .hasMessageContaining("use tune");
    }

    @Test
    void aFailedBriefMayBeEditedAgain() {
        when(briefs.findById(ID)).thenReturn(Optional.of(entity(BriefEntity.FAILED, 1)));
        when(versions.findByBriefIdOrderByVersionDesc(ID)).thenReturn(List.of());

        assertThat(service.update(ID, BriefFixtures.valid().build(), null).version()).isEqualTo(2);
    }

    // --- delete ----------------------------------------------------------

    @Test
    void deleteWithoutPurgeJustRemovesTheBrief() {
        BriefEntity e = entity(BriefEntity.DRAFT, 1);
        when(briefs.findById(ID)).thenReturn(Optional.of(e));

        service.delete(ID, false);

        verify(briefs).delete(e);
        verify(apply, never()).purge(any());
    }

    @Test
    void deleteWithPurgeKeepsTheBriefWhenTheRealEntitiesCouldNotBeRemoved() {
        when(briefs.findById(ID)).thenReturn(Optional.of(entity(BriefEntity.APPLIED, 1)));
        when(bindings.findByBriefId(ID))
                .thenReturn(List.of(new BriefBindingEntity(ID, "a1", "AGENT", "agent-1")));
        when(apply.purge(ID))
                .thenReturn(
                        new ApplyResult(ID, "FAILED", 1, "agent busy", null, List.of(), List.of()));

        assertThatThrownBy(() -> service.delete(ID, true))
                .isInstanceOf(BriefConflictException.class)
                .hasMessageContaining("agent busy");
        verify(briefs, never()).delete(any(BriefEntity.class));
    }

    @Test
    void deleteWithPurgeRemovesTheBriefAfterASuccessfulPurge() {
        BriefEntity e = entity(BriefEntity.APPLIED, 1);
        when(briefs.findById(ID)).thenReturn(Optional.of(e));
        when(bindings.findByBriefId(ID))
                .thenReturn(List.of(new BriefBindingEntity(ID, "a1", "AGENT", "agent-1")));
        when(apply.purge(ID))
                .thenReturn(new ApplyResult(ID, "SUCCEEDED", 1, null, null, List.of(), List.of()));

        service.delete(ID, true);

        verify(briefs).delete(e);
    }

    @Test
    void deleteWithPurgeSkipsThePurgeWhenNothingWasBound() {
        BriefEntity e = entity(BriefEntity.DRAFT, 1);
        when(briefs.findById(ID)).thenReturn(Optional.of(e));
        when(bindings.findByBriefId(ID)).thenReturn(List.of());

        service.delete(ID, true);

        verify(apply, never()).purge(any());
        verify(briefs).delete(e);
    }
}
