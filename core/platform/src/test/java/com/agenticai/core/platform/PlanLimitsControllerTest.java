package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlanLimitsService;
import com.agenticai.core.tenancy.PlanLimitsService.ResolvedPlanLimits;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code PUT /api/platform/plan-limits/{plan}} replaces a plan's caps
 * WHOLESALE, and the write fans out to every tenant on that plan - so a
 * silent argument mix-up here mis-caps (or silently revokes an entitlement
 * for) whole tiers of tenants at once, with no error and no audit trail.
 * Plain unit tests calling the controller method directly, no MockMvc/web
 * layer - the established convention in this repo's controller tests (see
 * {@code TenantSettingsControllerTest} in core/tenancy for the same note).
 *
 * <p>The two regressions this class exists to catch:
 * <ul>
 *   <li>The entitlement fields are nullable on purpose (boxed - see {@link
 *   PlanLimitsUpdateRequest}'s own comment): an OMITTED value must resolve
 *   to the plan's CURRENT stored value via {@code getLimitsForPlan}, never
 *   coerce to {@code false}. A coercion bug would revoke an entitlement for
 *   every tenant on the plan the next time an admin saved any unrelated
 *   field - the exact failure mode the boxed Booleans were introduced to
 *   prevent (V180 rollout, stale web-admin drafts).</li>
 *   <li>All fifteen values must reach {@link PlanLimitsService#update} in
 *   the declared positional order. Four ints and eleven booleans in a row
 *   are type-identical to the compiler - a {@code maxAgents}/{@code
 *   maxWorkflows} or {@code byokAllowed}/{@code whiteLabel} transposition
 *   compiles clean and silently mis-caps every tenant on the plan.</li>
 * </ul>
 */
class PlanLimitsControllerTest {

    // Shorthand so the 15-field request literals below stay readable: every
    // boxed entitlement the tests don't specifically exercise is passed as an
    // explicit value (fast path, no getLimitsForPlan read) unless the test's
    // whole point is the omitted-field resolution.
    private static final Boolean T = Boolean.TRUE;
    private static final Boolean F = Boolean.FALSE;

    private PlanLimitsService planLimitsService;
    private PlanLimitsController controller;

    @BeforeEach
    void setUp() {
        planLimitsService = mock(PlanLimitsService.class);
        controller = new PlanLimitsController(planLimitsService);

        // update() echoes its ACTUAL positional arguments back as the saved
        // row (the real service returns the row it just persisted, same
        // shape). Because this Answer is positional by construction and
        // PlanLimitsResponse.from maps positionally too, every field of the
        // controller's return value equals whatever physically arrived at
        // that update() position - which is what lets the one-hot toggle
        // test below detect ANY pairwise boolean transposition, not just
        // the ones a single true/false pattern happens to distinguish.
        when(planLimitsService.update(
                any(TenantPlan.class), anyInt(), anyInt(), anyInt(), anyInt(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyBoolean()))
            .thenAnswer(inv -> new ResolvedPlanLimits(
                inv.<TenantPlan>getArgument(0),
                inv.<Integer>getArgument(1),
                inv.<Integer>getArgument(2),
                inv.<Integer>getArgument(3),
                inv.<Integer>getArgument(4),
                inv.<Boolean>getArgument(5),
                inv.<Boolean>getArgument(6),
                inv.<Boolean>getArgument(7),
                inv.<Boolean>getArgument(8),
                inv.<Boolean>getArgument(9),
                inv.<Boolean>getArgument(10),
                inv.<Boolean>getArgument(11),
                inv.<Boolean>getArgument(12),
                inv.<Boolean>getArgument(13),
                inv.<Boolean>getArgument(14)));
    }

    // ------------------------------------------------------------------
    // Null-resolution: an omitted boxed field means "keep current", never
    // "false" - checked once per nullable family (the original
    // liveVisionAllowed case plus a newer V470 entitlement).

    @Test
    void omittedLiveVisionFieldKeepsThePlansCurrentTrueRatherThanSilentlyRevokingIt() {
        // A web-admin draft captured by a pre-V180 build omits the field
        // entirely (Jackson deserializes the absent key to null). PREMIUM
        // seeds live_vision_allowed=TRUE - the controller must read that
        // current value and pass it through. A `!= null ? x : false`
        // coercion here would make this assertion fail with FALSE captured.
        when(planLimitsService.getLimitsForPlan(TenantPlan.PREMIUM))
            .thenReturn(new ResolvedPlanLimits(
                TenantPlan.PREMIUM, 10, 25, 15, 5,
                true, true, false, true,
                true, true, true, false, false, true));

        PlanLimitsResponse response = controller.updatePlanLimits(
            TenantPlan.PREMIUM,
            new PlanLimitsUpdateRequest(10, 25, 15, 5, true, true, false, null, T, T, T, F, F, T));

        ArgumentCaptor<Boolean> liveVision = ArgumentCaptor.forClass(Boolean.class);
        verify(planLimitsService).update(
            eq(TenantPlan.PREMIUM), eq(10), eq(25), eq(15), eq(5),
            eq(true), eq(true), eq(false), liveVision.capture(),
            eq(true), eq(true), eq(true), eq(false), eq(false), eq(true));
        assertTrue(liveVision.getValue(),
            "omitted liveVisionAllowed must resolve to the plan's current TRUE, not coerce to false");
        assertTrue(response.liveVisionAllowed());
    }

    @Test
    void omittedNewEntitlementKeepsThePlansCurrentValueRatherThanCoercingToFalse() {
        // The V470 entitlement columns share liveVisionAllowed's "omitted
        // keeps current" contract - pinned here through ssoAllowed (PREMIUM
        // seeds it TRUE) because all seven boxed fields funnel through the
        // same resolution block and a copy-paste slip dropping one of them
        // to false is exactly the regression shape this class exists for.
        when(planLimitsService.getLimitsForPlan(TenantPlan.PREMIUM))
            .thenReturn(new ResolvedPlanLimits(
                TenantPlan.PREMIUM, 10, 25, 15, 5,
                true, true, false, true,
                true, true, true, false, false, true));

        PlanLimitsResponse response = controller.updatePlanLimits(
            TenantPlan.PREMIUM,
            new PlanLimitsUpdateRequest(10, 25, 15, 5, true, true, false, T, T, T, null, F, F, T));

        verify(planLimitsService).update(
            eq(TenantPlan.PREMIUM), eq(10), eq(25), eq(15), eq(5),
            eq(true), eq(true), eq(false), eq(true),
            eq(true), eq(true), eq(true), eq(false), eq(false), eq(true));
        assertTrue(response.ssoAllowed(),
            "omitted ssoAllowed must resolve to the plan's current TRUE, not coerce to false");
    }

    @Test
    void omittedLiveVisionResolvesAgainstThePathPlansOwnRowNotSomeOtherPlans() {
        // FREE seeds live_vision_allowed=FALSE. Only FREE's row is stubbed -
        // if the controller consulted any other plan's row the unstubbed
        // call would return null and NPE, failing the test loudly. Guards
        // against resolving the null with a hardcoded plan (or a hardcoded
        // `true`, the mirror image of the coercion bug above).
        when(planLimitsService.getLimitsForPlan(TenantPlan.FREE))
            .thenReturn(new ResolvedPlanLimits(
                TenantPlan.FREE, 2, 5, 3, 1,
                false, false, false, false,
                false, false, false, false, false, false));

        PlanLimitsResponse response = controller.updatePlanLimits(
            TenantPlan.FREE,
            new PlanLimitsUpdateRequest(2, 5, 3, 1, false, false, false, null, F, F, F, F, F, F));

        verify(planLimitsService).getLimitsForPlan(TenantPlan.FREE);
        ArgumentCaptor<Boolean> liveVision = ArgumentCaptor.forClass(Boolean.class);
        verify(planLimitsService).update(
            eq(TenantPlan.FREE), eq(2), eq(5), eq(3), eq(1),
            eq(false), eq(false), eq(false), liveVision.capture(),
            eq(false), eq(false), eq(false), eq(false), eq(false), eq(false));
        assertFalse(liveVision.getValue());
        assertFalse(response.liveVisionAllowed());
    }

    @Test
    void explicitFalseIsHonouredAsARevokeAndNeverConfusedWithOmitted() {
        // The current row says TRUE, the admin explicitly unticks the
        // toggle. getLimitsForPlan is deliberately NOT stubbed: a controller
        // that wrongly treated Boolean.FALSE as "omitted" would consult the
        // row, get null from the unstubbed mock and NPE - and the never()
        // verify below pins that the read doesn't happen at all when the
        // field is present (an explicit value needs no extra service call).
        PlanLimitsResponse response = controller.updatePlanLimits(
            TenantPlan.UNLIMITED,
            new PlanLimitsUpdateRequest(-1, -1, -1, -1, true, true, true, F, T, T, T, T, T, T));

        ArgumentCaptor<Boolean> liveVision = ArgumentCaptor.forClass(Boolean.class);
        verify(planLimitsService).update(
            eq(TenantPlan.UNLIMITED), eq(-1), eq(-1), eq(-1), eq(-1),
            eq(true), eq(true), eq(true), liveVision.capture(),
            eq(true), eq(true), eq(true), eq(true), eq(true), eq(true));
        assertFalse(liveVision.getValue(),
            "an explicit false is a deliberate revoke and must reach the service as false");
        assertFalse(response.liveVisionAllowed());
        verify(planLimitsService, never()).getLimitsForPlan(any());
    }

    // ------------------------------------------------------------------
    // The plan the service receives is the PATH variable, always.

    @Test
    void thePathVariableIsThePlanThatReachesTheServiceNotAnythingBodyDerived() {
        // web-admin PUTs its whole draft row, which carries a `plan` key the
        // DTO deliberately does not declare (Jackson ignores unknown
        // properties - see the controller's javadoc). This pins the
        // contract at the unit level: whatever DTO widening happens later,
        // updatePlanLimits must keep forwarding its @PathVariable, so a
        // body-echoed plan can never redirect the write to another tier.
        controller.updatePlanLimits(
            TenantPlan.PREMIUM,
            new PlanLimitsUpdateRequest(1, 2, 3, 4, false, false, false, T, T, T, T, T, T, T));

        ArgumentCaptor<TenantPlan> plan = ArgumentCaptor.forClass(TenantPlan.class);
        verify(planLimitsService).update(
            plan.capture(), anyInt(), anyInt(), anyInt(), anyInt(),
            anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
            anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
            anyBoolean(), anyBoolean());
        assertEquals(TenantPlan.PREMIUM, plan.getValue());
    }

    // ------------------------------------------------------------------
    // Positional integrity of the fifteen-argument update call.

    @Test
    void allFifteenValuesReachUpdateInTheDeclaredPositionalOrder() {
        // Distinct sentinel per int position: a maxAgents/maxWorkflows (or
        // any other int/int) transposition is invisible to the type system
        // and would mis-cap every tenant on the plan - distinct values make
        // each captor's assertion position-specific.
        controller.updatePlanLimits(
            TenantPlan.UNLIMITED,
            new PlanLimitsUpdateRequest(11, 22, 33, 44, true, false, true, F, T, F, T, F, T, F));

        ArgumentCaptor<TenantPlan> plan = ArgumentCaptor.forClass(TenantPlan.class);
        ArgumentCaptor<Integer> maxUsers = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> maxAgents = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> maxWorkflows = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> maxKbCollections = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> byokAllowed = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> whatsappAllowed = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> whiteLabel = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> liveVisionAllowed = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> externalChannels = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> developerApi = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> sso = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> scim = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> workflowExport = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> automation = ArgumentCaptor.forClass(Boolean.class);
        verify(planLimitsService).update(
            plan.capture(),
            maxUsers.capture(),
            maxAgents.capture(),
            maxWorkflows.capture(),
            maxKbCollections.capture(),
            byokAllowed.capture(),
            whatsappAllowed.capture(),
            whiteLabel.capture(),
            liveVisionAllowed.capture(),
            externalChannels.capture(),
            developerApi.capture(),
            sso.capture(),
            scim.capture(),
            workflowExport.capture(),
            automation.capture());

        assertEquals(TenantPlan.UNLIMITED, plan.getValue());
        assertEquals(11, maxUsers.getValue());
        assertEquals(22, maxAgents.getValue());
        assertEquals(33, maxWorkflows.getValue());
        assertEquals(44, maxKbCollections.getValue());
        assertTrue(byokAllowed.getValue());
        assertFalse(whatsappAllowed.getValue());
        assertTrue(whiteLabel.getValue());
        assertFalse(liveVisionAllowed.getValue());
        assertTrue(externalChannels.getValue());
        assertFalse(developerApi.getValue());
        assertTrue(sso.getValue());
        assertFalse(scim.getValue());
        assertTrue(workflowExport.getValue());
        assertFalse(automation.getValue());
    }

    @Test
    void eachToggleLandsInItsOwnUpdatePositionNotANeighbours() {
        // The true/false/true pattern above cannot distinguish a
        // byokAllowed/whiteLabel transposition (both true) - no single
        // pattern over eleven booleans can catch every pairwise swap.
        // One-hot patterns can: exactly one toggle true per call, asserted
        // through the response, which (via the positional echo Answer in
        // setUp) reflects what physically arrived at each update()
        // position.
        PlanLimitsResponse byokOnly = controller.updatePlanLimits(
            TenantPlan.PREMIUM, new PlanLimitsUpdateRequest(1, 2, 3, 4, true, false, false, F, F, F, F, F, F, F));
        assertTrue(byokOnly.byokAllowed());
        assertFalse(byokOnly.whatsappAllowed());
        assertFalse(byokOnly.whiteLabel());
        assertFalse(byokOnly.liveVisionAllowed());

        PlanLimitsResponse whatsappOnly = controller.updatePlanLimits(
            TenantPlan.PREMIUM, new PlanLimitsUpdateRequest(1, 2, 3, 4, false, true, false, F, F, F, F, F, F, F));
        assertFalse(whatsappOnly.byokAllowed());
        assertTrue(whatsappOnly.whatsappAllowed());
        assertFalse(whatsappOnly.whiteLabel());
        assertFalse(whatsappOnly.liveVisionAllowed());

        PlanLimitsResponse whiteLabelOnly = controller.updatePlanLimits(
            TenantPlan.PREMIUM, new PlanLimitsUpdateRequest(1, 2, 3, 4, false, false, true, F, F, F, F, F, F, F));
        assertFalse(whiteLabelOnly.byokAllowed());
        assertFalse(whiteLabelOnly.whatsappAllowed());
        assertTrue(whiteLabelOnly.whiteLabel());
        assertFalse(whiteLabelOnly.liveVisionAllowed());

        // Explicit TRUE doubles as the pass-through case for a supplied
        // value upgrading a plan whose toggles are otherwise all off.
        PlanLimitsResponse liveVisionOnly = controller.updatePlanLimits(
            TenantPlan.PREMIUM, new PlanLimitsUpdateRequest(1, 2, 3, 4, false, false, false, T, F, F, F, F, F, F));
        assertFalse(liveVisionOnly.byokAllowed());
        assertFalse(liveVisionOnly.whatsappAllowed());
        assertFalse(liveVisionOnly.whiteLabel());
        assertTrue(liveVisionOnly.liveVisionAllowed());
        assertFalse(liveVisionOnly.externalChannelsAllowed());

        // The V470 entitlements get the same one-hot treatment on two
        // positions that sit at opposite ends of the new field run - a
        // shifted argument list would land at least one of them wrong.
        PlanLimitsResponse ssoOnly = controller.updatePlanLimits(
            TenantPlan.PREMIUM, new PlanLimitsUpdateRequest(1, 2, 3, 4, false, false, false, F, F, F, T, F, F, F));
        assertTrue(ssoOnly.ssoAllowed());
        assertFalse(ssoOnly.scimAllowed());
        assertFalse(ssoOnly.automationAllowed());

        PlanLimitsResponse automationOnly = controller.updatePlanLimits(
            TenantPlan.PREMIUM, new PlanLimitsUpdateRequest(1, 2, 3, 4, false, false, false, F, F, F, F, F, F, T));
        assertTrue(automationOnly.automationAllowed());
        assertFalse(automationOnly.ssoAllowed());
        assertFalse(automationOnly.workflowExportAllowed());
    }

    // ------------------------------------------------------------------
    // The editor's page-load read.

    @Test
    void listPlanLimitsProjectsEveryResolvedRowUnchangedInServiceOrder() {
        // Thin projection only - the GET must not reorder, drop or reshape
        // rows; web-admin's plans page renders exactly what comes back.
        when(planLimitsService.getAllPlanLimits()).thenReturn(List.of(
            new ResolvedPlanLimits(TenantPlan.FREE, 2, 5, 3, 1,
                false, false, false, false,
                false, false, false, false, false, false),
            new ResolvedPlanLimits(TenantPlan.PREMIUM, 10, 25, 15, 5,
                true, true, false, true,
                true, true, true, false, false, true)));

        List<PlanLimitsResponse> out = controller.listPlanLimits();

        assertEquals(2, out.size());
        assertEquals("FREE", out.get(0).plan());
        assertEquals(5, out.get(0).maxAgents());
        assertFalse(out.get(0).liveVisionAllowed());
        assertFalse(out.get(0).externalChannelsAllowed());
        assertEquals("PREMIUM", out.get(1).plan());
        assertEquals(25, out.get(1).maxAgents());
        assertTrue(out.get(1).liveVisionAllowed());
        assertTrue(out.get(1).ssoAllowed());
    }
}
