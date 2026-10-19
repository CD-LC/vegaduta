package com.agenticai.core.quality.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.quality.calibration.JudgeCalibration.LabeledPair;
import com.agenticai.core.quality.calibration.JudgeCalibrationService.CalibrationResponse;
import com.agenticai.core.quality.calibration.JudgeCalibrationSetService.ItemInput;
import com.agenticai.core.quality.calibration.JudgeCalibrationSetService.JudgeTrust;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

/** Stored calibration sets over the in-memory store, the judge mocked: no Redis, no LLM, no Spring context. */
class JudgeCalibrationSetServiceTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID OTHER_AGENT = UUID.randomUUID();

    private InMemoryJudgeCalibrationStore store;
    private JudgeCalibrationService judge;
    private JudgeCalibrationSetService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant-1", "tester", Set.of()));
        store = new InMemoryJudgeCalibrationStore();
        judge = mock(JudgeCalibrationService.class);
        when(judge.isEnabled()).thenReturn(true);
        service = new JudgeCalibrationSetService(store, judge);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- helpers ------------------------------------------------------

    private static List<ItemInput> rows(int n) {
        List<ItemInput> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new ItemInput("q" + i, "good " + i, "bad " + i, i % 2 == 0 ? "A" : "B", null));
        }
        return out;
    }

    private static JudgeCalibrationSet setOf(UUID agent) {
        return new JudgeCalibrationSet(agent, "my set", "tester");
    }

    private JudgeCalibrationSet existingSet(UUID agent) {
        return store.saveSet(setOf(agent));
    }

    private List<JudgeCalibrationItem> storedItems(UUID setId, int n, String humanLabel) {
        List<JudgeCalibrationItem> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new JudgeCalibrationItem(setId, i, "q" + i, "a" + i, "b" + i, humanLabel, null));
        }
        return store.saveItems(out);
    }

    private static int status(ResponseStatusException e) {
        return e.getStatusCode().value();
    }

    /** A response computed by the real statistics from a scripted judge, as runPairs would produce it. */
    private static CalibrationResponse responseFor(List<LabeledPair> pairs, JudgeCalibration.PairwiseJudge judge) {
        JudgeCalibration.Report report = JudgeCalibration.evaluate(pairs, judge);
        return new CalibrationResponse("OPENAI/judge", "OPENAI/agent", null, report, "m");
    }

    // ---- flag off: every operation is a 404 ---------------------------

    @Test
    void everyOperationIs404WhenCalibrationIsDisabled() {
        when(judge.isEnabled()).thenReturn(false);
        UUID id = UUID.randomUUID();

        assertEquals(404, status(assertThrows(ResponseStatusException.class,
            () -> service.createSet(AGENT, "n", rows(3)))));
        assertEquals(404, status(assertThrows(ResponseStatusException.class, () -> service.listSets(AGENT))));
        assertEquals(404, status(assertThrows(ResponseStatusException.class, () -> service.requireSet(AGENT, id))));
        assertEquals(404, status(assertThrows(ResponseStatusException.class,
            () -> service.addItems(AGENT, id, rows(1)))));
        assertEquals(404, status(assertThrows(ResponseStatusException.class,
            () -> service.deleteItem(AGENT, id, id))));
        assertEquals(404, status(assertThrows(ResponseStatusException.class, () -> service.deleteSet(AGENT, id))));
        assertEquals(404, status(assertThrows(ResponseStatusException.class,
            () -> service.calibrateSet(AGENT, id))));
        assertEquals(404, status(assertThrows(ResponseStatusException.class,
            () -> service.history(AGENT, null, null))));
        // nothing was written
        assertTrue(store.listSets(AGENT).isEmpty());
        assertTrue(store.resultsForAgent(AGENT, 10).isEmpty());
    }

    @Test
    void flagOffNeverTouchesTheStoreAtAll() {
        when(judge.isEnabled()).thenReturn(false);
        JudgeCalibrationStore spyStore = mock(JudgeCalibrationStore.class);
        JudgeCalibrationSetService off = new JudgeCalibrationSetService(spyStore, judge);

        assertThrows(ResponseStatusException.class, () -> off.createSet(AGENT, "n", rows(1)));
        assertThrows(ResponseStatusException.class, () -> off.calibrateSet(AGENT, UUID.randomUUID()));
        assertTrue(off.trustFor("OPENAI/judge").isEmpty());
        verifyNoInteractions(spyStore);
        verify(judge, never()).runPairs(any(), any());
    }

    // ---- create / items ------------------------------------------------

    @Test
    void createsASetWithNormalisedItemsAndPositions() {
        List<ItemInput> in = List.of(
            new ItemInput("q1", "a1", "b1", " a ", "because"),
            new ItemInput("q2", "a2", "b2", "b", null));

        JudgeCalibrationSet set = service.createSet(AGENT, "  Support tone  ", in);

        assertEquals("Support tone", set.getName());
        assertEquals(AGENT, set.getAgentId());
        assertEquals("tester", set.getCreatedBy());
        List<JudgeCalibrationItem> stored = store.listItems(set.getId());
        assertEquals(2, stored.size());
        assertEquals("A", stored.get(0).getHumanLabel());
        assertEquals("B", stored.get(1).getHumanLabel());
        assertEquals(0, stored.get(0).getPosition());
        assertEquals(1, stored.get(1).getPosition());
        assertEquals("because", stored.get(0).getRationale());
    }

    @Test
    void createRejectsABlankNameAndBadRowsWith400s() {
        assertThrows(IllegalArgumentException.class, () -> service.createSet(AGENT, "  ", rows(1)));
        assertThrows(IllegalArgumentException.class, () -> service.createSet(AGENT, null, rows(1)));
        assertThrows(IllegalArgumentException.class,
            () -> service.createSet(AGENT, "x".repeat(256), rows(1)));
        IllegalArgumentException badLabel = assertThrows(IllegalArgumentException.class, () -> service.createSet(
            AGENT, "n", List.of(new ItemInput("q", "a", "b", "C", null))));
        assertTrue(badLabel.getMessage().contains("Row 1"));
        assertThrows(IllegalArgumentException.class, () -> service.createSet(
            AGENT, "n", List.of(new ItemInput("q", "a", " ", "A", null))));
        assertThrows(IllegalArgumentException.class, () -> service.createSet(
            AGENT, "n", List.of(new ItemInput("", "a", "b", "A", null))));
        assertThrows(IllegalArgumentException.class, () -> service.createSet(
            AGENT, "n", List.of(new ItemInput("q", "a".repeat(6001), "b", "A", null))));
        assertThrows(IllegalArgumentException.class, () -> service.createSet(
            AGENT, "n", List.of(new ItemInput("q", "a", "b", "A", "r".repeat(2001)))));
        assertTrue(store.listSets(AGENT).isEmpty(), "nothing is stored when validation fails");
    }

    @Test
    void createWithNoItemsIsAllowedSoARowsCanBePastedLater() {
        JudgeCalibrationSet set = service.createSet(AGENT, "empty for now", null);
        assertNotNull(set);
        assertEquals(0, store.countItems(set.getId()));
        assertEquals(1, store.listSets(AGENT).size());
    }

    @Test
    void aSetCannotExceedTheConfiguredCeiling() {
        service.setMaxExamples = 5;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.createSet(AGENT, "big", rows(6)));
        assertTrue(e.getMessage().contains("set-max-examples"));

        JudgeCalibrationSet set = existingSet(AGENT);
        storedItems(set.getId(), 4, "A");
        assertThrows(IllegalArgumentException.class, () -> service.addItems(AGENT, set.getId(), rows(2)));
        assertEquals(4, store.countItems(set.getId()));
        // exactly filling it is fine
        service.addItems(AGENT, set.getId(), rows(1));
        assertEquals(5, store.countItems(set.getId()));
        assertEquals(4, store.listItems(set.getId()).get(4).getPosition(), "appended after the existing items");
    }

    @Test
    void addItemsRequiresRows() {
        JudgeCalibrationSet set = existingSet(AGENT);
        assertThrows(IllegalArgumentException.class, () -> service.addItems(AGENT, set.getId(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.addItems(AGENT, set.getId(), null));
    }

    // ---- tenant / agent isolation -------------------------------------

    @Test
    void aSetOfAnotherAgentIsA404NotA403OnEveryRoute() {
        JudgeCalibrationSet foreign = existingSet(OTHER_AGENT);
        UUID id = foreign.getId();

        assertEquals(404, status(assertThrows(ResponseStatusException.class, () -> service.requireSet(AGENT, id))));
        assertEquals(404, status(assertThrows(ResponseStatusException.class,
            () -> service.renameSet(AGENT, id, "x"))));
        assertEquals(404, status(assertThrows(ResponseStatusException.class,
            () -> service.addItems(AGENT, id, rows(1)))));
        assertEquals(404, status(assertThrows(ResponseStatusException.class, () -> service.deleteSet(AGENT, id))));
        assertEquals(404, status(assertThrows(ResponseStatusException.class,
            () -> service.calibrateSet(AGENT, id))));
        assertEquals(404, status(assertThrows(ResponseStatusException.class,
            () -> service.history(AGENT, id, 5))));
        assertTrue(store.findSet(id).isPresent(), "the foreign set was not deleted");
        assertEquals("my set", store.findSet(id).orElseThrow().getName(), "nor renamed");
        verify(judge, never()).runPairs(any(), any());
    }

    @Test
    void aSetOfAnotherTenantIsInvisible() {
        JudgeCalibrationSet mine = service.createSet(AGENT, "tenant one set", rows(2));

        TenantContext.set(TenantContext.of("tenant-2", "other", Set.of()));
        assertEquals(404, status(assertThrows(ResponseStatusException.class,
            () -> service.requireSet(AGENT, mine.getId()))));
        assertTrue(service.listSets(AGENT).isEmpty());
        assertEquals(0, store.countItems(mine.getId()));
        assertTrue(service.history(AGENT, null, null).isEmpty());

        TenantContext.set(TenantContext.of("tenant-1", "tester", Set.of()));
        assertEquals(1, service.listSets(AGENT).size());
    }

    @Test
    void aMissingSetIsA404() {
        UUID id = UUID.randomUUID();
        assertEquals(404, status(assertThrows(ResponseStatusException.class, () -> service.requireSet(AGENT, id))));
    }

    @Test
    void deletingAnItemOfAnotherSetIsA404() {
        JudgeCalibrationSet set = existingSet(AGENT);
        JudgeCalibrationSet otherSet = existingSet(AGENT);
        JudgeCalibrationItem foreignItem = storedItems(otherSet.getId(), 1, "A").get(0);

        assertEquals(404, status(assertThrows(ResponseStatusException.class,
            () -> service.deleteItem(AGENT, set.getId(), foreignItem.getId()))));
        assertEquals(1, store.countItems(otherSet.getId()));

        JudgeCalibrationItem own = storedItems(set.getId(), 1, "A").get(0);
        service.deleteItem(AGENT, set.getId(), own.getId());
        assertEquals(0, store.countItems(set.getId()));
    }

    @Test
    void renameAndDeleteWorkForTheOwningAgentAndDeleteCascades() {
        JudgeCalibrationSet set = existingSet(AGENT);
        storedItems(set.getId(), 2, "A");
        store.saveResult(resultFor(set.getId(), AGENT, 0.7));
        assertEquals("renamed", service.renameSet(AGENT, set.getId(), " renamed ").getName());
        assertThrows(IllegalArgumentException.class, () -> service.renameSet(AGENT, set.getId(), ""));

        service.deleteSet(AGENT, set.getId());

        assertTrue(store.findSet(set.getId()).isEmpty());
        assertEquals(0, store.countItems(set.getId()));
        assertTrue(store.resultsForSet(set.getId(), 10).isEmpty(), "history goes with the set");
    }

    // ---- minimum-n guard ----------------------------------------------

    @Test
    void aSetBelowTheMinimumCannotBeRun() {
        JudgeCalibrationSet set = existingSet(AGENT);
        storedItems(set.getId(), 19, "A");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.calibrateSet(AGENT, set.getId()));

        assertTrue(e.getMessage().contains("19"));
        assertTrue(e.getMessage().contains("at least 20"));
        verify(judge, never()).runPairs(any(), any());
        assertTrue(store.resultsForSet(set.getId(), 10).isEmpty());
    }

    @Test
    void theMinimumIsConfigurable() {
        service.setMinExamples = 5;
        JudgeCalibrationSet set = existingSet(AGENT);
        storedItems(set.getId(), 4, "A");
        assertThrows(IllegalArgumentException.class, () -> service.calibrateSet(AGENT, set.getId()));

        storedItems(set.getId(), 1, "A");
        when(judge.runPairs(any(), any())).thenReturn(
            responseFor(List.of(new LabeledPair("x", "q", "a", "b", "A")), (p, f, s) -> 1));
        service.calibrateSet(AGENT, set.getId());
        verify(judge).runPairs(any(), any());
    }

    @Test
    void aSetAboveTheSynchronousCeilingCannotBeRun() {
        service.setMaxExamples = 25;
        JudgeCalibrationSet set = existingSet(AGENT);
        storedItems(set.getId(), 26, "A");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.calibrateSet(AGENT, set.getId()));
        assertTrue(e.getMessage().contains("at most 25"));
    }

    @Test
    void anItemWithoutOutputBCannotBeRun() {
        service.setMinExamples = 1;
        JudgeCalibrationSet set = existingSet(AGENT);
        storedItems(set.getId(), 1, "A");
        store.saveItems(List.of(new JudgeCalibrationItem(set.getId(), 1, "q", "a", null, "A", null)));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.calibrateSet(AGENT, set.getId()));
        assertTrue(e.getMessage().contains("Item 2"));
    }

    // ---- run + persistence --------------------------------------------

    @Test
    void aRunIsPersistedWithItsStatisticsAndReturnedWithTheRecordIds() {
        JudgeCalibrationSet set = existingSet(AGENT);
        storedItems(set.getId(), 20, "A");
        // The judge prefers whichever answer is labelled A in the ORIGINAL order, in both orders: consistent.
        when(judge.runPairs(any(), any())).thenAnswer(inv -> {
            List<LabeledPair> pairs = inv.getArgument(1);
            return responseFor(pairs, (prompt, first, second) -> first.startsWith("a") ? 1 : 2);
        });

        CalibrationResponse response = service.calibrateSet(AGENT, set.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LabeledPair>> pairsCaptor = ArgumentCaptor.forClass(List.class);
        verify(judge).runPairs(any(), pairsCaptor.capture());
        assertEquals(20, pairsCaptor.getValue().size());
        assertEquals("q0", pairsCaptor.getValue().get(0).prompt());
        assertEquals("a0", pairsCaptor.getValue().get(0).answerA());
        assertEquals("b0", pairsCaptor.getValue().get(0).answerB());
        assertEquals("A", pairsCaptor.getValue().get(0).humanPreferred());

        JudgeCalibrationResult r = store.resultsForSet(set.getId(), 10).get(0);
        assertEquals(set.getId(), r.getSetId());
        assertEquals(AGENT, r.getAgentId());
        assertEquals("OPENAI/judge", r.getJudgeModel());
        assertEquals("OPENAI/agent", r.getAgentModel());
        assertEquals(20, r.getN());
        assertEquals(20, r.getUsable());
        assertEquals(20, r.getConsistent());
        assertEquals(1.0, r.getOrderSwapConsistency());
        assertEquals(1.0, r.getRawAgreement());
        assertEquals("tester", r.getCreatedBy());
        assertEquals(set.getId(), response.calibrationSetId());
        assertEquals(r.getId(), response.resultId());
        assertNotNull(response.recordedAt());
        // kappa is undefined here (every human label is A): null, never 0 and never 1.
        assertNull(r.getKappa());
    }

    @Test
    void allSameLabelsGiveAnUndefinedKappaThatIsRecordedAsNullAndNotTrusted() {
        JudgeCalibrationSet set = existingSet(AGENT);
        storedItems(set.getId(), 20, "A");
        when(judge.runPairs(any(), any())).thenAnswer(inv ->
            responseFor(inv.getArgument(1), (prompt, first, second) -> first.startsWith("a") ? 1 : 2));
        service.calibrateSet(AGENT, set.getId());

        JudgeCalibrationResult saved = store.resultsForSet(set.getId(), 10).get(0);
        assertNull(saved.getKappa());
        JudgeTrust trust = JudgeCalibrationSetService.classify(saved, 0.6);
        assertEquals("UNCALIBRATED", trust.status());
        assertNull(trust.kappa());
        assertEquals(20, trust.n());
    }

    @Test
    void aMixedLabelPerfectJudgeRecordsKappaOne() {
        JudgeCalibrationSet set = existingSet(AGENT);
        List<JudgeCalibrationItem> stored = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // humans alternate A/B; in 'B' items the better answer is shown as answerB
            stored.add(new JudgeCalibrationItem(set.getId(), i, "q" + i,
                i % 2 == 0 ? "good" : "bad", i % 2 == 0 ? "bad" : "good", i % 2 == 0 ? "A" : "B", null));
        }
        store.saveItems(stored);
        when(judge.runPairs(any(), any())).thenAnswer(inv ->
            responseFor(inv.getArgument(1), (prompt, first, second) -> first.equals("good") ? 1 : 2));

        service.calibrateSet(AGENT, set.getId());

        JudgeCalibrationResult saved = store.resultsForSet(set.getId(), 10).get(0);
        assertEquals(1.0, saved.getKappa(), 1e-12);
        assertEquals("OK", JudgeCalibrationSetService.classify(saved, 0.6).status());
    }

    @Test
    void aRunWithNoUsableVerdictsIsReturnedButNotRecorded() {
        JudgeCalibrationSet set = existingSet(AGENT);
        storedItems(set.getId(), 20, "A");
        when(judge.runPairs(any(), any())).thenAnswer(inv ->
            responseFor(inv.getArgument(1), (prompt, first, second) -> {
                throw new IllegalStateException("provider down");
            }));

        CalibrationResponse response = service.calibrateSet(AGENT, set.getId());

        assertEquals(0, response.report().usable());
        assertNull(response.resultId());
        assertNull(response.recordedAt());
        assertTrue(store.resultsForSet(set.getId(), 10).isEmpty());
    }

    // ---- history -------------------------------------------------------

    private static JudgeCalibrationResult resultFor(UUID setId, UUID agent, Double kappa) {
        return new JudgeCalibrationResult(setId, agent, "OPENAI/judge", null, 20, 20, 20, kappa, 1.0, 1.0,
            "x", null, "tester");
    }

    @Test
    void historyClampsTheLimitAndScopesBySetOrAgent() throws Exception {
        JudgeCalibrationSet set = existingSet(AGENT);
        JudgeCalibrationSet other = existingSet(AGENT);
        for (int i = 0; i < 120; i++) {
            store.saveResult(resultFor(i % 2 == 0 ? set.getId() : other.getId(), AGENT, 0.5));
        }
        store.saveResult(resultFor(UUID.randomUUID(), OTHER_AGENT, 0.9));

        assertEquals(20, service.history(AGENT, null, null).size(), "default limit");
        assertEquals(100, service.history(AGENT, null, 100000).size(), "clamped to the maximum");
        assertEquals(1, service.history(AGENT, null, -3).size(), "clamped to at least one");
        assertEquals(7, service.history(AGENT, set.getId(), 7).size());
        assertTrue(service.history(AGENT, set.getId(), 100).stream().allMatch(r -> r.getSetId().equals(set.getId())));
    }

    @Test
    void historyIsNewestFirst() throws Exception {
        JudgeCalibrationSet set = existingSet(AGENT);
        JudgeCalibrationResult first = store.saveResult(resultFor(set.getId(), AGENT, 0.9));
        Thread.sleep(5);
        JudgeCalibrationResult second = store.saveResult(resultFor(set.getId(), AGENT, 0.4));

        List<JudgeCalibrationResult> history = service.history(AGENT, set.getId(), 10);

        assertEquals(second.getId(), history.get(0).getId());
        assertEquals(first.getId(), history.get(1).getId());
    }

    // ---- judge trust ---------------------------------------------------

    private static JudgeCalibrationResult resultWith(Double kappa, int n) {
        return new JudgeCalibrationResult(UUID.randomUUID(), AGENT, "OPENAI/judge", null, n, n, n, kappa, 1.0, 1.0,
            "x", null, "tester");
    }

    @Test
    void classifyMapsKappaToStatusAtTheThreshold() {
        assertEquals("UNCALIBRATED", JudgeCalibrationSetService.classify(null, 0.6).status());
        assertNull(JudgeCalibrationSetService.classify(null, 0.6).n());
        assertEquals("LOW", JudgeCalibrationSetService.classify(resultWith(0.59, 30), 0.6).status());
        assertEquals("OK", JudgeCalibrationSetService.classify(resultWith(0.6, 30), 0.6).status());
        assertEquals("OK", JudgeCalibrationSetService.classify(resultWith(0.95, 30), 0.6).status());
        assertEquals("LOW", JudgeCalibrationSetService.classify(resultWith(-0.2, 30), 0.6).status());
        JudgeTrust t = JudgeCalibrationSetService.classify(resultWith(0.7, 25), 0.6);
        assertEquals(0.7, t.kappa());
        assertEquals(25, t.n());
    }

    @Test
    void trustForUsesTheLatestResultOfThatJudgeModelAndTheConfiguredThreshold() throws Exception {
        store.saveResult(resultWith(0.2, 30));
        Thread.sleep(5);
        store.saveResult(resultWith(0.7, 30));
        assertEquals("OK", service.trustFor("OPENAI/judge").orElseThrow().status(), "the latest result wins");

        service.kappaThreshold = 0.8;
        assertEquals("LOW", service.trustFor("OPENAI/judge").orElseThrow().status());

        JudgeTrust none = service.trustFor("OPENAI/other").orElseThrow();
        assertEquals("UNCALIBRATED", none.status());
        assertNull(none.kappa());
        assertNull(none.n());
    }

    @Test
    void trustIsPerTenant() {
        store.saveResult(resultWith(0.9, 30));
        assertEquals("OK", service.trustFor("OPENAI/judge").orElseThrow().status());

        TenantContext.set(TenantContext.of("tenant-2", "other", Set.of()));
        assertEquals("UNCALIBRATED", service.trustFor("OPENAI/judge").orElseThrow().status());
    }

    @Test
    void trustForIsEmptyWithoutAJudgeModelAndSwallowsLookupFailures() {
        assertTrue(service.trustFor(null).isEmpty());
        assertTrue(service.trustFor("  ").isEmpty());

        JudgeCalibrationStore broken = mock(JudgeCalibrationStore.class);
        when(broken.latestForJudgeModel(anyString())).thenThrow(new IllegalStateException("redis down"));
        JudgeCalibrationSetService failing = new JudgeCalibrationSetService(broken, judge);
        assertFalse(failing.trustFor("OPENAI/judge").isPresent());
    }

    @Test
    void normalizeLabelAcceptsOnlyAorB() {
        assertEquals("A", JudgeCalibrationSetService.normalizeLabel("a"));
        assertEquals("B", JudgeCalibrationSetService.normalizeLabel(" B "));
        assertNull(JudgeCalibrationSetService.normalizeLabel("C"));
        assertNull(JudgeCalibrationSetService.normalizeLabel(""));
        assertNull(JudgeCalibrationSetService.normalizeLabel(null));
    }
}
