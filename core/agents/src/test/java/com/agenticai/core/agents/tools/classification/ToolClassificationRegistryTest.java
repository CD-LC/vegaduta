package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Unit tests for {@link ToolClassificationRegistry}, the service every enforcement
 * mechanism will consult.
 *
 * <p>The tests are organised around the two properties that make this component
 * worth having at all, and both are the kind that a smoke test would pass while
 * the real behaviour was broken:
 * <ol>
 *   <li><b>An unknown tool is BLOCKED, not permitted and not silently absent.</b>
 *       Asserted for a name nobody declared, for a blank name, and - the case that
 *       matters most - for a stored row that exists but is unusable, which must
 *       <i>not</i> fall through to the compiled-in platform default.</li>
 *   <li><b>Resolution order is exactly stored-exact, stored-pattern, built-in,
 *       blocked.</b> Asserted by giving the registry conflicting answers at two
 *       levels at once and checking which one wins, rather than by testing each
 *       level in isolation where any order would pass.</li>
 * </ol>
 *
 * <p>The repository is a Mockito mock: every method under test is pure logic over
 * what the repository returned, so a mock is both sufficient and much clearer about
 * which rows exist in each scenario than a database fixture would be.
 */
class ToolClassificationRegistryTest {

    private ToolClassificationRepository repository;
    private ToolClassificationRegistry registry;

    @BeforeEach
    void setUp() {
        repository = mock(ToolClassificationRepository.class);
        // No stubbing of findPatternRows() by default: Mockito returns an empty list
        // for a List-returning method, which is the "tenant has no wildcard rows"
        // case and the right default for most tests here.
        registry = new ToolClassificationRegistry(repository, new SimpleMeterRegistry());
    }

    private static ToolClassificationEntity row(
        String toolName, ToolTrustTier trust, ToolSensitivity sensitivity,
        ToolDeterminism determinism, ToolClassificationStatus status, boolean irreversible
    ) {
        return new ToolClassificationEntity(
            toolName, trust, sensitivity, determinism, status, null, irreversible, true,
            "TENANT_DECLARED", "declared by a test", "tester@example.com");
    }

