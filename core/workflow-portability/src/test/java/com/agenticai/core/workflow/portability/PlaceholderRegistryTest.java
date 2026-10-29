package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The registry's key assignment, de-duplication and the {@code @placeholder:} token grammar. A
 * regression here either leaks a foreign id into an exported file or drops a use the import preview
 * promised to list.
 */
class PlaceholderRegistryTest {

    @Test
    void registerHandsOutSequentialKeysPerKind() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        assertThat(registry.register(PlaceholderRegistry.AGENT, "uuid-1", "A", "n1", "agentId"))
                .isEqualTo("agent_1");
        assertThat(registry.register(PlaceholderRegistry.AGENT, "uuid-2", "B", "n2", "agentId"))
                .isEqualTo("agent_2");
        assertThat(
                        registry.register(
                                PlaceholderRegistry.KNOWLEDGE_BASE,
                                "kb-1",
                                "Docs",
                                "n3",
                                "collectionId"))
                .isEqualTo("kb_1");
    }

    @Test
    void theSameIdentitySharesOneKeyAndCollectsItsUses() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        String first =
                registry.register(
                        PlaceholderRegistry.AGENT, "uuid-1", "Researcher", "n1", "agentId");
        String second =
                registry.register(
                        PlaceholderRegistry.AGENT, "uuid-1", "Researcher", "n2", "agentId");
        assertThat(second).isEqualTo(first);

        PlaceholderRegistry.Entry entry = registry.get(first);
        assertThat(entry.kind()).isEqualTo(PlaceholderRegistry.AGENT);
        assertThat(entry.label()).isEqualTo("Researcher");
        assertThat(entry.usedBy())
                .containsExactly(
                        new PlaceholderRegistry.Use("n1", "agentId"),
                        new PlaceholderRegistry.Use("n2", "agentId"));
    }

    @Test
    void registerKeepsDistinctEntriesDistinct() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        // A null identity falls back to the label, so two different labels are two placeholders.
        assertThat(registry.register(PlaceholderRegistry.DESTINATION, null, "a@x.com", "n1", "to"))
                .isEqualTo("destination_1");
        assertThat(registry.register(PlaceholderRegistry.DESTINATION, null, "b@x.com", "n2", "to"))
                .isEqualTo("destination_2");
    }

    @Test
    void registerExactKeepsTheSpellingAndBackfillsAnEmptyLabel() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        PlaceholderRegistry.Entry entry =
                registry.registerExact("agent_7", PlaceholderRegistry.AGENT, null, "n1", "agentId");
        assertThat(entry.key()).isEqualTo("agent_7");
        assertThat(entry.label()).isEmpty();

        PlaceholderRegistry.Entry again =
                registry.registerExact(
                        "agent_7", PlaceholderRegistry.AGENT, "Researcher", "n2", "agentId");
        assertThat(again).isSameAs(entry);
        assertThat(entry.label()).isEqualTo("Researcher");
        assertThat(entry.usedBy()).hasSize(2);
    }

    @Test
    void useOnAnUnknownKeyIsIgnoredAndDuplicatesAreNotRecorded() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        registry.use("ghost_1", "n1", "f");
        assertThat(registry.isEmpty()).isTrue();

        String key = registry.register(PlaceholderRegistry.AGENT, "uuid-1", "A", "n1", "agentId");
        registry.use(key, "n1", "agentId");
        assertThat(registry.get(key).usedBy()).hasSize(1);
        // Null node/field ids are normalised to empty strings, not NPEs.
        registry.use(key, null, null);
        assertThat(registry.get(key).usedBy()).contains(new PlaceholderRegistry.Use("", ""));
    }

    @Test
    void useListsAreCappedSoAHostileFileStaysSmall() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        String key =
                registry.register(PlaceholderRegistry.WORKFLOW, "w-1", "W", "n0", "workflowId");
        for (int i = 0; i < 40; i++) {
            registry.use(key, "n" + i, "f" + i);
        }
        assertThat(registry.get(key).usedBy()).hasSize(20);
    }

    @Test
    void entriesComeBackInRegistrationOrder() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        registry.register(PlaceholderRegistry.DEVICE, "d-1", "D", "n1", "deviceId");
        registry.register(PlaceholderRegistry.AGENT, "a-1", "A", "n1", "agentId");
        assertThat(registry.entries())
                .extracting(PlaceholderRegistry.Entry::key)
                .containsExactly("device_1", "agent_1");
        assertThat(registry.isEmpty()).isFalse();
    }

    @Test
    void isMappableIsExactlyTheResourceKindsAnImporterCanPoint() {
        assertThat(PlaceholderRegistry.isMappable(PlaceholderRegistry.AGENT)).isTrue();
        assertThat(PlaceholderRegistry.isMappable(PlaceholderRegistry.KNOWLEDGE_BASE)).isTrue();
        assertThat(PlaceholderRegistry.isMappable(PlaceholderRegistry.WORKFLOW)).isTrue();
        assertThat(PlaceholderRegistry.isMappable(PlaceholderRegistry.CONTACT_GROUP)).isTrue();
        assertThat(PlaceholderRegistry.isMappable(PlaceholderRegistry.DEVICE)).isTrue();
        // A credential is never imported; a destination has nothing to point at here.
        assertThat(PlaceholderRegistry.isMappable(PlaceholderRegistry.CREDENTIAL)).isFalse();
        assertThat(PlaceholderRegistry.isMappable(PlaceholderRegistry.SECRET)).isFalse();
        assertThat(PlaceholderRegistry.isMappable(PlaceholderRegistry.DESTINATION)).isFalse();
        assertThat(PlaceholderRegistry.isMappable(PlaceholderRegistry.UNKNOWN)).isFalse();
        assertThat(PlaceholderRegistry.isMappable("nonsense")).isFalse();
    }

    @Test
    void kindOfKeyReadsThePrefix() {
        assertThat(PlaceholderRegistry.kindOfKey("agent_3")).isEqualTo(PlaceholderRegistry.AGENT);
        assertThat(PlaceholderRegistry.kindOfKey("kb_1"))
                .isEqualTo(PlaceholderRegistry.KNOWLEDGE_BASE);
        assertThat(PlaceholderRegistry.kindOfKey("workflow_2"))
                .isEqualTo(PlaceholderRegistry.WORKFLOW);
        assertThat(PlaceholderRegistry.kindOfKey("group_1"))
                .isEqualTo(PlaceholderRegistry.CONTACT_GROUP);
        assertThat(PlaceholderRegistry.kindOfKey("device_1")).isEqualTo(PlaceholderRegistry.DEVICE);
        assertThat(PlaceholderRegistry.kindOfKey("destination_1"))
                .isEqualTo(PlaceholderRegistry.DESTINATION);
        assertThat(PlaceholderRegistry.kindOfKey("CREDENTIAL_n2_1"))
                .isEqualTo(PlaceholderRegistry.CREDENTIAL);
        assertThat(PlaceholderRegistry.kindOfKey("credential_1"))
                .isEqualTo(PlaceholderRegistry.CREDENTIAL);
        assertThat(PlaceholderRegistry.kindOfKey("secret:ORG_KEY"))
                .isEqualTo(PlaceholderRegistry.SECRET);
        assertThat(PlaceholderRegistry.kindOfKey("other")).isEqualTo(PlaceholderRegistry.UNKNOWN);
        assertThat(PlaceholderRegistry.kindOfKey(null)).isEqualTo(PlaceholderRegistry.UNKNOWN);
    }

    @Test
    void tokenAndKeyOfTokenRoundTrip() {
        assertThat(PlaceholderRegistry.token("agent_1")).isEqualTo("@placeholder:agent_1");
        assertThat(PlaceholderRegistry.keyOfToken("@placeholder:agent_1")).isEqualTo("agent_1");
        assertThat(PlaceholderRegistry.keyOfToken("@placeholder:secret:ORG_KEY"))
                .isEqualTo("secret:ORG_KEY");
    }

    @Test
    void keyOfTokenOnlyAcceptsAWholeStringToken() {
        assertThat(PlaceholderRegistry.keyOfToken(null)).isNull();
        assertThat(PlaceholderRegistry.keyOfToken("")).isNull();
        // Embedded tokens are not whole-string tokens.
        assertThat(PlaceholderRegistry.keyOfToken("see @placeholder:agent_1 here")).isNull();
        assertThat(PlaceholderRegistry.keyOfToken("@placeholder:")).isNull();
        assertThat(PlaceholderRegistry.keyOfToken("@placeholder:has space")).isNull();
        assertThat(PlaceholderRegistry.keyOfToken("@placeholder:" + "x".repeat(81))).isNull();
    }
}
