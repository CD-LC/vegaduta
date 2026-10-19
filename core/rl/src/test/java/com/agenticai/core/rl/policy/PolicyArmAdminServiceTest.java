package com.agenticai.core.rl.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.rl.api.RlArmConflictException;
import com.agenticai.core.rl.api.RlArmConfigValidator;
import com.agenticai.core.rl.api.RlArmNotFoundException;
import com.agenticai.core.rl.api.RlArmRejectedException;
import com.agenticai.core.rl.domain.AgentPolicy;
import com.agenticai.core.rl.domain.PolicyArm;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.web.dto.PolicyArmRequest;
import com.agenticai.core.rl.web.dto.PolicyArmResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The write half of the fix for "the bandit can never learn": until these
 * endpoints existed, {@code MODEL_ROUTE} could only get arms from a config
 * property that defaults to empty, so every tenant in production had an arm
 * pool of size zero and {@code ContextualBanditService#selectArm} returned
 * empty on every single turn.
 *
 * <p>These tests pin the three things that make arm creation safe rather than
 * merely possible: nothing is written until validation passes, a FREE tenant is
 * told "no" instead of being handed an arm that could never be pulled, and an
 * arm can only ever be read/deleted through the policy that actually owns it.
 * Provider/model usability itself is validated by {@code RlArmConfigValidator}
 * (implemented in {@code core/agents}, tested there) - here it is a mock, and
 * what is asserted is that its veto is honoured before any persistence happens.
 */
class PolicyArmAdminServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID POLICY_ID = UUID.randomUUID();

    private PolicyStore policyStore;
    private TenantLimits tenantLimits;
    private RlArmConfigValidator validator;
    private ObjectProvider<RlArmConfigValidator> validatorProvider;
    private PolicyArmAdminService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        policyStore = mock(PolicyStore.class);
        tenantLimits = mock(TenantLimits.class);
        validator = mock(RlArmConfigValidator.class);
        validatorProvider = mock(ObjectProvider.class);
        when(validatorProvider.getIfAvailable()).thenReturn(validator);
        service = new PolicyArmAdminService(policyStore, tenantLimits, validatorProvider);

        // Every test runs as a PREMIUM tenant unless it says otherwise - the
        // FREE gate has its own test below.
        TenantContext.set(TenantContext.of("acme", "admin-1", Set.of("tenant-admin")));
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private AgentPolicy policy() {
        // AgentPolicy#id is JPA-generated, so a constructed instance has none -
        // mock just the identity this service reads off it.
        AgentPolicy policy = mock(AgentPolicy.class);
        when(policy.getId()).thenReturn(POLICY_ID);
        return policy;
    }

    private void stubPolicyExists() {
        AgentPolicy policy = policy();
        when(policyStore.ensurePolicy(AGENT_ID, PolicyDimension.MODEL_ROUTE)).thenReturn(policy);
        when(policyStore.findPolicy(AGENT_ID, PolicyDimension.MODEL_ROUTE)).thenReturn(Optional.of(policy));
        when(policyStore.createArm(eq(POLICY_ID), anyString(), anyString(), any()))
            .thenAnswer(invocation -> new PolicyArm(
                invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2), invocation.getArgument(3)));
    }

    private static PolicyArmRequest request(String armKey, String armConfigJson) {
        return new PolicyArmRequest(armKey, armConfigJson, null);
    }

    // --- list ---

    @Test
    void listReturnsThePolicysArms() {
        AgentPolicy policy = policy();
        when(policyStore.findPolicy(AGENT_ID, PolicyDimension.MODEL_ROUTE)).thenReturn(Optional.of(policy));
        when(policyStore.listArms(POLICY_ID)).thenReturn(List.of(
            new PolicyArm(POLICY_ID, "gpt-4o", "{\"model\":\"gpt-4o\"}", null),
            new PolicyArm(POLICY_ID, "gpt-4o-mini", "{\"model\":\"gpt-4o-mini\"}", "short")
        ));

        List<PolicyArmResponse> arms = service.list(AGENT_ID, PolicyDimension.MODEL_ROUTE);

        assertThat(arms).extracting(PolicyArmResponse::armKey).containsExactly("gpt-4o", "gpt-4o-mini");
        assertThat(arms).extracting(PolicyArmResponse::contextBucket).containsExactly(null, "short");
        // A brand-new arm carries nothing but the uniform prior - this is the
        // signal an operator uses to tell "configured" from "learned".
        assertThat(arms.get(0).pulls()).isZero();
        assertThat(arms.get(0).alpha()).isEqualTo(1.0);
        assertThat(arms.get(0).beta()).isEqualTo(1.0);
    }

    @Test
    void listIsSideEffectFreeWhenThePolicyDoesNotExistYet() {
        when(policyStore.findPolicy(AGENT_ID, PolicyDimension.GEN_PARAMS)).thenReturn(Optional.empty());

        assertThat(service.list(AGENT_ID, PolicyDimension.GEN_PARAMS)).isEmpty();

        verify(policyStore, never()).ensurePolicy(any(), any());
        verify(policyStore, never()).ensureAllPolicies(any());
    }

    // --- create: happy path ---

    @Test
    void createValidatesAgainstTheRuntimeThenPersists() {
        stubPolicyExists();

        PolicyArmResponse created = service.create(
            AGENT_ID, PolicyDimension.MODEL_ROUTE, request("gpt-4o", "{ \"model\" : \"gpt-4o\" }"));

        // The validator sees the CANONICAL config, and sees it before anything
        // is written.
        verify(validator).validateArm(AGENT_ID, PolicyDimension.MODEL_ROUTE, "gpt-4o", "{\"model\":\"gpt-4o\"}");
        verify(policyStore).createArm(POLICY_ID, "gpt-4o", "{\"model\":\"gpt-4o\"}", null);
        assertThat(created.armKey()).isEqualTo("gpt-4o");
        assertThat(created.armConfigJson()).isEqualTo("{\"model\":\"gpt-4o\"}");
        assertThat(created.contextBucket()).isNull();
    }

    @Test
    void createTrimsTheArmKeyAndCollapsesABlankContextBucketToTheGlobalBucket() {
        stubPolicyExists();

        service.create(AGENT_ID, PolicyDimension.MODEL_ROUTE,
            new PolicyArmRequest("  gpt-4o  ", "{\"model\":\"gpt-4o\"}", "   "));

        verify(policyStore).createArm(POLICY_ID, "gpt-4o", "{\"model\":\"gpt-4o\"}", null);
    }

    // --- create: config rejection (400, never a 500) ---

    @Test
    void createRejectsMalformedJsonWithoutTouchingAnything() {
        assertThatThrownBy(() -> service.create(
            AGENT_ID, PolicyDimension.MODEL_ROUTE, request("broken", "{\"model\": ")))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("not valid JSON");

        verifyNoInteractions(validator);
        verify(policyStore, never()).ensurePolicy(any(), any());
        verify(policyStore, never()).createArm(any(), anyString(), anyString(), any());
    }

    @Test
    void createRejectsAConfigThatIsNotAJsonObject() {
        assertThatThrownBy(() -> service.create(
            AGENT_ID, PolicyDimension.MODEL_ROUTE, request("array", "[\"gpt-4o\"]")))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("JSON OBJECT");

        verifyNoInteractions(validator);
    }

    @Test
    void createRejectsTrailingGarbageAfterTheObject() {
        // Plain readTree would silently accept this by parsing the first value
        // and ignoring the rest, storing something the operator did not mean.
        assertThatThrownBy(() -> service.create(
            AGENT_ID, PolicyDimension.MODEL_ROUTE, request("sloppy", "{\"model\":\"gpt-4o\"} oops")))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("not valid JSON");

        verifyNoInteractions(validator);
    }

    @Test
    void createRejectsABlankArmKey() {
        assertThatThrownBy(() -> service.create(
            AGENT_ID, PolicyDimension.MODEL_ROUTE, request("   ", "{\"model\":\"gpt-4o\"}")))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("armKey is required");

        verifyNoInteractions(validator);
    }

    // --- create: the runtime's own veto ---

    @Test
    void createPersistsNothingWhenTheRuntimeRejectsTheModel() {
        doThrow(new RlArmRejectedException("\"claude-opus-4-8\" isn't in OPENAI's model catalog"))
            .when(validator).validateArm(any(), any(), anyString(), anyString());

        assertThatThrownBy(() -> service.create(
            AGENT_ID, PolicyDimension.MODEL_ROUTE, request("claude-opus-4-8", "{\"model\":\"claude-opus-4-8\"}")))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("model catalog");

        // Crucially: no half-created policy row is left behind for a rejected arm.
        verify(policyStore, never()).ensurePolicy(any(), any());
        verify(policyStore, never()).createArm(any(), anyString(), anyString(), any());
    }

    @Test
    void createFailsClosedForModelRouteWhenNoValidatorIsOnTheClasspath() {
        when(validatorProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.create(
            AGENT_ID, PolicyDimension.MODEL_ROUTE, request("gpt-4o", "{\"model\":\"gpt-4o\"}")))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("cannot be validated");

        verify(policyStore, never()).createArm(any(), anyString(), anyString(), any());
    }

    @Test
    void createStillWorksForDimensionsWithNoRuntimeConsumerWhenNoValidatorIsPresent() {
        when(validatorProvider.getIfAvailable()).thenReturn(null);
        AgentPolicy policy = policy();
        when(policyStore.ensurePolicy(AGENT_ID, PolicyDimension.GEN_PARAMS)).thenReturn(policy);
        when(policyStore.createArm(eq(POLICY_ID), anyString(), anyString(), any()))
            .thenAnswer(invocation -> new PolicyArm(
                invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2), invocation.getArgument(3)));

        PolicyArmResponse created = service.create(
            AGENT_ID, PolicyDimension.GEN_PARAMS, request("SPICY", "{\"temperature\":1.2,\"topP\":0.99}"));

        assertThat(created.armKey()).isEqualTo("SPICY");
    }

    // --- create: duplicate keys ---

    @Test
    void createRejectsADuplicateArmKeyOnTheSamePolicy() {
        stubPolicyExists();
        when(policyStore.findArmByKey(POLICY_ID, "gpt-4o"))
            .thenReturn(Optional.of(new PolicyArm(POLICY_ID, "gpt-4o", "{\"model\":\"gpt-4o\"}", null)));

        assertThatThrownBy(() -> service.create(
            AGENT_ID, PolicyDimension.MODEL_ROUTE, request("gpt-4o", "{\"model\":\"gpt-4o\"}")))
            .isInstanceOf(RlArmConflictException.class)
            .hasMessageContaining("already exists");

        verify(policyStore, never()).createArm(any(), anyString(), anyString(), any());
    }

    @Test
    void createRefusesToGrowThePolicyPastTheArmCeiling() {
        stubPolicyExists();
        List<PolicyArm> full = java.util.stream.IntStream.range(0, PolicyArmAdminService.MAX_ARMS_PER_POLICY)
            .mapToObj(i -> new PolicyArm(POLICY_ID, "arm-" + i, "{\"model\":\"m" + i + "\"}", null))
            .toList();
        when(policyStore.listArms(POLICY_ID)).thenReturn(full);

        assertThatThrownBy(() -> service.create(
            AGENT_ID, PolicyDimension.MODEL_ROUTE, request("one-too-many", "{\"model\":\"gpt-4o\"}")))
            .isInstanceOf(RlArmRejectedException.class)
            .hasMessageContaining("maximum is " + PolicyArmAdminService.MAX_ARMS_PER_POLICY);
    }

    // --- create: FREE plan ---

    @Test
    void createIsRefusedOutrightForAFreeTenantRatherThanSilentlyInert() {
        // DefaultRlPolicyPort#selectArm short-circuits to empty for FREE on
        // EVERY dimension, so an arm created here could never be pulled -
        // storing one would reproduce the exact "configured but does nothing"
        // failure this whole change exists to remove.
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.FREE);

        assertThatThrownBy(() -> service.create(
            AGENT_ID, PolicyDimension.MODEL_ROUTE, request("gpt-4o", "{\"model\":\"gpt-4o\"}")))
            .isInstanceOf(TenantLimitExceededException.class)
            .hasMessageContaining("Premium");

        verifyNoInteractions(validator);
        verify(policyStore, never()).ensurePolicy(any(), any());
    }

    @Test
    void freeTenantsMayStillListAndDeleteArmsTheyAlreadyHave() {
        // A downgrade must not strand arms a tenant created while paid.
        when(tenantLimits.plan("acme")).thenReturn(TenantPlan.FREE);
        AgentPolicy policy = policy();
        when(policyStore.findPolicy(AGENT_ID, PolicyDimension.MODEL_ROUTE)).thenReturn(Optional.of(policy));
        PolicyArm arm = new PolicyArm(POLICY_ID, "gpt-4o", "{\"model\":\"gpt-4o\"}", null);
        UUID armId = UUID.randomUUID();
        when(policyStore.listArms(POLICY_ID)).thenReturn(List.of(arm));
        when(policyStore.findArmById(armId)).thenReturn(Optional.of(arm));

        assertThat(service.list(AGENT_ID, PolicyDimension.MODEL_ROUTE)).hasSize(1);
        service.delete(AGENT_ID, PolicyDimension.MODEL_ROUTE, armId);

        verify(policyStore).deleteArm(arm);
    }

    // --- delete ---

    @Test
    void deleteRemovesAnArmOfThisAgentAndDimension() {
        AgentPolicy policy = policy();
        when(policyStore.findPolicy(AGENT_ID, PolicyDimension.MODEL_ROUTE)).thenReturn(Optional.of(policy));
        PolicyArm arm = new PolicyArm(POLICY_ID, "gpt-4o", "{\"model\":\"gpt-4o\"}", null);
        UUID armId = UUID.randomUUID();
        when(policyStore.findArmById(armId)).thenReturn(Optional.of(arm));

        service.delete(AGENT_ID, PolicyDimension.MODEL_ROUTE, armId);

        verify(policyStore).deleteArm(arm);
    }

    @Test
    void deleteIsNotFoundWhenTheArmBelongsToADifferentPolicy() {
        // Covers both "wrong dimension's URL" and the cross-tenant case: an arm
        // id that resolves inside this schema but hangs off another policy is
        // not deletable through this one.
        AgentPolicy policy = policy();
        when(policyStore.findPolicy(AGENT_ID, PolicyDimension.MODEL_ROUTE)).thenReturn(Optional.of(policy));
        UUID armId = UUID.randomUUID();
        when(policyStore.findArmById(armId))
            .thenReturn(Optional.of(new PolicyArm(UUID.randomUUID(), "someone-elses", "{}", null)));

        assertThatThrownBy(() -> service.delete(AGENT_ID, PolicyDimension.MODEL_ROUTE, armId))
            .isInstanceOf(RlArmNotFoundException.class);

        verify(policyStore, never()).deleteArm(any());
    }

    // --- cross-tenant isolation ---

    @Test
    void tenantBCannotDeleteAnArmOnTenantAsPolicy() {
        // RL tables are schema-per-tenant (PolicyArm carries no tenant_id), so
        // tenant A's agent has no policy row inside tenant B's schema at all -
        // the lookup simply misses and B learns nothing beyond "not here".
        when(policyStore.findPolicy(AGENT_ID, PolicyDimension.MODEL_ROUTE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(AGENT_ID, PolicyDimension.MODEL_ROUTE, UUID.randomUUID()))
            .isInstanceOf(RlArmNotFoundException.class)
            .hasMessageContaining("no arms to delete");

        verify(policyStore, never()).deleteArm(any());
        verify(policyStore, never()).findArmById(any());
    }

    @Test
    void tenantBCannotAddAnArmToTenantAsAgent() {
        // The validator resolves the agent through AgentService#get, which is
        // tenant-scoped and throws the agents module's own not-found (mapped to
        // 404) for an id that isn't this tenant's - modelled here as any
        // propagating runtime exception, since core/rl must not depend on
        // core/agents to know its type.
        doThrow(new IllegalStateException("Agent not found"))
            .when(validator).validateArm(any(), any(), anyString(), anyString());

        assertThatThrownBy(() -> service.create(
            AGENT_ID, PolicyDimension.MODEL_ROUTE, request("gpt-4o", "{\"model\":\"gpt-4o\"}")))
            .isInstanceOf(IllegalStateException.class);

        verify(policyStore, never()).ensurePolicy(any(), any());
        verify(policyStore, never()).createArm(any(), anyString(), anyString(), any());
    }

    @Test
    void tenantBCannotListTenantAsArms() {
        when(policyStore.findPolicy(AGENT_ID, PolicyDimension.MODEL_ROUTE)).thenReturn(Optional.empty());

        assertThat(service.list(AGENT_ID, PolicyDimension.MODEL_ROUTE)).isEmpty();

        verify(policyStore, never()).listArms(any());
    }
}
