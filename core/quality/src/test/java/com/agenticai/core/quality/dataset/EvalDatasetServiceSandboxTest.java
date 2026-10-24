package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A dataset may pin its replay mode (full pipeline or quick text-only) or pin nothing and follow the workspace's
 * default, which is the full pipeline as shipped. The sandbox auto-approval needs a dataset that does not pin quick
 * mode and a tenant the operator declared non-production, and a dataset that never used it keeps the hash its runs carry.
 */
class EvalDatasetServiceSandboxTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final String TENANT = "tenant-1";

    private EvalDatasetRepository datasets;
    private EvalDatasetService service;

    @BeforeEach
    void setUp() {
        datasets = mock(EvalDatasetRepository.class);
        when(datasets.save(any(EvalDataset.class))).thenAnswer(inv -> inv.getArgument(0));
        @SuppressWarnings("unchecked")
        ObjectProvider<com.agenticai.core.chat.LlmCallLogService> logs = mock(ObjectProvider.class);
        service = new EvalDatasetService(
            datasets, mock(EvalDatasetCaseRepository.class), mock(EvalRunRepository.class), logs,
            mock(ApplicationContext.class), "", "", "", "");
        TenantContext.set(TenantContext.of(TENANT, "tester", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void policy(EvalMode defaultMode, boolean tenantDeclared) {
        service.sandboxPolicy = EvalSandboxPolicy.of(
            defaultMode, tenantDeclared ? Set.of(TENANT) : Set.of(), "eval-auto-approve", List.of());
    }

    @Test
    void aDatasetCreatedWithoutAChoicePinsNoModeAndSoFollowsTheDefault() {
        EvalDataset created = service.createDataset(AGENT_ID, "d", null, null, null);

        assertNull(created.getToolExecutionEnabled(), "no pin: the run takes the workspace default");
        assertTrue(created.pinnedMode().isEmpty());
        assertFalse(created.isAutoApproveTools());
    }

    @Test
    void theShippedPolicyEqualsTheDocumentedDefaults() {
        EvalSandboxPolicy shipped = EvalSandboxPolicy.defaults();

        assertEquals(EvalMode.FULL, shipped.defaultMode(), "evals measure the production pipeline unless told otherwise");
        assertTrue(shipped.readOnlyTools().isEmpty(), "no unclassified tool is vouched for by default");
        assertFalse(shipped.autoApproveAllowedFor(TENANT), "no tenant may auto-approve until the operator lists it");
        assertEquals("eval-auto-approve", shipped.actor());
        assertTrue(shipped.excludedTools().containsAll(
            List.of("local_shell", "coding_task", "schedule_task", "a2a_call_agent", "*payment*", "*transfer*")));
    }

    @Test
    void anExplicitChoiceIsPinnedWhateverTheDefaultIs() {
        policy(EvalMode.QUICK, false);

        EvalDataset full = service.createDataset(AGENT_ID, "d", null, null, true);
        EvalDataset quick = service.createDataset(AGENT_ID, "d2", null, null, false);
        EvalDataset none = service.createDataset(AGENT_ID, "d3", null, null, null);

        assertEquals(EvalMode.FULL, full.pinnedMode().orElseThrow());
        assertEquals(EvalMode.QUICK, quick.pinnedMode().orElseThrow());
        assertTrue(none.pinnedMode().isEmpty(), "the default is applied when a run starts, not frozen into the dataset");
    }

    @Test
    void autoApprovalIsRefusedForATenantTheOperatorDidNotDeclareNonProduction() {
        policy(EvalMode.FULL, false);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> service.createDataset(AGENT_ID, "d", null, null, true, true));

        assertTrue(refused.getMessage().contains("app.quality.eval.auto-approve.tenant-ids"), refused.getMessage());
        verify(datasets, never()).save(any(EvalDataset.class));
    }

    @Test
    void autoApprovalOnADatasetPinnedToQuickModeIsRefused() {
        policy(EvalMode.FULL, true);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> service.createDataset(AGENT_ID, "d", null, null, false, true));

        assertTrue(refused.getMessage().contains("quick"), refused.getMessage());
    }

    @Test
    void autoApprovalOnADatasetThatPinsNothingIsAllowedBecauseItFollowsTheFullDefault() {
        policy(EvalMode.FULL, true);

        EvalDataset created = service.createDataset(AGENT_ID, "d", null, null, null, true);

        assertTrue(created.isAutoApproveTools());
        assertNull(created.getToolExecutionEnabled());
    }

    @Test
    void autoApprovalIsAllowedForADeclaredTenantAndChangesTheContentHash() {
        policy(EvalMode.FULL, true);

        EvalDataset plain = service.createDataset(AGENT_ID, "d", null, null, true, false);
        EvalDataset approving = service.createDataset(AGENT_ID, "d", null, null, true, true);

        assertTrue(approving.isAutoApproveTools());
        assertNotEquals(plain.getContentHash(), approving.getContentHash(),
            "auto-approving changes what a run measures, so a run of the other setting must not satisfy a gate");
    }

    @Test
    void aDatasetThatNeverUsedAutoApprovalKeepsTheHashItsRunsCarry() {
        EvalDataset legacy = new EvalDataset(AGENT_ID, "d", null, 0.7, true, "u");
        EvalDataset explicit = new EvalDataset(AGENT_ID, "d", null, 0.7, true, false, "u");

        assertEquals(DatasetContentHash.of(legacy, List.of()), DatasetContentHash.of(explicit, List.of()));
        // And the hash of a tool-less dataset ignores a stray auto-approval flag: it can have no effect.
        EvalDataset toolless = new EvalDataset(AGENT_ID, "d", null, 0.7, false, "u");
        EvalDataset stray = new EvalDataset(AGENT_ID, "d", null, 0.7, false, true, "u");
        assertEquals(DatasetContentHash.of(toolless, List.of()), DatasetContentHash.of(stray, List.of()));
    }

    @Test
    void aDatasetThatHeldTheOldFalseDefaultAndOneThatPinsNothingHashAlike() {
        // V670 turned every stored FALSE into NULL; no run or gate binding of those datasets may go stale.
        EvalDataset oldDefault = new EvalDataset(AGENT_ID, "d", null, 0.7, false, "u");
        EvalDataset nothingPinned = new EvalDataset(AGENT_ID, "d", null, 0.7, (Boolean) null, "u");

        assertEquals(DatasetContentHash.of(oldDefault, List.of()), DatasetContentHash.of(nothingPinned, List.of()));
        assertNotEquals(DatasetContentHash.of(nothingPinned, List.of()),
            DatasetContentHash.of(new EvalDataset(AGENT_ID, "d", null, 0.7, true, "u"), List.of()));
    }

    @Test
    void pinningQuickModeAlsoTurnsAutoApprovalOff() {
        policy(EvalMode.FULL, true);
        EvalDataset approving = new EvalDataset(AGENT_ID, "d", null, 0.7, true, true, "u");
        ReflectionTestUtils.setField(approving, "id", DATASET_ID);
        when(datasets.findById(DATASET_ID)).thenReturn(Optional.of(approving));

        EvalDataset updated = service.updateDataset(AGENT_ID, DATASET_ID, "d", null, null, false, null);

        assertEquals(EvalMode.QUICK, updated.pinnedMode().orElseThrow());
        assertFalse(updated.isAutoApproveTools(), "auto-approval cannot outlive the tools it approves");
    }

    @Test
    void clearingThePinMakesTheDatasetFollowTheDefaultAgainAndAnUnchangedUpdateKeepsIt() {
        policy(EvalMode.FULL, false);
        EvalDataset pinned = new EvalDataset(AGENT_ID, "d", null, 0.7, false, "u");
        ReflectionTestUtils.setField(pinned, "id", DATASET_ID);
        when(datasets.findById(DATASET_ID)).thenReturn(Optional.of(pinned));

        EvalDataset renamed = service.updateDataset(AGENT_ID, DATASET_ID, "renamed", null, null, null, null);
        assertEquals(EvalMode.QUICK, renamed.pinnedMode().orElseThrow(), "null means unchanged, never cleared");

        EvalDataset cleared = service.updateDataset(AGENT_ID, DATASET_ID, "renamed", null, null, null, null, true);
        assertNull(cleared.getToolExecutionEnabled());
        assertTrue(cleared.pinnedMode().isEmpty());
    }

    @Test
    void anUpdateThatLeavesAutoApprovalAloneKeepsItEvenIfTheTenantLeftTheList() {
        policy(EvalMode.FULL, false); // the tenant is no longer declared
        EvalDataset approving = new EvalDataset(AGENT_ID, "d", null, 0.7, true, true, "u");
        ReflectionTestUtils.setField(approving, "id", DATASET_ID);
        when(datasets.findById(DATASET_ID)).thenReturn(Optional.of(approving));

        EvalDataset updated = service.updateDataset(AGENT_ID, DATASET_ID, "renamed", null, null, null, null);

        // Renaming must not be blocked; the trigger and every replay are where the list is enforced.
        assertTrue(updated.isAutoApproveTools());
        assertEquals("renamed", updated.getName());
    }
}
