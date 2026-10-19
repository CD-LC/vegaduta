package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link McpAnnotationClassifier}: the hint-to-classification mapping, the flag, the tenant/global scoping,
 * and - through a real {@link ToolClassificationRegistry} over a mocked repository - that annotation-derived
 * classification only ever fills the gap left by stored rows and built-in declarations.
 */
class McpAnnotationClassifierTest {

    private static final String TOOL = "mcp__acme__do_thing";

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static McpToolHints hints(Boolean readOnly, Boolean destructive, Boolean idempotent, Boolean openWorld) {
        return new McpToolHints(readOnly, destructive, idempotent, openWorld);
    }

    private static ToolClassification mapped(McpToolHints hints) {
        return McpAnnotationClassifier.map(TOOL, hints).orElseThrow();
    }

    // ---- mapping ------------------------------------------------------------------

    @Test
    void readOnlyBecomesANonConsequentialUntrustedRead() {
        ToolClassification c = mapped(hints(true, null, null, null));
        assertEquals(ToolSensitivity.READ_TENANT, c.sensitivity());
        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, c.trust());
        assertFalse(c.irreversible());
        assertFalse(c.requiresHumanConfirmation());
        assertFalse(c.isTaintSink());
        assertTrue(c.producesTaintedOutput());
    }

    @Test
    void readOnlyWinsOverOpenWorldAndDestructiveClaims() {
        // Same precedent as fetch_web_page: an open-world READ is still a read.
        ToolClassification openWorldRead = mapped(hints(true, null, null, true));
        assertEquals(ToolSensitivity.READ_TENANT, openWorldRead.sensitivity());
        assertFalse(openWorldRead.requiresHumanConfirmation());
        // Spec: destructiveHint is meaningful only when readOnlyHint is false.
        ToolClassification contradictory = mapped(hints(true, true, null, null));
        assertEquals(ToolSensitivity.READ_TENANT, contradictory.sensitivity());
        assertFalse(contradictory.irreversible());
    }

    @Test
    void destructiveBecomesAnIrreversibleWriteThatNeedsConfirmation() {
        ToolClassification c = mapped(hints(false, true, null, null));
        assertEquals(ToolSensitivity.WRITE_TENANT, c.sensitivity());
        assertEquals(ToolDeterminism.DURABLE, c.determinism());
        assertTrue(c.irreversible());
        assertTrue(c.requiresHumanConfirmation());
        assertTrue(c.isTaintSink());
        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, c.trust());
    }

    @Test
    void nonReadOnlyToolWithoutADestructiveHintFollowsTheSpecDefaultOfDestructive() {
        ToolClassification explicitNotReadOnly = mapped(hints(false, null, null, null));
        assertTrue(explicitNotReadOnly.irreversible());
        assertTrue(explicitNotReadOnly.requiresHumanConfirmation());
    }

    @Test
    void additiveWriteIsNotIrreversibleAndNeedsNoConfirmation() {
        ToolClassification c = mapped(hints(false, false, null, null));
        assertEquals(ToolSensitivity.WRITE_TENANT, c.sensitivity());
        assertFalse(c.irreversible());
        assertFalse(c.requiresHumanConfirmation());
        assertTrue(c.isTaintSink(), "a write is still a taint sink");
    }

    @Test
    void idempotentAdditiveClosedWorldWriteIsRetrySafe() {
        assertEquals(ToolDeterminism.SIDE_EFFECT, mapped(hints(false, false, true, null)).determinism());
        // ...but never when it is destructive, or reaches the open world.
        assertEquals(ToolDeterminism.DURABLE, mapped(hints(false, true, true, null)).determinism());
        assertEquals(ToolDeterminism.DURABLE, mapped(hints(false, false, true, true)).determinism());
    }

    @Test
    void openWorldNonReadToolIsEgress() {
        ToolClassification c = mapped(hints(false, false, null, true));
        assertEquals(ToolSensitivity.EGRESS, c.sensitivity());
        assertFalse(c.irreversible(), "destructiveHint=false keeps it non-irreversible");
        ToolClassification destructiveEgress = mapped(hints(null, true, null, true));
        assertEquals(ToolSensitivity.EGRESS, destructiveEgress.sensitivity());
        assertTrue(destructiveEgress.requiresHumanConfirmation());
    }

    @Test
    void openWorldAloneElevatesButOnlyWhenExplicitlyTrue() {
        ToolClassification c = mapped(hints(null, null, null, true));
        assertEquals(ToolSensitivity.EGRESS, c.sensitivity());
        assertTrue(c.irreversible(), "not read-only: the spec's destructive default applies");
        // openWorldHint=false (or only a title / idempotentHint) says nothing about risk: unannotated.
        assertTrue(McpAnnotationClassifier.map(TOOL, hints(null, null, null, false)).isEmpty());
        assertTrue(McpAnnotationClassifier.map(TOOL, hints(null, null, true, null)).isEmpty());
        assertTrue(McpAnnotationClassifier.map(TOOL, McpToolHints.NONE).isEmpty());
        assertTrue(McpAnnotationClassifier.map(TOOL, null).isEmpty());
    }

    @Test
    void everyMappedToolStaysUntrustedAndRequiresToolContext() {
        for (Boolean readOnly : new Boolean[] {null, true, false}) {
            for (Boolean destructive : new Boolean[] {null, true, false}) {
                for (Boolean openWorld : new Boolean[] {null, true, false}) {
                    Optional<ToolClassification> c = McpAnnotationClassifier.map(TOOL, hints(readOnly, destructive, null, openWorld));
                    c.ifPresent(classification -> {
                        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, classification.trust());
                        assertTrue(classification.needsToolContext());
                        assertEquals(TOOL, classification.toolName());
                    });
                }
            }
        }
    }

    @Test
    void sdkAnnotationsAreExtractedNullSafely() {
        assertEquals(McpToolHints.NONE, McpToolHints.from(null));
        McpToolHints h = McpToolHints.from(new McpSchema.ToolAnnotations("t", true, false, true, false, null));
        assertEquals(Boolean.TRUE, h.readOnly());
        assertEquals(Boolean.FALSE, h.destructive());
        assertEquals(Boolean.TRUE, h.idempotent());
        assertEquals(Boolean.FALSE, h.openWorld());
    }

    // ---- flag and scoping ------------------------------------------------------------

    @Test
    void disabledClassifierCapturesNothingAndClassifiesNothing() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(false);
        classifier.replaceServer("t1", "mcp__acme__", Map.of(TOOL, hints(false, true, null, null)));
        assertTrue(classifier.classify("t1", TOOL).isEmpty());
        assertFalse(classifier.isEnabled());
    }

    @Test
    void onlyMcpNamedToolsAreClassified() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        classifier.replaceServer("t1", "mcp__acme__", Map.of(TOOL, hints(false, true, null, null)));
        assertTrue(classifier.classify("t1", "search_knowledge_base").isEmpty());
        assertTrue(classifier.classify("t1", null).isEmpty());
        assertTrue(classifier.classify("t1", TOOL).isPresent());
    }

    @Test
    void tenantHintsAreIsolatedAndStaticHintsAreSharedAfterTheTenantsOwn() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        classifier.replaceServer("t1", "mcp__acme__", Map.of(TOOL, hints(true, null, null, null)));
        classifier.replaceServer(McpAnnotationClassifier.GLOBAL_SCOPE, "mcp__shared__",
            Map.of("mcp__shared__ping", hints(true, null, null, null)));

        assertEquals(ToolSensitivity.READ_TENANT, classifier.classify("t1", TOOL).orElseThrow().sensitivity());
        assertTrue(classifier.classify("t2", TOOL).isEmpty(), "another tenant's registration is not annotated");
        assertTrue(classifier.classify("t1", "mcp__shared__ping").isPresent());
        assertTrue(classifier.classify("t2", "mcp__shared__ping").isPresent());
        assertTrue(classifier.classify(null, "mcp__shared__ping").isPresent());
    }

    @Test
    void replacingAServerDropsToolsItNoLongerAdvertisesAndLeavesOtherServersAlone() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        classifier.replaceServer("t1", "mcp__acme__", Map.of(
            "mcp__acme__old", hints(false, true, null, null),
            "mcp__acme__keep", hints(true, null, null, null)));
        classifier.replaceServer("t1", "mcp__other__", Map.of("mcp__other__x", hints(true, null, null, null)));

        classifier.replaceServer("t1", "mcp__acme__", Map.of("mcp__acme__keep", hints(true, null, null, null)));

        assertTrue(classifier.classify("t1", "mcp__acme__old").isEmpty());
        assertTrue(classifier.classify("t1", "mcp__acme__keep").isPresent());
        assertTrue(classifier.classify("t1", "mcp__other__x").isPresent());

        classifier.forgetServer("t1", "mcp__acme__");
        assertTrue(classifier.classify("t1", "mcp__acme__keep").isEmpty());
    }

    @Test
    void toolsWithoutARiskHintAreNotStored() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        classifier.replaceServer("t1", "mcp__acme__", Map.of(TOOL, McpToolHints.NONE));
        assertTrue(classifier.classify("t1", TOOL).isEmpty());
    }

    // ---- registry integration ------------------------------------------------------------

    private static ToolClassificationRegistry registryWith(
        ToolClassificationRepository repository, McpAnnotationClassifier classifier
    ) {
        ToolClassificationRegistry registry = new ToolClassificationRegistry(repository, new SimpleMeterRegistry());
        registry.setMcpAnnotations(classifier);
        return registry;
    }

    @Test
    void registryResolvesAnAnnotatedUnclassifiedMcpToolFromItsHintsForTheCallingTenant() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        classifier.replaceServer("t1", "mcp__acme__", Map.of(TOOL, hints(false, true, null, null)));
        ToolClassificationRegistry registry = registryWith(mock(ToolClassificationRepository.class), classifier);

        TenantContext.set(TenantContext.of("t1", "u", Set.of()));
        ToolClassificationDecision decision = registry.resolve(TOOL);
        ToolClassification c = assertInstanceOf(ToolClassificationDecision.Classified.class, decision).classification();
        assertTrue(c.requiresHumanConfirmation());

        TenantContext.set(TenantContext.of("t2", "u", Set.of()));
        assertInstanceOf(ToolClassificationDecision.Blocked.class, registry.resolve(TOOL),
            "another tenant has no hints for this name: still UNCLASSIFIED, today's behaviour");
    }

    @Test
    void registryResolveAllUsesTheSameFallback() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        classifier.replaceServer("t1", "mcp__acme__", Map.of(TOOL, hints(true, null, null, null)));
        ToolClassificationRegistry registry = registryWith(mock(ToolClassificationRepository.class), classifier);

        TenantContext.set(TenantContext.of("t1", "u", Set.of()));
        Map<String, ToolClassificationDecision> all = registry.resolveAll(java.util.List.of(TOOL, "mcp__acme__unannotated", "fetch_web_page"));

        assertInstanceOf(ToolClassificationDecision.Classified.class, all.get(TOOL));
        assertInstanceOf(ToolClassificationDecision.Blocked.class, all.get("mcp__acme__unannotated"));
        assertInstanceOf(ToolClassificationDecision.Classified.class, all.get("fetch_web_page"));
    }

    @Test
    void aStoredTenantRowBeatsTheServersHints() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        // The server claims "read only"; the tenant admin says it is a credentialed irreversible write.
        classifier.replaceServer("t1", "mcp__acme__", Map.of(TOOL, hints(true, null, null, null)));
        ToolClassificationRepository repository = mock(ToolClassificationRepository.class);
        ToolClassificationEntity adminRow = new ToolClassificationEntity(
            TOOL, ToolTrustTier.TENANT_SCOPED, ToolSensitivity.CREDENTIALED, ToolDeterminism.DURABLE,
            ToolClassificationStatus.ACTIVE, null, true, true, "TENANT_DECLARED", "admin override", "admin@example.com");
        org.mockito.Mockito.when(repository.findByToolName(TOOL)).thenReturn(Optional.of(adminRow));
        ToolClassificationRegistry registry = registryWith(repository, classifier);

        TenantContext.set(TenantContext.of("t1", "u", Set.of()));
        ToolClassification c = assertInstanceOf(ToolClassificationDecision.Classified.class, registry.resolve(TOOL)).classification();

        assertEquals(ToolSensitivity.CREDENTIALED, c.sensitivity());
        assertEquals(ToolTrustTier.TENANT_SCOPED, c.trust());
    }

    @Test
    void aBuiltInDeclarationBeatsAMcpToolThatReusesItsName() {
        // Names are namespaced mcp__*, so this cannot happen in practice - pinned anyway so the order is explicit.
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        ToolClassificationRegistry registry = registryWith(mock(ToolClassificationRepository.class), classifier);
        ToolClassification c = assertInstanceOf(ToolClassificationDecision.Classified.class, registry.resolve("fetch_web_page")).classification();
        assertEquals(ToolSensitivity.READ_PUBLIC, c.sensitivity());
    }

    @Test
    void registryWithoutAClassifierOrWithItDisabledIsUnchanged() {
        ToolClassificationRegistry plain = new ToolClassificationRegistry(
            mock(ToolClassificationRepository.class), new SimpleMeterRegistry());
        assertInstanceOf(ToolClassificationDecision.Blocked.class, plain.resolve(TOOL));

        McpAnnotationClassifier disabled = new McpAnnotationClassifier(false);
        disabled.replaceServer("t1", "mcp__acme__", Map.of(TOOL, hints(false, true, null, null)));
        ToolClassificationRegistry withDisabled = registryWith(mock(ToolClassificationRepository.class), disabled);
        TenantContext.set(TenantContext.of("t1", "u", Set.of()));
        assertInstanceOf(ToolClassificationDecision.Blocked.class, withDisabled.resolve(TOOL));
    }

    @Test
    void aDestructiveHintYieldsTheClassificationTheConfirmationGateKeysOn() {
        McpAnnotationClassifier classifier = new McpAnnotationClassifier(true);
        classifier.replaceServer("t1", "mcp__acme__", Map.of(TOOL, hints(false, true, null, null)));
        ToolClassificationRegistry registry = registryWith(mock(ToolClassificationRepository.class), classifier);
        TenantContext.set(TenantContext.of("t1", "u", Set.of()));

        ToolClassification c = assertInstanceOf(ToolClassificationDecision.Classified.class, registry.resolve(TOOL)).classification();

        // requiresHumanConfirmation() is the exact predicate AgentToolGovernance.decide uses to pick REQUIRE_APPROVAL.
        assertTrue(c.requiresHumanConfirmation());
        assertEquals("UNTRUSTED_EXTERNAL/WRITE_TENANT/DURABLE+irreversible", c.riskLabel());
    }
}
