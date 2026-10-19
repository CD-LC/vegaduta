package com.agenticai.core.audit.seal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogEntry;
import com.agenticai.core.audit.AuditLogRepository;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.audit.seal.AuditSealDtos.ExportBundle;
import com.agenticai.core.audit.seal.SealTestSupport.TestSigner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The sealing/verification service against mocked repositories backed by in-memory lists that mimic what the
 * native queries do in Postgres (rows ordered by created_at then id; boundaries compared by position).
 */
class AuditSealServiceTest {

    private static final String SCHEMA = "t_acme";
    private static final Instant T0 = Instant.parse("2026-10-03T00:00:00Z");
    private static final Instant NOW = T0.plusSeconds(3600);

    private AuditLogRepository logRepository;
    private AuditSealRepository sealRepository;
    private ObjectProvider<AuditSealSigner> signerProvider;
    private TestSigner signer;

    /** the audit table, in (created_at, id) order */
    private final List<AuditLogEntry> log = new ArrayList<>();
    /** the audit_seal table, in seq order */
    private final List<AuditSeal> seals = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        logRepository = mock(AuditLogRepository.class);
        sealRepository = mock(AuditSealRepository.class);
        signerProvider = mock(ObjectProvider.class);
        signer = new TestSigner("kid-1");
        when(signerProvider.getIfAvailable()).thenReturn(signer);

        when(logRepository.findFirstUnsealed(any(Instant.class), anyInt())).thenAnswer(inv -> {
            Instant settledBefore = inv.getArgument(0);
            int limit = inv.getArgument(1);
            return log.stream().filter(e -> e.getCreatedAt().isBefore(settledBefore)).limit(limit).toList();
        });
        when(logRepository.findUnsealedAfter(any(Instant.class), any(Instant.class), any(UUID.class), anyInt()))
            .thenAnswer(inv -> {
                Instant settledBefore = inv.getArgument(0);
                UUID afterId = inv.getArgument(2);
                int limit = inv.getArgument(3);
                return log.stream().skip(indexOf(afterId) + 1L)
                    .filter(e -> e.getCreatedAt().isBefore(settledBefore)).limit(limit).toList();
            });
        when(logRepository.findInSealRange(
            any(Instant.class), any(UUID.class), any(Instant.class), any(UUID.class), anyInt()))
            .thenAnswer(inv -> {
                UUID fromId = inv.getArgument(1);
                UUID toId = inv.getArgument(3);
                int limit = inv.getArgument(4);
                int from = indexOf(fromId);
                int to = indexOf(toId);
                if (from < 0 || to < from) {
                    return List.of();
                }
                return log.subList(from, to + 1).stream().limit(limit).toList();
            });

