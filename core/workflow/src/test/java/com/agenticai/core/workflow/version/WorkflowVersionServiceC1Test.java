package com.agenticai.core.workflow.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.version.VersionLabel;
import com.agenticai.core.agents.version.VersionRollbackException;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowStatus;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The C1 verbs on {@link WorkflowVersionService}: publish (deduped snapshot of the draft), promote
 * (label move), rollback (PRODUCTION pointer moves back - never a new snapshot), run resolution
 * (label-first) and the details/graph diff.
 *
 * <p>The repository is an in-memory proxy fake rather than a Mockito mock: the inline mock maker
 * cannot instrument this interface's Spring Data hierarchy on JDK 24, and a fake that ANSWERS the
 * queries (rather than returning canned values) exercises the service's own label/dedupe logic more
 * honestly anyway - a regression in "at most one label holder" or "newest first" inside the service
 * would fail here, not hide behind a stubbed answer.
 */
class WorkflowVersionServiceC1Test {

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String GRAPH_A =
            "{\"nodes\":[{\"id\":\"n1\",\"type\":\"trigger\"}],\"edges\":[]}";
    private static final String GRAPH_B =
            "{\"nodes\":[{\"id\":\"n1\",\"type\":\"trigger\"},{\"id\":\"n2\",\"type\":\"agent\"}],\"edges\":[]}";

    /**
     * The rows the fake repository "persists" - a real store, so ordering/label invariants are
     * exercised.
     */
    private final List<WorkflowVersion> store = new ArrayList<>();

    /** Every repository method invoked, in order - the ordering assertions' datasource. */
    private final List<String> calls = new ArrayList<>();

    private WorkflowVersionService service;

    @BeforeEach
    void setUp() {
        service =
                new WorkflowVersionService(
                        fakeRepository(), com.agenticai.core.json.JsonMappers.plain());
        TenantContext.set(TenantContext.of("tenant-1", "user@example.com", Set.of()));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private WorkflowVersionRepository fakeRepository() {
        return (WorkflowVersionRepository)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {WorkflowVersionRepository.class},
                        (proxy, method, args) -> {
                            calls.add(method.getName());
                            return switch (method.getName()) {
                                case "save", "saveAndFlush" -> {
                                    WorkflowVersion v = persist((WorkflowVersion) args[0]);
                                    if (!store.contains(v)) {
                                        store.add(v);
                                    }
                                    yield v;
                                }
                                case "findFirstByWorkflowIdOrderByVersionNumberDesc" ->
                                        store.stream()
                                                .filter(v -> v.getWorkflowId().equals(args[0]))
                                                .max(
                                                        Comparator.comparingInt(
                                                                WorkflowVersion::getVersionNumber));
                                case "findByWorkflowIdAndVersionNumber" ->
                                        store.stream()
                                                .filter(
                                                        v ->
                                                                v.getWorkflowId().equals(args[0])
                                                                        && v.getVersionNumber()
                                                                                == (Integer)
                                                                                        args[1])
                                                .findFirst();
                                case "findByWorkflowIdAndLabel" ->
                                        store.stream()
                                                .filter(
                                                        v ->
                                                                v.getWorkflowId().equals(args[0])
                                                                        && v.getLabel() == args[1])
                                                .findFirst();
                                case "findByWorkflowIdOrderByVersionNumberDesc" ->
                                        store.stream()
                                                .filter(v -> v.getWorkflowId().equals(args[0]))
                                                .sorted(
                                                        Comparator.comparingInt(
                                                                        WorkflowVersion
                                                                                ::getVersionNumber)
                                                                .reversed())
                                                .toList();
                                case "latestVersionNumber" ->
                                        store.stream()
                                                .filter(v -> v.getWorkflowId().equals(args[0]))
                                                .map(WorkflowVersion::getVersionNumber)
                                                .max(Integer::compareTo)
                                                .orElse(null);
                                default -> defaultValue(method.getReturnType());
                            };
                        });
    }