    private static ToolCallback toolCallback(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException("not exercised by this test");
            }
        };
    }

    // ---- fail closed -------------------------------------------------------

    @Test
    void unknownToolResolvesToBlockedUnclassified() {
        when(repository.findByToolName("mcp__evilcorp__exfiltrate")).thenReturn(Optional.empty());

        ToolClassificationDecision decision = registry.resolve("mcp__evilcorp__exfiltrate");

        assertFalse(decision.isClassified(), "an unclassified tool must never resolve as classified");
        ToolClassificationDecision.Blocked blocked =
            assertInstanceOf(ToolClassificationDecision.Blocked.class, decision);
        assertEquals(ToolClassificationDecision.Reason.UNCLASSIFIED, blocked.reason());
        assertEquals("mcp__evilcorp__exfiltrate", blocked.toolName());
        assertEquals("unclassified - blocked (UNCLASSIFIED)", decision.describe());
        assertThrows(ToolNotClassifiedException.class, decision::classificationOrThrow);
    }

    @Test
    void thereIsNoMcpWildcardFallbackSoEveryMcpToolStartsBlocked() {
        // The quarantine-until-classified behaviour for MCP-discovered tools is
        // achieved by the ABSENCE of a rule, which is the only version of it that
        // cannot be accidentally deleted. Assert the absence.
        for (String name : List.of("mcp__github__create_issue", "mcp__slack__post_message", "mcp__x__y")) {
            when(repository.findByToolName(name)).thenReturn(Optional.empty());
            assertFalse(registry.resolve(name).isClassified(), "must start blocked: " + name);
        }
    }

    @Test
    void blankAndNullNamesAreBlockedRatherThanRejected() {
        assertFalse(registry.resolve((String) null).isClassified());
        assertFalse(registry.resolve("").isClassified());
        assertFalse(registry.resolve("   ").isClassified());
        // A malformed ToolDefinition must not reach the database at all.
        verify(repository, never()).findByToolName(anyString());
    }

    @Test
    void unusableStoredRowBlocksAndDoesNotFallBackToTheBuiltInDeclaration() {
        // This is the important one. google_gmail HAS a compiled-in declaration, so a
        // naive implementation that treated "row present but unparseable" the same as
        // "row absent" would silently revert a tenant's deliberate decision to the
        // platform default - a security regression that leaves no trace.
        ToolClassificationEntity quarantined = row(
            "google_gmail", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.EGRESS,
            ToolDeterminism.DURABLE, ToolClassificationStatus.QUARANTINED, true);
        when(repository.findByToolName("google_gmail")).thenReturn(Optional.of(quarantined));

        ToolClassificationDecision decision = registry.resolve("google_gmail");

        assertFalse(decision.isClassified());
        assertEquals(ToolClassificationDecision.Reason.QUARANTINED,
            assertInstanceOf(ToolClassificationDecision.Blocked.class, decision).reason());
        assertNotNull(BuiltInToolClassifications.all().get("google_gmail"),
            "fixture precondition: this test is only meaningful while google_gmail IS declared in code");
    }

    // ---- resolution order --------------------------------------------------

    @Test
    void storedExactRowWinsOverTheBuiltInDeclaration() {
        // A tenant tightening fetch_web_page from READ_PUBLIC to READ_TENANT must win
        // over what the platform shipped.
        ToolClassificationEntity override = row(
            "fetch_web_page", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.READ_TENANT,
            ToolDeterminism.SIDE_EFFECT, ToolClassificationStatus.ACTIVE, false);
        when(repository.findByToolName("fetch_web_page")).thenReturn(Optional.of(override));

        ToolClassification resolved = registry.resolve("fetch_web_page").classificationOrThrow();

        assertEquals(ToolSensitivity.READ_TENANT, resolved.sensitivity(), "the stored row must win");
        assertEquals(ToolSensitivity.READ_PUBLIC,
            BuiltInToolClassifications.all().get("fetch_web_page").sensitivity(),
            "fixture precondition: the code declaration must actually differ, or this test proves nothing");
    }

    @Test
    void storedExactRowWinsOverAStoredPatternRow() {
        ToolClassificationEntity exact = row(
            "mcp__github__read_issue", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.READ_PUBLIC,
            ToolDeterminism.SIDE_EFFECT, ToolClassificationStatus.ACTIVE, false);
        when(repository.findByToolName("mcp__github__read_issue")).thenReturn(Optional.of(exact));

        ToolClassification resolved = registry.resolve("mcp__github__read_issue").classificationOrThrow();

        assertEquals(ToolSensitivity.READ_PUBLIC, resolved.sensitivity());
        verify(repository, never()).findPatternRows();
    }

    @Test
    void storedPatternRowClassifiesAWholeMcpServerAndIsReKeyedToTheConcreteTool() {
        ToolClassificationEntity pattern = row(
            "mcp__github__*", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.WRITE_TENANT,
            ToolDeterminism.DURABLE, ToolClassificationStatus.ACTIVE, true);
        when(repository.findByToolName("mcp__github__create_issue")).thenReturn(Optional.empty());
        when(repository.findPatternRows()).thenReturn(List.of(pattern));

        ToolClassification resolved = registry.resolve("mcp__github__create_issue").classificationOrThrow();

        assertEquals(ToolSensitivity.WRITE_TENANT, resolved.sensitivity());
        assertEquals("mcp__github__create_issue", resolved.toolName(),
            "a classification naming the PATTERN would make every downstream log line name a tool that does not exist");
        assertTrue(resolved.requiresHumanConfirmation());
    }

    @Test
    void storedPatternRowThatDoesNotMatchIsIgnored() {
        ToolClassificationEntity pattern = row(
            "mcp__github__*", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.WRITE_TENANT,
            ToolDeterminism.DURABLE, ToolClassificationStatus.ACTIVE, true);
        when(repository.findByToolName("mcp__slack__post")).thenReturn(Optional.empty());
        when(repository.findPatternRows()).thenReturn(List.of(pattern));

        assertFalse(registry.resolve("mcp__slack__post").isClassified());
    }

    @Test
    void quarantinedPatternRowBlocksTheConcreteToolAndNamesBothInTheDetail() {
        ToolClassificationEntity pattern = row(
            "mcp__github__*", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.WRITE_TENANT,
            ToolDeterminism.DURABLE, ToolClassificationStatus.QUARANTINED, true);
        when(repository.findByToolName("mcp__github__create_issue")).thenReturn(Optional.empty());
        when(repository.findPatternRows()).thenReturn(List.of(pattern));

        ToolClassificationDecision.Blocked blocked = assertInstanceOf(
            ToolClassificationDecision.Blocked.class, registry.resolve("mcp__github__create_issue"));

        assertEquals(ToolClassificationDecision.Reason.QUARANTINED, blocked.reason());
        assertEquals("mcp__github__create_issue", blocked.toolName(),
            "the operator is looking for the concrete tool that stopped working");
        assertTrue(blocked.detail().contains("mcp__github__*"),
            "...but still needs to be told which pattern matched");
    }

    @Test
    void builtInDeclarationResolvesWhenNoRowExists() {
        when(repository.findByToolName("forward_attachment")).thenReturn(Optional.empty());

        ToolClassification resolved = registry.resolve("forward_attachment").classificationOrThrow();

        assertEquals(ToolSensitivity.EGRESS, resolved.sensitivity());
        assertTrue(resolved.irreversible());
        assertTrue(resolved.requiresHumanConfirmation());
    }

    @Test
    void builtInPatternDeclarationCoversDelegationToolsWithTenantDerivedNames() {
        // AgentToolProvider names these "ask_agent_" + slugify(agent name), so the
        // concrete names are tenant data and only a pattern can reach them.
        when(repository.findByToolName("ask_agent_support_bot")).thenReturn(Optional.empty());

        ToolClassification resolved = registry.resolve("ask_agent_support_bot").classificationOrThrow();

        assertEquals("ask_agent_support_bot", resolved.toolName());
        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, resolved.trust(),
            "a delegated answer is only as trusted as the callee's own tools");
    }

    @Test
    void resolveByToolCallbackUsesTheDispatchedName() {
        when(repository.findByToolName("fetch_web_page")).thenReturn(Optional.empty());

        ToolClassification resolved = registry.resolve(toolCallback("fetch_web_page")).classificationOrThrow();

        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, resolved.trust());
        verify(repository).findByToolName("fetch_web_page");
    }

    // ---- batch resolution --------------------------------------------------

    @Test
    void resolveAllReturnsAnEntryForEveryNameIncludingTheBlockedOnes() {
        ToolClassificationEntity stored = row(
            "internal_thing", ToolTrustTier.TRUSTED_INTERNAL, ToolSensitivity.READ_PUBLIC,
            ToolDeterminism.SIDE_EFFECT, ToolClassificationStatus.ACTIVE, false);
        when(repository.findByToolNameIn(any())).thenReturn(List.of(stored));

        Map<String, ToolClassificationDecision> decisions = registry.resolveAll(
            List.of("internal_thing", "fetch_web_page", "mcp__unknown__tool", "internal_thing"));

        assertEquals(3, decisions.size(), "duplicates collapse, nothing is dropped");
        assertTrue(decisions.get("internal_thing").isClassified());
        assertTrue(decisions.get("fetch_web_page").isClassified(), "resolved from the built-in declaration");
        assertFalse(decisions.get("mcp__unknown__tool").isClassified(),
            "a blocked tool must APPEAR in the map - an absent key would be read as 'fine'");
        assertEquals(ToolClassificationDecision.Reason.UNCLASSIFIED,
            assertInstanceOf(ToolClassificationDecision.Blocked.class,
                decisions.get("mcp__unknown__tool")).reason());
    }

    @Test
    void resolveAllTakesTwoQueriesRatherThanTwoPerTool() {
        when(repository.findByToolNameIn(any())).thenReturn(List.of());

        registry.resolveAll(List.of("a", "b", "c", "d", "e", "f", "g", "h"));

        verify(repository, times(1)).findByToolNameIn(any());
        verify(repository, times(1)).findPatternRows();
        verify(repository, never()).findByToolName(anyString());
    }

    @Test
    void resolveAllToleratesEmptyAndBlankInput() {
        assertTrue(registry.resolveAll(null).isEmpty());
        assertTrue(registry.resolveAll(List.of()).isEmpty());
        assertTrue(registry.resolveAll(List.of("", "   ")).isEmpty());
        verify(repository, never()).findByToolNameIn(any());
    }

    @Test
    void allClassifiedIsFalseWhenAnySingleToolIsUnclassified() {
        when(repository.findByToolNameIn(any())).thenReturn(List.of());

        assertTrue(registry.allClassified(List.of("fetch_web_page", "forward_attachment")));
        assertFalse(registry.allClassified(List.of("fetch_web_page", "mcp__unknown__tool")),
            "one unclassified tool must fail the whole set - that is what a registration gate needs");
    }

    @Test
    void requireClassificationThrowsForAnUnknownTool() {
        when(repository.findByToolName("nope")).thenReturn(Optional.empty());

        ToolNotClassifiedException thrown =
            assertThrows(ToolNotClassifiedException.class, () -> registry.requireClassification("nope"));
        assertEquals("nope", thrown.getToolName());
        assertEquals(ToolClassificationDecision.Reason.UNCLASSIFIED, thrown.getReason());
    }

    // ---- declaration / quarantine -----------------------------------------

    @Test
    void declareUpsertsAnActiveRowWithTheDeclaredValues() {
        ToolClassification classification = new ToolClassification(
            "mcp__github__create_issue", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.WRITE_TENANT,
            ToolDeterminism.DURABLE, "repo:write", true, true);

        registry.declare(classification, "MCP_IMPORT", "imported from the github MCP server", "admin@example.com");

        verify(repository).upsert(
            any(UUID.class),
            eq("mcp__github__create_issue"),
            eq("UNTRUSTED_EXTERNAL"),
            eq("WRITE_TENANT"),
            eq("DURABLE"),
            eq("ACTIVE"),
            eq("repo:write"),
            eq(true),
            eq(true),
            eq("MCP_IMPORT"),
            eq("imported from the github MCP server"),
            eq("admin@example.com"),
            any(Instant.class));
    }

    @Test
    void declareAlwaysWritesActiveSoItCannotBeUsedToClearAQuarantine() {
        // Quarantine has to be cleared by a human who looked, not as a side effect of
        // re-importing a manifest - so quarantine() and declare() are separate APIs
        // and only quarantine() ever writes QUARANTINED.
        registry.declare(new ToolClassification(
                "t", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.READ_TENANT,
                ToolDeterminism.SIDE_EFFECT, null, false, true),
            "TENANT_DECLARED", null, null);

        verify(repository).upsert(
            any(UUID.class), eq("t"), anyString(), anyString(), anyString(), eq("ACTIVE"),
            eq((String) null), anyBoolean(), anyBoolean(), eq("TENANT_DECLARED"), eq((String) null),
            eq("system"), any(Instant.class));
    }

    @Test
    void quarantineReportsWhetherThereWasAnythingToQuarantine() {
        when(repository.updateStatus(eq("known_tool"), eq("QUARANTINED"), anyString(), any(Instant.class)))
            .thenReturn(1);
        when(repository.updateStatus(eq("no_row_tool"), eq("QUARANTINED"), anyString(), any(Instant.class)))
            .thenReturn(0);

        assertTrue(registry.quarantine("known_tool", "manifest hash changed"));
        assertFalse(registry.quarantine("no_row_tool", "manifest hash changed"),
            "no row is not a failure to act - the tool is already blocked as UNCLASSIFIED");
    }

    @Test
    void clearQuarantineReturnsTheRowToActive() {
        when(repository.updateStatus(eq("t"), eq("ACTIVE"), anyString(), any(Instant.class))).thenReturn(1);

        assertTrue(registry.clearQuarantine("t", "reviewed by security, descriptions unchanged"));
    }

    // ---- drift report ------------------------------------------------------

    @Test
    void driftReportFlagsAnOverriddenFirstPartyTool() {
        ToolClassificationEntity override = row(
            "fetch_web_page", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.READ_PUBLIC,
            ToolDeterminism.SIDE_EFFECT, ToolClassificationStatus.ACTIVE, false);
        when(repository.findAllByOrderByToolNameAsc()).thenReturn(List.of(override));

        List<ToolClassificationRegistry.ClassificationDrift> drift = registry.driftFromBuiltInDeclarations();

        ToolClassificationRegistry.ClassificationDrift overridden = drift.stream()
            .filter(d -> d.toolName().equals("fetch_web_page"))
            .findFirst()
            .orElseThrow();
        assertEquals(ToolClassificationRegistry.DriftKind.OVERRIDDEN, overridden.kind());
        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, overridden.declared().trust());
        assertEquals(ToolTrustTier.TENANT_SCOPED, overridden.stored().trust(),
            "a tenant down-grading the platform's taint source is exactly what this report exists to surface");
    }

    @Test
    void driftReportFlagsUnusableRowsAndRowsUnknownToTheCode() {
        ToolClassificationEntity quarantinedFirstParty = row(
            "forward_attachment", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.EGRESS,
            ToolDeterminism.DURABLE, ToolClassificationStatus.QUARANTINED, true);
        ToolClassificationEntity orphan = row(
            "some_renamed_tool", ToolTrustTier.TENANT_SCOPED, ToolSensitivity.READ_TENANT,
            ToolDeterminism.SIDE_EFFECT, ToolClassificationStatus.ACTIVE, false);
        ToolClassificationEntity patternRow = row(
            "mcp__github__*", ToolTrustTier.UNTRUSTED_EXTERNAL, ToolSensitivity.WRITE_TENANT,
            ToolDeterminism.DURABLE, ToolClassificationStatus.ACTIVE, false);
        when(repository.findAllByOrderByToolNameAsc())
            .thenReturn(List.of(quarantinedFirstParty, orphan, patternRow));

        List<ToolClassificationRegistry.ClassificationDrift> drift = registry.driftFromBuiltInDeclarations();

        assertEquals(ToolClassificationRegistry.DriftKind.UNUSABLE_ROW,
            drift.stream().filter(d -> d.toolName().equals("forward_attachment")).findFirst().orElseThrow().kind());
        assertEquals(ToolClassificationRegistry.DriftKind.UNKNOWN_TO_CODE,
            drift.stream().filter(d -> d.toolName().equals("some_renamed_tool")).findFirst().orElseThrow().kind());
        assertTrue(drift.stream().noneMatch(d -> d.toolName().equals("mcp__github__*")),
            "wildcard rows are not comparable to exact code declarations and must not be reported as drift");
    }

    @Test
    void driftReportListsEveryCodeDeclarationThatHasNoRowForThisTenant() {
        when(repository.findAllByOrderByToolNameAsc()).thenReturn(List.of());

        List<ToolClassificationRegistry.ClassificationDrift> drift = registry.driftFromBuiltInDeclarations();

        assertEquals(BuiltInToolClassifications.all().size(), drift.size());
        assertTrue(drift.stream().allMatch(d -> d.kind() == ToolClassificationRegistry.DriftKind.NOT_STORED));
    }
}
