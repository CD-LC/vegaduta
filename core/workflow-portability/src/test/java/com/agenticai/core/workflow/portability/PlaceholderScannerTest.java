package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The read-only scan that lists the holes an uploaded file already carries: {@code @placeholder:}
 * tokens, {@code [[CREDENTIAL_..]]} markers and {@code {{secret.NAME}}} vault references, each
 * recorded with the node and field it was seen in.
 */
class PlaceholderScannerTest {

    @Test
    void wholeStringTokensRegisterUnderTheirOwnKey() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        PlaceholderScanner.scan(
                Map.of("agentId", "@placeholder:agent_1"), "n1", "Fetch", registry, Map.of());

        PlaceholderRegistry.Entry entry = registry.get("agent_1");
        assertThat(entry).isNotNull();
        assertThat(entry.kind()).isEqualTo(PlaceholderRegistry.AGENT);
        assertThat(entry.label()).isEqualTo("Fetch");
        assertThat(entry.usedBy()).containsExactly(new PlaceholderRegistry.Use("n1", "agentId"));
    }

    @Test
    void aFileSuppliedLabelWinsOverTheNodeLabel() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        PlaceholderScanner.scan(
                Map.of("agentId", "@placeholder:agent_1"),
                "n1",
                "Fetch",
                registry,
                Map.of("agent_1", "Researcher"));
        assertThat(registry.get("agent_1").label()).isEqualTo("Researcher");
    }

    @Test
    void tokensAreSkippedWhenTheCallerAsksForMarkersOnly() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        PlaceholderScanner.scan(
                Map.of("agentId", "@placeholder:agent_1"),
                "n1",
                "Fetch",
                registry,
                Map.of(),
                false);
        assertThat(registry.isEmpty()).isTrue();
    }

    @Test
    void credentialMarkersRegisterByNameOncePerMarker() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        PlaceholderScanner.scan(
                Map.of(
                        "token",
                        "a [[CREDENTIAL_n1_1]] and [[CREDENTIAL_n1_1]] plus [[CREDENTIAL_n1_2]]"),
                "n1",
                "Call API",
                registry,
                Map.of());

        assertThat(registry.get("CREDENTIAL_n1_1")).isNotNull();
        assertThat(registry.get("CREDENTIAL_n1_1").kind())
                .isEqualTo(PlaceholderRegistry.CREDENTIAL);
        assertThat(registry.get("CREDENTIAL_n1_1").label()).contains("Call API");
        assertThat(registry.get("CREDENTIAL_n1_2")).isNotNull();
    }

    @Test
    void vaultReferencesRegisterAsSecretKind() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        PlaceholderScanner.scan(
                Map.of("header", "Bearer {{secret.ORG_KEY}} or {{ secret:OTHER_KEY }}"),
                "n1",
                "Call API",
                registry,
                Map.of());

        assertThat(registry.get("secret:ORG_KEY").kind()).isEqualTo(PlaceholderRegistry.SECRET);
        assertThat(registry.get("secret:OTHER_KEY").kind()).isEqualTo(PlaceholderRegistry.SECRET);
        assertThat(registry.get("secret:ORG_KEY").label()).isEqualTo("ORG_KEY");
    }

    @Test
    void nestedMapsAndListsAreWalked() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        PlaceholderScanner.scan(
                Map.of(
                        "outer",
                        Map.of(
                                "list",
                                List.of(
                                        Map.of("deep", "@placeholder:kb_2"),
                                        List.of("{{secret.DEEP}}")))),
                "n1",
                "Node",
                registry,
                Map.of());

        assertThat(registry.get("kb_2").usedBy())
                .containsExactly(new PlaceholderRegistry.Use("n1", "deep"));
        assertThat(registry.get("secret:DEEP")).isNotNull();
    }

    @Test
    void nonStringLeavesAndAnEmptyTreeAreHarmless() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        PlaceholderScanner.scan(
                Map.of("n", 5, "b", true, "x", List.of(1, 2.5, Map.of("y", false))),
                "n1",
                "Node",
                registry,
                Map.of());
        assertThat(registry.isEmpty()).isTrue();

        PlaceholderScanner.scan(null, "n1", "Node", registry, Map.of());
        PlaceholderScanner.scan(Map.of(), "n1", "Node", registry, Map.of());
        assertThat(registry.isEmpty()).isTrue();
    }
}
