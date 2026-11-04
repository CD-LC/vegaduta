package com.agenticai.core.quality.memeval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.quality.memeval.MemoryScenario.Fact;
import com.agenticai.core.quality.memeval.MemoryScenario.FactKind;
import com.agenticai.core.quality.memeval.MemoryScenario.Probe;
import com.agenticai.core.quality.memeval.MemoryScenario.ProbeType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Schema, loader and ledger-consistency checks of the shipped memory-eval dataset (zero network). */
class MemoryDatasetTest {

    private static List<MemoryScenario> all;

    @BeforeAll
    static void load() {
        all = MemoryScenarioLoader.loadAll();
    }

    private static List<MemoryScenario> byCategory(String category) {
        return all.stream().filter(s -> category.equals(s.category())).toList();
    }

    @Test
    void datasetIsLedgerConsistent() {
        assertThat(ScenarioValidator.validateDataset(all)).isEmpty();
    }

    @Test
    void shipsEightPersonasWithSixToTwelveSessionsAndThirtyToSixtyFacts() {
        List<MemoryScenario> personas = byCategory("persona");
        assertThat(personas).hasSize(8);
        for (MemoryScenario p : personas) {
            assertThat(p.sessions().size()).isBetween(6, 12);
            assertThat(p.ledger().size()).isBetween(30, 60);
            Set<ProbeType> types = p.probes().stream().map(Probe::type).collect(Collectors.toSet());
            assertThat(types).contains(ProbeType.DIRECT_RECALL, ProbeType.PREFERENCE, ProbeType.UPDATE,
                ProbeType.MULTI_HOP, ProbeType.ABSTENTION);
        }
    }

    @Test
    void personasCoverEnglishHindiAndHinglish() {
        Set<String> langs = byCategory("persona").stream().map(s -> s.persona().language()).collect(Collectors.toSet());
        assertThat(langs).contains("en", "hi", "hinglish");
        MemoryScenario hindi = byCategory("persona").stream().filter(s -> "hi".equals(s.persona().language())).findFirst().orElseThrow();
        assertThat(hindi.sessions().get(0).turns().get(0).text()).matches(".*[\\u0900-\\u097F].*");
    }

    @Test
    void shipsScenariosS1ToS15() {
        List<String> ids = all.stream().map(MemoryScenario::id).toList();
        List<String> expected = List.of(
            "s01-basic-recall", "s02-preference-unprompted", "s03-knowledge-update", "s04-contradiction-at-write",
            "s05-multi-hop", "s06-abstention", "s07-repeated-mistake-tool-arg", "s08-repeated-mistake-approval-denial",
            "s09-distractor-precision", "s10-forget-request", "s11-poison-user-chat", "s12-poison-tool-output",
            "s13-poison-slow-drip", "s14-cross-user-isolation", "s15-cross-tenant-isolation");
        assertThat(ids).containsAll(expected);
    }

    @Test
    void s9HasFortyBenignFactsAndThreeRelevantOnes() {
        MemoryScenario s9 = all.stream().filter(s -> s.id().equals("s09-distractor-precision")).findFirst().orElseThrow();
        assertThat(s9.sessions()).hasSize(8);
        long relevant = s9.ledger().stream().filter(f -> !f.relevantTo().isEmpty()).count();
        assertThat(relevant).isEqualTo(3);
        assertThat(s9.ledger().size() - relevant).isEqualTo(40);
    }

    @Test
    void poisoningCasesCarryUniqueCanariesAndAttackFacts() {
        List<MemoryScenario> attacks = all.stream().filter(s -> s.attack() != null).toList();
        assertThat(attacks).hasSize(3);
        List<String> canaries = attacks.stream().flatMap(s -> s.attack().canaries().stream()).toList();
        assertThat(canaries).doesNotHaveDuplicates().allMatch(c -> c.startsWith("CNRY-"));
        for (MemoryScenario a : attacks) {
            assertThat(a.ledger()).anyMatch(f -> f.kind() == FactKind.ATTACK);
        }
    }

    @Test
    void mistakeSetCoversRepeatedMistakesAsMistakeProbes() {
        List<MemoryScenario> mistakes = byCategory("mistake-correction");
        assertThat(mistakes).hasSizeGreaterThanOrEqualTo(12);
        assertThat(mistakes).allMatch(s -> s.probes().get(0).type() == ProbeType.MISTAKE);
    }

    @Test
    void noRealLookingPiiInTheDataset() {
        String blob = all.stream().flatMap(s -> s.ledger().stream()).map(Fact::value).collect(Collectors.joining(" "));
        java.util.regex.Matcher pan = java.util.regex.Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b").matcher(blob);
        while (pan.find()) {
            assertThat(pan.group()).isEqualTo("ZZZZZ9999Z"); // the one deliberately fake PAN
        }
        assertThat(blob).doesNotContainPattern("\\b\\d{12}\\b").doesNotContain("@gmail.com").doesNotContain("@yahoo.");
    }

    @Test
    void rejectsWrongSchemaVersion() {
        assertThatThrownBy(() -> MemoryScenarioLoader.parseJson("{\"schema_version\":2,\"id\":\"x\"}"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema_version");
    }

    @Test
    void rejectsUnknownProperties() {
        assertThatThrownBy(() -> MemoryScenarioLoader.parseJson("{\"schema_version\":1,\"id\":\"x\",\"bogus\":1}"))
            .hasStackTraceContaining("bogus");
    }

    @Test
    void validatorCatchesBrokenLedgers() {
        MemoryScenario s = MemoryScenarioLoader.parseJson("""
            {"schema_version":1,"id":"bad","category":"c","seed":1,
             "sessions":[{"id":"s1","gap_days":0,"user_id":"u","tenant_id":"t",
                          "turns":[{"text":"hi","plants":["a","ghost"]}]}],
             "ledger":[{"id":"a","key":"k","value":"v","planted_in":"s1","superseded_by":"b"},
                       {"id":"b","key":"k2","value":"w","planted_in":"s1","superseded_by":"a"}],
             "probes":[{"id":"p","after":"s9","type":"DIRECT_RECALL","prompt":"?","user_id":"u","tenant_id":"t",
                        "query_keys":["nokey"],"expect":{}}]}
            """).get(0);
        String errors = String.join("\n", ScenarioValidator.validate(s));
        assertThat(errors).contains("unknown fact ghost").contains("cyclic").contains("different key")
            .contains("unknown session s9").contains("absent from the ledger").contains("asserts nothing")
            .contains("planted 0 times");
    }

    @Test
    void supersedeAndForgetTimelineHelpers() {
        MemoryScenario s3 = all.stream().filter(s -> s.id().equals("s03-knowledge-update")).findFirst().orElseThrow();
        Fact pune = s3.factsById().get("f-pune");
        assertThat(s3.supersededBy(pune, 0)).isFalse();
        assertThat(s3.supersededBy(pune, 1)).isTrue();
        assertThat(s3.dayOf(2)).isEqualTo(97);
        assertThat(new HashSet<>(s3.liveFacts(2)).stream().map(Fact::id)).containsExactly("f-blr");
    }
}
