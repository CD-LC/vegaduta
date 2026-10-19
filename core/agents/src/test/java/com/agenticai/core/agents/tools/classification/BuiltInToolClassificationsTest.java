package com.agenticai.core.agents.tools.classification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Invariant tests over the compiled-in declaration table in
 * {@link BuiltInToolClassifications}.
 *
 * <p>These are not "does the map contain X" tests, which would just restate the
 * file. They assert the <b>properties the table must keep having</b> as tools are
 * added - the ones a reviewer of a future pull request would otherwise have to
 * notice by eye:
 * <ul>
 *   <li>Structural consistency: the key and the classification's own
 *       {@code toolName} cannot drift apart, and exact entries cannot smuggle in a
 *       wildcard.</li>
 *   <li>Cross-dimension coherence: anything irreversible must actually trigger
 *       human confirmation, and anything that moves bytes off the platform must be
 *       {@code DURABLE}.</li>
 *   <li>Two documented conventions that are deliberately uniform today and would be
 *       a decision to break - no {@code PURE} tool exists, and every entry declares
 *       {@code needsToolContext}. These are canaries: if you legitimately break one,
 *       update the test and the class javadoc together.</li>
 * </ul>
 */
class BuiltInToolClassificationsTest {

    @Test
    void tableIsNotEmptyAndIsUnmodifiable() {
        Map<String, ToolClassification> all = BuiltInToolClassifications.all();
        assertFalse(all.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> all.remove("fetch_web_page"),
            "a caller must not be able to un-declare a tool at runtime");
        assertThrows(UnsupportedOperationException.class, () -> BuiltInToolClassifications.allPatterns().clear());
    }

    @Test
    void everyEntryIsKeyedByItsOwnToolName() {
        // A mismatch would make the drift report compare a stored row against the
        // wrong declaration, and would make resolve() hand back a classification
        // naming a different tool.
        for (Map.Entry<String, ToolClassification> entry : BuiltInToolClassifications.all().entrySet()) {
            assertEquals(entry.getKey(), entry.getValue().toolName(),
                "declaration keyed '" + entry.getKey() + "' names '" + entry.getValue().toolName() + "'");
        }
    }

    @Test
    void exactEntriesContainNoWildcardAndPatternEntriesDo() {
        for (String name : BuiltInToolClassifications.all().keySet()) {
            assertFalse(name.contains("*"),
                "'" + name + "' is in the exact map but looks like a pattern - it would never match anything");
        }
        for (ToolClassification pattern : BuiltInToolClassifications.allPatterns()) {
            assertTrue(pattern.toolName().contains("*"),
                "'" + pattern.toolName() + "' is in the pattern list but has no wildcard");
        }
    }

    @Test
    void everyIrreversibleDeclarationActuallyTriggersHumanConfirmation() {
        // If a tool is marked irreversible but sits at READ_PUBLIC/READ_TENANT, the
        // flag does nothing and the author almost certainly meant something else.
        for (ToolClassification classification : BuiltInToolClassifications.all().values()) {
            if (classification.irreversible()) {
                assertTrue(classification.requiresHumanConfirmation(),
                    classification.toolName() + " is irreversible but would never prompt a human "
                        + "(sensitivity=" + classification.sensitivity() + ")");
            }
        }
    }

    @Test
    void everyEgressDeclarationIsDurable() {
        // Moving bytes to a caller-named destination is by definition an effect this
        // platform cannot absorb, so a SIDE_EFFECT egress tool would be a
        // classification error that makes a blind retry look acceptable.
        for (ToolClassification classification : BuiltInToolClassifications.all().values()) {
            if (classification.sensitivity() == ToolSensitivity.EGRESS) {
                assertEquals(ToolDeterminism.DURABLE, classification.determinism(),
                    classification.toolName() + " is EGRESS but not DURABLE - a retry would send twice");
            }
        }
    }

    @Test
    void everyTaintSourceIsAlsoRecognisedBySensitivityOrIsAPureRead() {
        // Sanity check on the orthogonality claim: an UNTRUSTED_EXTERNAL tool is
        // allowed to be any sensitivity at all, including READ_PUBLIC
        // (fetch_web_page). This test exists to document that the combination is
        // intentional, by asserting that at least one such entry exists - if a future
        // refactor collapsed trust and sensitivity into one field, this fails.
        assertTrue(BuiltInToolClassifications.all().values().stream()
                .anyMatch(c -> c.trust() == ToolTrustTier.UNTRUSTED_EXTERNAL
                    && c.sensitivity() == ToolSensitivity.READ_PUBLIC),
            "trust and sensitivity must remain independent dimensions");
    }