    private static WorkflowVersion persist(WorkflowVersion version) {
        // The real id is @GeneratedValue(UUID); the fake mints it the same
        // way so id-equality checks in the service behave like production.
        if (version.getId() == null) {
            try {
                Field id = WorkflowVersion.class.getDeclaredField("id");
                id.setAccessible(true);
                id.set(version, UUID.randomUUID());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return version;
    }

    private static Object defaultValue(Class<?> type) {
        if (Optional.class.isAssignableFrom(type)) {
            return Optional.empty();
        }
        if (List.class.isAssignableFrom(type)) {
            return List.of();
        }
        return null;
    }

    private WorkflowVersion saved(
            UUID workflowId, int number, String graph, WorkflowStatus status) {
        WorkflowVersion v =
                persist(
                        WorkflowVersion.snapshot(
                                workflowId, number, graph, "{}", "wf", "d", status, null, null));
        store.add(v);
        return v;
    }

    private WorkflowDefinition definition(String graphJson, WorkflowStatus status) {
        WorkflowDefinition definition =
                new WorkflowDefinition("Order Flow", "desc", graphJson, status);
        // The id is @GeneratedValue on save; tests never hit a database, so
        // stamp it reflectively (same trick persist() uses for version ids).
        try {
            Field id = WorkflowDefinition.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(definition, WORKFLOW_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return definition;
    }

    // ------------------------------------------------------------ publish

    @Test
    void publishSnapshotsTheDraftAsTheNextOrdinal() {
        WorkflowVersion v =
                service.publish(definition(GRAPH_A, WorkflowStatus.ACTIVE), "first cut");

        assertEquals(1, v.getVersionNumber());
        assertEquals(GRAPH_A, v.getGraphJson());
        assertEquals("first cut", v.getChangeSummary());
        assertNotNull(v.getBundleHash());
        assertEquals(64, v.getBundleHash().length());
        // details_json captures schedule/limits state even for a default row
        assertNotNull(v.getDetailsJson());
        assertTrue(v.getDetailsJson().contains("scheduleEnabled"));
    }

    @Test
    void publishOfAnUnchangedBundleReturnsTheExistingRow() {
        WorkflowDefinition def = definition(GRAPH_A, WorkflowStatus.ACTIVE);
        WorkflowVersion first = service.publish(def, "v1");

        WorkflowVersion again = service.publish(def, "same thing twice");

        assertSame(first, again);
        assertEquals(1, store.size()); // dedupe: no padding row
    }

    @Test
    void publishIncrementsPastExistingOrdinals() {
        saved(WORKFLOW_ID, 4, GRAPH_A, WorkflowStatus.ACTIVE);
        WorkflowVersion v =
                service.publish(definition(GRAPH_B, WorkflowStatus.ACTIVE), "changed graph");
        assertEquals(5, v.getVersionNumber());
    }

    // -------------------------------------------------- auto-assign label

    @Test
    void autoModeAssignsProductionToTheNewActiveSnapshot() {
        // Feature-flag provider absent = auto mode: a save on an ACTIVE
        // workflow moves PRODUCTION to the fresh row, keeping "the labeled
        // version is what runs" true by construction.
        service.publish(definition(GRAPH_A, WorkflowStatus.ACTIVE), "v1");
        WorkflowVersion v2 = service.publish(definition(GRAPH_B, WorkflowStatus.ACTIVE), "v2");

        assertNull(store.get(0).getLabel());
        assertEquals(VersionLabel.PRODUCTION, v2.getLabel());
        // The clear+flush on the old holder ran BEFORE the new assignment.
        assertTrue(calls.indexOf("saveAndFlush") < calls.lastIndexOf("save"));
    }

    // ------------------------------------------------------------ promote

    @Test
    void promoteClearsThePreviousHolderBeforeAssigning() {
        WorkflowVersion current = saved(WORKFLOW_ID, 3, GRAPH_A, WorkflowStatus.ACTIVE);
        current.assignLabel(VersionLabel.PRODUCTION);
        WorkflowVersion target = saved(WORKFLOW_ID, 7, GRAPH_B, WorkflowStatus.ACTIVE);

        calls.clear();
        WorkflowVersion result = service.promote(WORKFLOW_ID, 7, VersionLabel.PRODUCTION);

        assertNull(current.getLabel());
        assertEquals(VersionLabel.PRODUCTION, result.getLabel());
        // saveAndFlush (the clear) precedes save (the assign) - the partial
        // unique index must never see two holders even within one tx.
        int clearAt = calls.indexOf("saveAndFlush");
        int assignAt = calls.lastIndexOf("save");
        assertTrue(clearAt >= 0 && clearAt < assignAt);
    }

    @Test
    void promoteIsIdempotentOnTheCurrentHolder() {
        WorkflowVersion holder = saved(WORKFLOW_ID, 7, GRAPH_A, WorkflowStatus.ACTIVE);
        holder.assignLabel(VersionLabel.PRODUCTION);

        calls.clear();
        service.promote(WORKFLOW_ID, 7, VersionLabel.PRODUCTION);

        assertTrue(calls.stream().noneMatch(c -> c.equals("save") || c.equals("saveAndFlush")));
    }

    // ----------------------------------------------------------- rollback

    @Test
    void rollbackMovesProductionBackOneVersion() {
        saved(WORKFLOW_ID, 9, GRAPH_B, WorkflowStatus.DRAFT); // newer, never promoted
        WorkflowVersion current = saved(WORKFLOW_ID, 7, GRAPH_A, WorkflowStatus.ACTIVE);
        current.assignLabel(VersionLabel.PRODUCTION);
        WorkflowVersion previous = saved(WORKFLOW_ID, 6, GRAPH_A, WorkflowStatus.ACTIVE);

        int storeSizeBefore = store.size();
        WorkflowVersion result = service.rollback(WORKFLOW_ID);

        assertNull(current.getLabel());
        assertEquals(VersionLabel.PRODUCTION, result.getLabel());
        assertEquals(6, result.getVersionNumber()); // below the HOLDER, not below the newest row
        assertEquals(storeSizeBefore, store.size()); // label move only - never a new snapshot
    }

    @Test
    void rollbackWithNothingEarlierIsAConflict() {
        WorkflowVersion current = saved(WORKFLOW_ID, 1, GRAPH_A, WorkflowStatus.ACTIVE);
        current.assignLabel(VersionLabel.PRODUCTION);

        assertThrows(VersionRollbackException.class, () -> service.rollback(WORKFLOW_ID));
        assertEquals(VersionLabel.PRODUCTION, current.getLabel()); // pointer untouched on failure
    }

    // ------------------------------------------- run resolution / pinning

    @Test
    void runResolutionPrefersTheProductionHolderOverTheNewestRow() {
        saved(WORKFLOW_ID, 9, GRAPH_B, WorkflowStatus.DRAFT);
        WorkflowVersion production = saved(WORKFLOW_ID, 7, GRAPH_A, WorkflowStatus.ACTIVE);
        production.assignLabel(VersionLabel.PRODUCTION);

        assertEquals(7, service.resolveRunVersion(WORKFLOW_ID).orElseThrow().getVersionNumber());
        assertEquals(Integer.valueOf(7), service.currentVersionNumber(WORKFLOW_ID));
        assertEquals(
                GRAPH_A, service.resolveRunGraphJson(definition(GRAPH_B, WorkflowStatus.ACTIVE)));
    }

    @Test
    void runResolutionFallsBackToNewestWhenNoLabelExists() {
        saved(WORKFLOW_ID, 4, GRAPH_B, WorkflowStatus.ACTIVE);
        assertEquals(
                GRAPH_B, service.resolveRunGraphJson(definition(GRAPH_A, WorkflowStatus.ACTIVE)));
    }

    @Test
    void promoteIsVisibleToTheNextResolution() {
        WorkflowVersion v3 = saved(WORKFLOW_ID, 3, GRAPH_A, WorkflowStatus.ACTIVE);
        WorkflowVersion v7 = saved(WORKFLOW_ID, 7, GRAPH_B, WorkflowStatus.ACTIVE);
        service.promote(WORKFLOW_ID, 3, VersionLabel.PRODUCTION);
        assertEquals(
                GRAPH_A, service.resolveRunGraphJson(definition(GRAPH_B, WorkflowStatus.ACTIVE)));

        service.promote(WORKFLOW_ID, 7, VersionLabel.PRODUCTION);
        assertNull(v3.getLabel());
        assertEquals(VersionLabel.PRODUCTION, v7.getLabel());
        assertEquals(
                GRAPH_B, service.resolveRunGraphJson(definition(GRAPH_A, WorkflowStatus.ACTIVE)));
    }

    // --------------------------------------------------------------- diff

    @Test
    @SuppressWarnings("unchecked")
    void diffVersionsSurfacesGraphAndDetailsChanges() {
        WorkflowVersion a =
                WorkflowVersion.snapshot(
                        WORKFLOW_ID,
                        3,
                        GRAPH_A,
                        "{\"scheduleEnabled\":false}",
                        "wf",
                        "d",
                        WorkflowStatus.DRAFT,
                        null,
                        null);
        WorkflowVersion b =
                WorkflowVersion.snapshot(
                        WORKFLOW_ID,
                        7,
                        GRAPH_B,
                        "{\"scheduleEnabled\":true}",
                        "wf2",
                        "d2",
                        WorkflowStatus.ACTIVE,
                        null,
                        null);

        Map<String, Object> diff = service.diffVersions(a, b);

        assertEquals(true, diff.get("nameChanged"));
        assertEquals(true, diff.get("statusChanged"));
        Map<String, Object> graph = (Map<String, Object>) diff.get("graph");
        assertEquals(1, ((List<?>) graph.get("addedNodes")).size());
        List<Map<String, Object>> details = (List<Map<String, Object>>) diff.get("details");
        assertEquals(1, details.size());
        assertEquals("scheduleEnabled", details.get(0).get("field"));
        assertEquals("true", details.get(0).get("to"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void diffVersionsToleratesLegacyRowsWithNullDetails() {
        WorkflowVersion a =
                WorkflowVersion.snapshot(
                        WORKFLOW_ID,
                        1,
                        GRAPH_A,
                        null,
                        "wf",
                        "d",
                        WorkflowStatus.ACTIVE,
                        null,
                        null);
        WorkflowVersion b =
                WorkflowVersion.snapshot(
                        WORKFLOW_ID,
                        2,
                        GRAPH_A,
                        null,
                        "wf",
                        "d",
                        WorkflowStatus.ACTIVE,
                        null,
                        null);

        Map<String, Object> diff = service.diffVersions(a, b);

        assertTrue(((List<?>) diff.get("details")).isEmpty());
        assertEquals(false, diff.get("nameChanged"));
    }
}
