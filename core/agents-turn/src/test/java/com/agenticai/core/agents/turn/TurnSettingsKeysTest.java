package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.settings.BehaviourCatalogFixture;
import com.agenticai.core.agents.tunables.BehaviourSettings;
import com.agenticai.core.agents.turn.TurnContinuation.Signals;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The turn-level settings of the catalog on the objects that consume them directly: the finish limits, the cache policy
 * and the continuation wording check. Each override is set the way an admin's write leaves it, in a real catalog over a
 * tenant-partitioned store; the end-to-end behaviour through the whole turn is in the agents-tools {@code
 * BehaviourSettingsTurnE2ETest}.
 */
class TurnSettingsKeysTest {

    private static final String TENANT = "tenant-a";
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final Instant T = Instant.parse("2026-10-01T00:00:00Z");

    private final BehaviourCatalogFixture f = new BehaviourCatalogFixture();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static TenantScope scope(String tenant) {
        return TenantScope.of(TenantContext.of(tenant, "u", Set.of()));
    }

    private BehaviourSettings behaviour() {
        return BehaviourSettings.of(f.resolver());
    }

    private static ChatTurn user(String s) {
        return new ChatTurn(MessageRole.USER, s, T);
    }

    private static ChatTurn assistant(String s) {
        return new ChatTurn(MessageRole.ASSISTANT, s, T);
    }

    // ------------------------------------------------------------------------------------ finish limits

    @Test
    void finishLimitsAreThePlatformsUntilATenantOrAnAgentSetsThem() {
        f.finish.setMaxContinuations(6);
        f.finish.setMaxToolCallRetries(2);

        TurnFinishLimits limits = TurnFinishLimits.resolve(behaviour(), f.finish, scope(TENANT), AGENT);

        assertEquals(new TurnFinishLimits(true, 6, 2), limits);
    }

    @Test
    void aTenantsFinishLimitsApplyToItsAgentsAndTheAgentsOwnWin() {
        f.tenant(TENANT, "chat.finish.maxContinuations", 1)
                .tenant(TENANT, "chat.finish.maxToolCallRetries", 0)
                .agent(TENANT, AGENT, "chat.finish.maxContinuations", 4)
                .agent(TENANT, OTHER_AGENT, "chat.finish.enabled", false);

        assertEquals(new TurnFinishLimits(true, 4, 0), TurnFinishLimits.resolve(behaviour(), f.finish, scope(TENANT), AGENT));
        assertEquals(new TurnFinishLimits(false, 1, 0), TurnFinishLimits.resolve(behaviour(), f.finish, scope(TENANT), OTHER_AGENT));
        assertEquals(
                new TurnFinishLimits(true, f.finish.getMaxContinuations(), f.finish.getMaxToolCallRetries()),
                TurnFinishLimits.resolve(behaviour(), f.finish, scope("tenant-b"), AGENT),
                "another tenant keeps the platform values");
    }

    @Test
    void withoutACatalogTheLimitsAreThePropertyObjectsOwn() {
        f.finish.setMaxContinuations(3);

        assertEquals(
                new TurnFinishLimits(f.finish.isEnabled(), 3, f.finish.getMaxToolCallRetries()),
                TurnFinishLimits.resolve(BehaviourSettings.platformOnly(), f.finish, scope(TENANT), AGENT));
    }

    // ----------------------------------------------------------------------------------- cache policy

    @Test
    void cacheEligibilityWithToolsFollowsTheTenantAndTheAgent() {
        f.cache.setEligibleWithTools(false);
        f.agent(TENANT, AGENT, "chat.cache.eligibleWithTools", true);
        ChatCachePolicy policy = new ChatCachePolicy(f.cache, behaviour());

        assertTrue(policy.eligibleWithTools(scope(TENANT), AGENT), "the agent opted in");
        assertFalse(policy.eligibleWithTools(scope(TENANT), OTHER_AGENT), "its sibling did not");
        assertFalse(policy.eligibleWithTools(scope("tenant-b"), AGENT), "another tenant did not");
        assertFalse(policy.eligibleWithTools(), "the platform-only form is unchanged");
    }

    @Test
    void aTenantCanSwitchToolAgentsBackOutOfTheCachesAgainstAnOptedInPlatform() {
        f.cache.setEligibleWithTools(true);
        f.tenant(TENANT, "chat.cache.eligibleWithTools", false);
        ChatCachePolicy policy = new ChatCachePolicy(f.cache, behaviour());

        assertFalse(policy.eligibleWithTools(scope(TENANT), AGENT));
        assertTrue(policy.eligibleWithTools(scope("tenant-b"), AGENT));
    }

    @Test
    void theNumberOfContextTurnsInTheKeyFollowsTheAgent() {
        f.cache.setContextTurns(1);
        f.agent(TENANT, AGENT, "chat.cache.contextTurns", 3);
        ChatCachePolicy policy = new ChatCachePolicy(f.cache, behaviour());
        List<ChatTurn> a = List.of(user("old"), user("q"), assistant("a"));
        List<ChatTurn> b = List.of(user("different old"), user("q"), assistant("a"));

        assertEquals(
                policy.conversationKey(a, scope(TENANT), OTHER_AGENT),
                policy.conversationKey(b, scope(TENANT), OTHER_AGENT),
                "one context turn: only the last turn counts");
        assertNotEquals(
                policy.conversationKey(a, scope(TENANT), AGENT),
                policy.conversationKey(b, scope(TENANT), AGENT),
                "three context turns: the older turn is part of the key");
        assertEquals(policy.conversationKey(a), policy.conversationKey(a, scope("tenant-b"), AGENT),
                "no override: the same key the platform-only form gives");
    }

    // ------------------------------------------------------------------ continuation wording check

    private static final String UNFINISHED = "Let me create the pom.xml file:";

    @Test
    void theWordingCheckSwitchOfTheCatalogDecidesWhetherAnAnnouncedStepIsContinued() {
        TurnContinuation policy = new TurnContinuation(f.continuation);
        Signals weakModelStoppedAtAStep = new Signals(UNFINISHED, true, false, 0, 0);

        assertTrue(policy.decide(weakModelStoppedAtAStep).proceed(), "platform: on");
        assertFalse(policy.decide(weakModelStoppedAtAStep, false).proceed(), "switched off for this tenant or agent");
        assertTrue(policy.decide(weakModelStoppedAtAStep, true).proceed());
    }

    @Test
    void theWordingCheckSwitchIsAskedOfTheCatalogPerAgent() {
        f.agent(TENANT, AGENT, TurnContinuation.HEURISTIC_ENABLED_KEY, false);
        TurnContinuation policy = new TurnContinuation(f.continuation);

        boolean forAgent =
                behaviour().bool(TurnContinuation.HEURISTIC_ENABLED_KEY, scope(TENANT), AGENT, policy::heuristicEnabledByPlatform);
        boolean forSibling =
                behaviour().bool(TurnContinuation.HEURISTIC_ENABLED_KEY, scope(TENANT), OTHER_AGENT, policy::heuristicEnabledByPlatform);

        assertFalse(forAgent);
        assertTrue(forSibling);
        Signals weakModelStoppedAtAStep = new Signals(UNFINISHED, true, false, 0, 0);
        assertFalse(policy.decide(weakModelStoppedAtAStep, forAgent).proceed());
        assertTrue(policy.decide(weakModelStoppedAtAStep, forSibling).proceed());
    }
}
