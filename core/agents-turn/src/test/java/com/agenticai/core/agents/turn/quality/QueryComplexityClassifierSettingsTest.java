package com.agenticai.core.agents.turn.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.decision.DecisionEngine;
import com.agenticai.core.agents.settings.BehaviourCatalogFixture;
import com.agenticai.core.security.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code routing.simple.maxChars} / {@code routing.simple.maxClauses}: what counts as a simple message is the tenant's or
 * the agent's own when they set it, the {@code app.routing.simple.*} value when they did not.
 */
class QueryComplexityClassifierSettingsTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SIBLING = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final String HOURS = "What are your opening hours?";
    private static final String THREE_CLAUSES = "Hello there. Nice day. See you soon";

    private final BehaviourCatalogFixture f = new BehaviourCatalogFixture();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private QueryComplexityClassifier classifier() {
        QueryComplexityClassifier classifier = new QueryComplexityClassifier((DecisionEngine) null, false, 0.75, f.routing);
        classifier.setSettingsResolver(f.resolver());
        return classifier;
    }

    private static boolean simpleFor(QueryComplexityClassifier classifier, String tenant, UUID agent, String message) {
        boolean[] out = new boolean[1];
        BehaviourCatalogFixture.inTenant(tenant, () -> out[0] = classifier.isSimple(message, null, agent));
        return out[0];
    }

    @Test
    void withNoOverrideTheDecisionIsTheOneTheRoutingPropertiesGive() {
        f.routing.setMaxChars(20);
        f.routing.setMaxClauses(2);
        QueryComplexityClassifier classifier = classifier();

        assertThat(simpleFor(classifier, "tenant-a", AGENT, HOURS)).as("28 chars against a platform limit of 20").isFalse();
        assertThat(simpleFor(classifier, "tenant-a", AGENT, "Opening hours?")).isTrue();
        assertThat(simpleFor(classifier, "tenant-a", AGENT, THREE_CLAUSES)).as("three clauses against two").isFalse();
    }

    @Test
    void aTenantsMaxCharsDecideWhatIsSimpleForItsAgentsOnly() {
        f.routing.setMaxChars(20);
        f.tenant("tenant-a", "routing.simple.maxChars", 200);
        QueryComplexityClassifier classifier = classifier();

        assertThat(simpleFor(classifier, "tenant-a", AGENT, HOURS)).isTrue();
        assertThat(simpleFor(classifier, "tenant-b", AGENT, HOURS)).as("another tenant keeps the platform limit").isFalse();
    }

    @Test
    void anAgentsOwnMaxCharsBeatTheTenantsAndLeaveItsSiblingsAlone() {
        f.routing.setMaxChars(200);
        f.tenant("tenant-a", "routing.simple.maxChars", 200).agent("tenant-a", AGENT, "routing.simple.maxChars", 10);
        QueryComplexityClassifier classifier = classifier();

        assertThat(simpleFor(classifier, "tenant-a", AGENT, HOURS)).as("this agent wants only very short messages routed down").isFalse();
        assertThat(simpleFor(classifier, "tenant-a", SIBLING, HOURS)).isTrue();
    }

    @Test
    void theClauseLimitIsTheTenantsToo() {
        f.routing.setMaxClauses(2);
        f.tenant("tenant-a", "routing.simple.maxClauses", 4);
        QueryComplexityClassifier classifier = classifier();

        assertThat(simpleFor(classifier, "tenant-a", AGENT, THREE_CLAUSES)).isTrue();
        assertThat(simpleFor(classifier, "tenant-b", AGENT, THREE_CLAUSES)).isFalse();
    }

    @Test
    void aClassifierWithoutACatalogUsesThePropertiesAsBefore() {
        f.routing.setMaxChars(20);
        QueryComplexityClassifier plain = new QueryComplexityClassifier((DecisionEngine) null, false, 0.75, f.routing);

        assertThat(plain.isSimple(HOURS)).isFalse();
        assertThat(plain.isSimple("Opening hours?")).isTrue();
    }
}