    @Test
    void noFirstPartyToolIsDeclaredPureToday() {
        // Documented in ToolDeterminism.PURE's javadoc and in the migration: nothing
        // first-party currently clears the bar (a clock read, a model call and a read
        // of mutable state all fail it). This is a canary - if you add a genuinely
        // pure tool, change this test and that javadoc in the same commit rather than
        // weakening either quietly.
        for (ToolClassification classification : BuiltInToolClassifications.all().values()) {
            assertFalse(classification.determinism() == ToolDeterminism.PURE,
                classification.toolName() + " is declared PURE; confirm it touches neither the clock, "
                    + "the network, a database nor a model, then update this test and ToolDeterminism's javadoc");
        }
    }

    @Test
    void everyDeclarationRequiresToolContext() {
        // The conservative convention stated once in the class javadoc: declaring
        // true fails closed (worst case a refusal to bind), so no entry is narrowed
        // without evidence.
        for (ToolClassification classification : BuiltInToolClassifications.all().values()) {
            assertTrue(classification.needsToolContext(),
                classification.toolName() + " no longer requires ToolContext - if that is proven, "
                    + "record the evidence in its entry comment and relax this test");
        }
    }

    @Test
    void findReturnsNullRatherThanADefaultForAnUnknownTool() {
        assertNull(BuiltInToolClassifications.find("definitely_not_a_tool"));
        assertNull(BuiltInToolClassifications.find(null));
        assertNull(BuiltInToolClassifications.find("  "));
        assertNull(BuiltInToolClassifications.find("mcp__github__create_issue"),
            "there must be no mcp__* catch-all - an unclassified MCP tool stays blocked");
    }

    @Test
    void findMatchesExactNamesBeforePatterns() {
        assertNotNull(BuiltInToolClassifications.find("fetch_web_page"));
        assertEquals(ToolSensitivity.READ_PUBLIC,
            BuiltInToolClassifications.find("fetch_web_page").sensitivity());
    }