        when(sealRepository.save(any(AuditSeal.class))).thenAnswer(inv -> {
            AuditSeal s = inv.getArgument(0);
            seals.add(s);
            return s;
        });
        when(sealRepository.findTopByOrderBySeqDesc()).thenAnswer(inv ->
            seals.isEmpty() ? Optional.empty() : Optional.of(seals.get(seals.size() - 1)));
        when(sealRepository.findBySeq(anyLong())).thenAnswer(inv -> {
            long seq = inv.getArgument(0);
            return seals.stream().filter(s -> s.getSeq() == seq).findFirst();
        });
        when(sealRepository.findByLastRowAtGreaterThanEqualAndFirstRowAtLessThanEqualOrderBySeqAsc(
            any(Instant.class), any(Instant.class), any(Pageable.class))).thenAnswer(inv -> {
                Instant from = inv.getArgument(0);
                Instant to = inv.getArgument(1);
                Pageable page = inv.getArgument(2);
                return seals.stream()
                    .filter(s -> !s.getLastRowAt().isBefore(from) && !s.getFirstRowAt().isAfter(to))
                    .limit(page.getPageSize()).toList();
            });
    }

    private AuditSealService service(int maxRows, long settleSeconds) {
        return service(maxRows, settleSeconds, 3600);
    }

    private AuditSealService service(int maxRows, long settleSeconds, long maxDeferSeconds) {
        return new AuditSealService(
            logRepository, sealRepository, signerProvider, maxRows, settleSeconds, maxDeferSeconds);
    }

    private static Optional<Double> epochSeconds(Instant at) {
        return Optional.of(at.toEpochMilli() / 1000.0 + (at.getNano() % 1_000_000) / 1e9);
    }

    private int indexOf(UUID id) {
        for (int i = 0; i < log.size(); i++) {
            if (id.equals(log.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    private AuditLogEntry entry(int i, String detail) {
        AuditLogEntry e = new AuditLogEntry("alice", "action." + i, "agent", "t-" + i, AuditOutcome.SUCCESS, detail);
        ReflectionTestUtils.setField(e, "id", SealTestSupport.uuid(i));
        ReflectionTestUtils.setField(e, "createdAt",
            AuditSealHasher.fromMicros(AuditSealHasher.toMicros(T0) + i * 1_000L));
        return e;
    }

    private void fillLog(int n) {
        for (int i = 0; i < n; i++) {
            log.add(entry(i, "detail " + i));
        }
    }

    private void sealEverything(AuditSealService service) {
        while (service.sealNextBatch(SCHEMA, NOW).isPresent()) {
            // drain
        }
    }

    @Test
    void sealsSettledRowsInBoundedBatchesAndChainsThem() {
        fillLog(7);
        AuditSealService service = service(3, 120);

        AuditSeal first = service.sealNextBatch(SCHEMA, NOW).orElseThrow();
        assertEquals(1, first.getSeq());
        assertEquals(3, first.getRowCount());
        assertEquals(AuditSealHasher.GENESIS_HASH, first.getPrevSealHash());
        assertEquals(SealTestSupport.uuid(0), first.getFirstRowId());
        assertEquals(SealTestSupport.uuid(2), first.getLastRowId());
        assertEquals(SCHEMA, first.getTenantScope());
        assertEquals("kid-1", first.getKeyId());
        assertEquals(signer.publicKeyB64u(), first.getPublicKey());
        assertEquals(NOW, first.getSealedAt());
        assertEquals(AuditSealHasher.ALGORITHM, first.getAlgorithm());
        assertTrue(AuditSealSignatures.verify(first.toRecord()));

        AuditSeal second = service.sealNextBatch(SCHEMA, NOW).orElseThrow();
        assertEquals(2, second.getSeq());
        assertEquals(first.getSealHash(), second.getPrevSealHash());
        assertEquals(SealTestSupport.uuid(3), second.getFirstRowId(), "continues right after the previous boundary");
        assertEquals(3, second.getRowCount());

        AuditSeal third = service.sealNextBatch(SCHEMA, NOW).orElseThrow();
        assertEquals(1, third.getRowCount());
        assertEquals(second.getSealHash(), third.getPrevSealHash());

        assertTrue(service.sealNextBatch(SCHEMA, NOW).isEmpty(), "nothing left to seal");
        assertEquals(3, seals.size());
    }

    @Test
    void aSealedLogVerifiesEndToEnd() {
        fillLog(7);
        AuditSealService service = service(3, 120);
        sealEverything(service);

        AuditSealVerifier.Result r = service.verify(null, null);
        assertTrue(r.ok(), r.reason());
        assertEquals(3, r.sealsChecked());
        assertEquals(7, r.rowsChecked());
        assertFalse(r.truncated());
        assertEquals(3L, r.headSeq());
    }

    @Test
    void rowsInsideTheSettleWindowAreNotSealedYet() {
        fillLog(3);
        AuditSealService service = service(100, 120);
        // "now" is only 60s after the rows: they have not settled
        assertTrue(service.sealNextBatch(SCHEMA, T0.plusSeconds(60)).isEmpty());
        assertTrue(seals.isEmpty());
        assertTrue(service.sealNextBatch(SCHEMA, T0.plusSeconds(121)).isPresent());
    }

    @Test
    void rowsAtOrAfterTheOldestOpenTransactionAreNotSealed() {
        fillLog(6); // rows at T0 + 0..5 ms
        // a transaction opened 2.5 ms after T0 may still commit rows stamped after that instant
        when(logRepository.findOldestOpenTransactionStartEpochSeconds())
            .thenReturn(epochSeconds(T0.plusNanos(2_500_000)));
        AuditSealService service = service(100, 0);

        AuditSeal seal = service.sealNextBatch(SCHEMA, NOW).orElseThrow();
        assertEquals(3, seal.getRowCount(), "only rows 0,1,2 are older than the open transaction");
        assertTrue(service.sealNextBatch(SCHEMA, NOW).isEmpty());

        // transaction finished: the rest becomes sealable
        when(logRepository.findOldestOpenTransactionStartEpochSeconds()).thenReturn(Optional.empty());
        assertEquals(3, service.sealNextBatch(SCHEMA, NOW).orElseThrow().getRowCount());
    }

    @Test
    void deferralBehindAnOpenTransactionIsCapped() {
        fillLog(3);
        // a transaction that has been open for ten days must not block sealing forever
        when(logRepository.findOldestOpenTransactionStartEpochSeconds())
            .thenReturn(epochSeconds(T0.minusSeconds(864_000)));
        // NOW - 3599 s = T0 + 1 s, so all three rows are older than the floor
        AuditSealService capped = service(100, 0, 3599);
        assertEquals(3, capped.sealNextBatch(SCHEMA, NOW).orElseThrow().getRowCount());
    }

    @Test
    void nothingIsSealedWithoutAUsableSigner() {
        fillLog(3);
        when(signerProvider.getIfAvailable()).thenReturn(null);
        AuditSealService service = service(100, 0);
        assertFalse(service.signerAvailable());
        assertTrue(service.sealNextBatch(SCHEMA, NOW).isEmpty());

        when(signerProvider.getIfAvailable()).thenReturn(signer);
        signer.available = false;
        assertFalse(service.signerAvailable());
        assertTrue(service.sealNextBatch(SCHEMA, NOW).isEmpty());

        AuditSealSigner failing = mock(AuditSealSigner.class);
        when(failing.isAvailable()).thenReturn(true);
        when(failing.sign(any())).thenReturn(Optional.empty());
        when(signerProvider.getIfAvailable()).thenReturn(failing);
        assertTrue(service.sealNextBatch(SCHEMA, NOW).isEmpty());

        verify(sealRepository, never()).save(any(AuditSeal.class));
    }

    @Test
    void batchSizeIsClampedToSaneBounds() {
        fillLog(1);

        service(0, 0).sealNextBatch(SCHEMA, NOW);
        verify(logRepository).findFirstUnsealed(any(Instant.class), eq(1));

        seals.clear();
        org.mockito.Mockito.clearInvocations(logRepository);
        service(10_000_000, 0).sealNextBatch(SCHEMA, NOW);
        verify(logRepository).findFirstUnsealed(any(Instant.class), eq(AuditSealService.MAX_ROWS_PER_SEAL_CEILING));
    }

    @Test
    void verifyDetectsAModifiedRowThroughTheService() {
        fillLog(6);
        AuditSealService service = service(3, 0);
        sealEverything(service);
        log.set(4, entry(4, "rewritten after the fact"));

        AuditSealVerifier.Result r = service.verify(null, null);
        assertFalse(r.ok());
        assertEquals(2L, r.firstBrokenSeq());
        assertTrue(r.reason().contains("range_hash mismatch"), r.reason());
    }

    @Test
    void verifyDetectsADeletedRowAndAnInsertedRow() {
        fillLog(6);
        AuditSealService service = service(3, 0);
        sealEverything(service);

        AuditLogEntry removed = log.remove(1);
        AuditSealVerifier.Result deleted = service.verify(null, null);
        assertFalse(deleted.ok());
        assertEquals(1L, deleted.firstBrokenSeq());

        log.add(1, removed);
        log.add(2, entry(99, "sneaked in"));
        AuditSealVerifier.Result inserted = service.verify(null, null);
        assertFalse(inserted.ok());
        assertTrue(inserted.reason().contains("late"), inserted.reason());
    }

    @Test
    void verifyDetectsADroppedSealAndAMissingPredecessor() {
        fillLog(9);
        AuditSealService service = service(3, 0);
        sealEverything(service);

        AuditSeal middle = seals.remove(1);
        AuditSealVerifier.Result gap = service.verify(null, null);
        assertFalse(gap.ok());
        assertTrue(gap.reason().contains("sequence gap"), gap.reason());

        seals.remove(0); // now the window starts at seq 3 and seq 2 is gone
        AuditSealVerifier.Result noPredecessor = service.verify(null, null);
        assertFalse(noPredecessor.ok());
        assertTrue(noPredecessor.reason().contains("missing"), noPredecessor.reason());
        assertNotNull(middle);
    }

    @Test
    void verifyOfAnEmptyLogIsOkAndABackwardsWindowIsRejected() {
        AuditSealService service = service(3, 0);
        AuditSealVerifier.Result r = service.verify(null, null);
        assertTrue(r.ok());
        assertEquals(0, r.sealsChecked());
        assertNull(r.headSeq());
        assertThrows(IllegalArgumentException.class, () -> service.verify(T0.plusSeconds(10), T0));
    }

    @Test
    void aWindowedVerifyChecksOnlyOverlappingSealsAgainstTheirPredecessor() {
        fillLog(9);
        AuditSealService service = service(3, 0);
        sealEverything(service);
        Instant secondStart = seals.get(1).getFirstRowAt();

        AuditSealVerifier.Result r = service.verify(secondStart, null);
        assertTrue(r.ok(), r.reason());
        assertEquals(2, r.sealsChecked());
        assertEquals(6, r.rowsChecked());
    }

    @Test
    void exportCarriesSealsRowsKeysAndAlgorithm() {
        fillLog(7);
        AuditSealService service = service(3, 0);
        sealEverything(service);

        ExportBundle bundle = service.export(null, null, NOW);
        assertEquals(AuditSealDtos.EXPORT_FORMAT, bundle.format());
        assertEquals(3, bundle.seals().size());
        assertEquals(7, bundle.rows().size());
        assertEquals(1, bundle.keys().size());
        assertEquals("kid-1", bundle.keys().get(0).kid());
        assertEquals("Ed25519", bundle.keys().get(0).alg());
        assertFalse(bundle.truncated());
        assertNull(bundle.predecessor());
        assertEquals(AuditSealHasher.ALGORITHM, bundle.algorithm().get("id"));
        assertEquals(seals.get(0).getSealHash(), bundle.seals().get(0).sealHash());
        assertEquals(AuditSealHasher.toMicros(seals.get(0).getFirstRowAt()), bundle.seals().get(0).firstRowAtMicros());
        assertEquals(SealTestSupport.uuid(0).toString(), bundle.rows().get(0).id());
        assertEquals(AuditSealHasher.toMicros(log.get(0).getCreatedAt()), bundle.rows().get(0).createdAtMicros());
    }

    @Test
    void aWindowedExportNamesItsPredecessorSeal() {
        fillLog(9);
        AuditSealService service = service(3, 0);
        sealEverything(service);

        ExportBundle bundle = service.export(seals.get(1).getFirstRowAt(), null, NOW);
        assertEquals(2, bundle.seals().size());
        assertEquals(6, bundle.rows().size());
        assertNotNull(bundle.predecessor());
        assertEquals(1L, bundle.predecessor().seq());
        assertEquals(seals.get(0).getSealHash(), bundle.predecessor().sealHash());
    }

    @Test
    void purgedSealsAreExportedWithoutRowsAndFlaggedPurged() {
        fillLog(6);
        AuditSealService service = service(3, 0);
        sealEverything(service);
        ReflectionTestUtils.setField(seals.get(0), "purgedAt", NOW);
        log.subList(0, 3).clear(); // retention removed the first range

        ExportBundle bundle = service.export(null, null, NOW);
        assertEquals(2, bundle.seals().size());
        assertTrue(bundle.seals().get(0).purged());
        assertFalse(bundle.seals().get(1).purged());
        assertEquals(3, bundle.rows().size(), "only the live range ships rows");

        AuditSealVerifier.Result r = service.verify(null, null);
        assertTrue(r.ok(), r.reason());
        assertEquals(1, r.purgedSeals());
        assertEquals(3, r.rowsChecked());
    }

    @Test
    void markPurgedBeforeDelegatesToTheRepository() {
        Instant cutoff = T0.minusSeconds(5);
        when(sealRepository.markPurgedBefore(cutoff, NOW)).thenReturn(4);
        assertEquals(4, service(3, 0).markPurgedBefore(cutoff, NOW));
        verify(sealRepository).markPurgedBefore(cutoff, NOW);
    }

    @Test
    void listClampsPagingAndMapsToViews() {
        fillLog(3);
        AuditSealService service = service(3, 0);
        sealEverything(service);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        when(sealRepository.findAllByOrderBySeqDesc(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(seals.get(0)), PageRequest.of(0, 200), 1));

        AuditSealDtos.SealPage result = service.list(-5, 100_000);
        verify(sealRepository).findAllByOrderBySeqDesc(page.capture());
        assertEquals(0, page.getValue().getPageNumber());
        assertEquals(AuditSealService.MAX_LIST_PAGE_SIZE, page.getValue().getPageSize());
        assertEquals(1, result.items().size());
        assertTrue(result.items().get(0).signed());
        assertEquals(1L, result.total());
        assertEquals(1L, result.items().get(0).seq());
    }
}