    @Test
    void findReKeysAPatternMatchToTheConcreteToolName() {
        ToolClassification resolved = BuiltInToolClassifications.find("ask_agent_billing_helper");

        assertNotNull(resolved, "ask_agent_* must cover the delegation tools");
        assertEquals("ask_agent_billing_helper", resolved.toolName());
        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, resolved.trust());
        // The pattern declaration itself is untouched by the re-key.
        assertEquals("ask_agent_*", BuiltInToolClassifications.allPatterns().get(0).toolName());
    }

    @Test
    void globMatchingTreatsRegexMetacharactersLiterally() {
        // \Q...\E quoting is what stops a tool name containing '.' or '+' from being
        // interpreted as a pattern - otherwise 'a.c' would match 'abc'.
        assertTrue(BuiltInToolClassifications.matchesGlob("mcp__github__*", "mcp__github__create_issue"));
        assertFalse(BuiltInToolClassifications.matchesGlob("mcp__github__*", "mcp__gitlab__create_issue"));
        assertTrue(BuiltInToolClassifications.matchesGlob("exact_name", "exact_name"));
        assertFalse(BuiltInToolClassifications.matchesGlob("a.c", "abc"),
            "'.' must be literal, not a regex wildcard");
        assertTrue(BuiltInToolClassifications.matchesGlob("a.c", "a.c"));
        assertTrue(BuiltInToolClassifications.matchesGlob("*", "anything_at_all"));
    }

    @Test
    void deepResearchToolsAreDeclaredLikeFetchWebPage() {
        // deep_research reads and returns full third-party pages, so it is a taint source exactly like
        // fetch_web_page; verify_citations echoes slices of those pages and must not launder them.
        ToolClassification fetch = BuiltInToolClassifications.all().get("fetch_web_page");
        for (String name : new String[] {"deep_research", "verify_citations"}) {
            ToolClassification c = BuiltInToolClassifications.all().get(name);
            assertNotNull(c, name + " must be declared, or taint tracking and the lethal-trifecta check never see it");
            assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL, c.trust(), name);
            assertEquals(ToolSensitivity.READ_PUBLIC, c.sensitivity(), name);
            assertEquals(fetch.determinism(), c.determinism(), name);
            assertFalse(c.irreversible(), name);
        }
    }

    @Test
    void theTwoPrimaryTaintSourcesAreDeclaredUntrusted() {
        // These two are the load-bearing classifications for the taint gate, and both
        // are counter-intuitive enough that a future "cleanup" could plausibly demote
        // them: a public page read looks harmless, and the knowledge base is the
        // tenant's own data.
        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL,
            BuiltInToolClassifications.all().get("fetch_web_page").trust());
        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL,
            BuiltInToolClassifications.all().get("search_knowledge_base").trust(),
            "taint must survive storage - a chunk ingested from the web stays untrusted when retrieved");
        assertEquals(ToolTrustTier.UNTRUSTED_EXTERNAL,
            BuiltInToolClassifications.all().get("recall_related_facts").trust(),
            "same reasoning for long-term memory");
    }

    @Test
    void webSearchAndBrowserAgentsAreTaintSourcesAndSkillReadsAreNotUnknownSinks() {
        for (String source : new String[] {"web_search", "web_task", "delegate_parallel", "run_subtasks", "search_conversation_history", "run_skill_script"}) {
            ToolClassification c = BuiltInToolClassifications.all().get(source);
            assertNotNull(c, source + " ingests outside content and must be a declared taint source");
            assertTrue(c.producesTaintedOutput(), source);
        }
        assertTrue(BuiltInToolClassifications.all().get("web_task").isTaintSink(), "a browser agent acts on sites");
        assertFalse(BuiltInToolClassifications.all().get("web_task").requiresHumanConfirmation(),
            "declaring it must not add a confirmation prompt to existing agents");
        for (String read : new String[] {"load_skill", "read_skill_file"}) {
            ToolClassification c = BuiltInToolClassifications.all().get(read);
            assertNotNull(c, read);
            assertFalse(c.isTaintSink(), read + " is a read, not an action to approve");
            assertFalse(c.producesTaintedOutput(), read + " carries reviewed instructions the model must follow");
        }
    }

    /**
     * Ratchet: every tool the platform registers (found by scanning the ToolCallback sources of all
     * core modules) needs a built-in classification, or it is a taint SINK that never counts as a
     * SOURCE. The baseline lists today's gaps and only shrinks.
     */
    @Test
    void everyFirstPartyToolCallbackIsClassifiedOrBaselined() throws Exception {
        java.nio.file.Path coreDir = java.nio.file.Path.of("..").toAbsolutePath().normalize();
        org.junit.jupiter.api.Assumptions.assumeTrue(
            java.nio.file.Files.isDirectory(coreDir.resolve("agents")), "module sources not found");
        java.util.regex.Pattern name = java.util.regex.Pattern.compile(
            "(?:\\.name\\(\"|(?:static final )?String (?:NAME|TOOL_NAME) = \")([a-z][a-z0-9_]*)\"");
        java.util.TreeSet<String> declared = new java.util.TreeSet<>();
        try (var files = java.nio.file.Files.walk(coreDir, 16)) {
            for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files
                .filter(x -> x.toString().endsWith(".java") && x.toString().contains("/src/main/java/")
                    && !x.toString().contains("/target/"))::iterator) {
                String src = java.nio.file.Files.readString(f);
                if (!java.util.regex.Pattern.compile("\\bimplements\\b[^{]*\\bToolCallback\\b").matcher(src).find()) {
                    continue;
                }
                var m = name.matcher(src);
                while (m.find()) {
                    declared.add(m.group(1));
                }
            }
        }
        assertTrue(declared.size() > 40, "the source scan found only " + declared.size() + " tool names");
        java.util.Set<String> baseline = new java.util.TreeSet<>();
        try (var in = BuiltInToolClassificationsTest.class.getResourceAsStream("/tool-classification-baseline.txt")) {
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    baseline.add(line.strip());
                }
            }
        }
        java.util.TreeSet<String> unclassified = new java.util.TreeSet<>();
        for (String n : declared) {
            if (BuiltInToolClassifications.find(n) == null) {
                unclassified.add(n);
            }
        }
        java.util.TreeSet<String> fresh = new java.util.TreeSet<>(unclassified);
        fresh.removeAll(baseline);
        assertTrue(fresh.isEmpty(),
            "tool(s) with no built-in classification: " + fresh + " - add them to BuiltInToolClassifications(Extended)");
        java.util.TreeSet<String> stale = new java.util.TreeSet<>(baseline);
        stale.removeAll(unclassified);
        assertTrue(stale.isEmpty(),
            "now classified (or gone), remove from tool-classification-baseline.txt: " + stale);
    }
}
