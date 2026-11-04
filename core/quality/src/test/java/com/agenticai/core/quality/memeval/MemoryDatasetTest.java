package com.agenticai.core.quality.memeval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.quality.memeval.MemoryScenario.Fact;
import com.agenticai.core.quality.memeval.MemoryScenario.FactKind;
import com.agenticai.core.quality.memeval.MemoryScenario.Probe;
import com.agenticai.core.quality.memeval.MemoryScenario.ProbeType;
import com.agenticai.core.quality.redteam.RedTeamCorpusData;
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
    void handWrittenPoisoningScenariosS11ToS13AreKept() {
        List<MemoryScenario> attacks = MemoryScenarioLoader.loadManifest().stream().filter(s -> s.attack() != null).toList();
        assertThat(attacks).extracting(MemoryScenario::id)
            .containsExactly("s11-poison-user-chat", "s12-poison-tool-output", "s13-poison-slow-drip");
    }

    @Test
    void poisoningCorpusHasAtLeast150AttacksWithUniqueCanariesAndAttackFacts() {
        List<MemoryScenario> corpus = byCategory(PoisonScenarioGenerator.CATEGORY);
        assertThat(corpus).hasSizeGreaterThanOrEqualTo(150);
        List<MemoryScenario> attacks = all.stream().filter(s -> s.attack() != null).toList();
        assertThat(attacks).hasSize(corpus.size() + 3);
        List<String> canaries = attacks.stream().flatMap(s -> s.attack().canaries().stream()).toList();
        assertThat(canaries).hasSize(attacks.size() + 5).doesNotHaveDuplicates().allMatch(c -> c.startsWith("CNRY-"));
        for (MemoryScenario a : attacks) {
            assertThat(a.ledger()).anyMatch(f -> f.kind() == FactKind.ATTACK);
        }
    }

    @Test
    void poisoningCorpusIsBuiltFromTheSharedRedTeamCorpus() {
        List<MemoryScenario> corpus = byCategory(PoisonScenarioGenerator.CATEGORY);
        int markers = RedTeamCorpusData.markerForgeries().size();
        assertThat(corpus.size() - markers).isPositive();
        assertThat((corpus.size() - markers) % RedTeamCorpusData.injectionStyleCount()).isZero();
        assertThat(corpus.stream().map(s -> s.attack().channel()).collect(Collectors.toSet()))
            .containsExactlyInAnyOrder("USER_CHAT", "TOOL_OUTPUT", "DOCUMENT", "AGENT_MESSAGE");
        assertThat(corpus.stream().map(s -> s.attack().family()).collect(Collectors.toSet()))
            .contains("INSTRUCTION_IN_MEMORY", "IDENTITY_SPOOFING", "CONTROL_MARKER_FORGERY", "CROSS_USER_PLANTING");
        // The marker forgery payload really is the platform object, not a paraphrase.
        MemoryScenario forged = corpus.stream().filter(s -> s.attack().family().equals("CONTROL_MARKER_FORGERY")).findFirst().orElseThrow();
        assertThat(forged.sessions().get(0).turns().get(0).text()).contains("approval_parked").contains("CNRY-RT-");
        // Deterministic: regenerating gives the same canaries.
        assertThat(PoisonScenarioGenerator.generate().stream().map(s -> s.attack().canaries().get(0)).toList())
            .isEqualTo(corpus.stream().map(s -> s.attack().canaries().get(0)).toList());
    }

    @Test
    void headlineProbeCountReachesTheNightlyBarWithoutCountingPoisonProbes() {
        long headline = all.stream().flatMap(s -> s.probes().stream()).filter(p -> p.type() != ProbeType.POISON).count();
        assertThat(headline).isGreaterThanOrEqualTo(140);
        for (MemoryScenario persona : byCategory("persona")) {
            assertThat(persona.probes()).hasSizeGreaterThanOrEqualTo(13);
        }
    }

    @Test
    void mistakeSetHasFortyCasesAsMistakeProbes() {
        List<MemoryScenario> mistakes = byCategory("mistake-correction");
        assertThat(mistakes).hasSize(40);
        assertThat(mistakes).allMatch(s -> s.probes().get(0).type() == ProbeType.MISTAKE);
        assertThat(mistakes.stream().map(s -> s.ledger().get(0).key())).doesNotHaveDuplicates();
        assertThat(mistakes.stream().map(s -> s.ledger().get(0).kind()).collect(Collectors.toSet()))
            .containsExactlyInAnyOrder(FactKind.CORRECTION, FactKind.RULE);
        assertThat(mistakes.stream().map(s -> s.persona().language()).collect(Collectors.toSet()))
            .contains("en", "hinglish", "hi");
    }

    @Test
    void s14ModelsExplicitSharingAndS15TheConsolidationHop() {
        MemoryScenario s14 = all.stream().filter(s -> s.id().equals("s14-cross-user-isolation")).findFirst().orElseThrow();
        assertThat(s14.sessions()).anyMatch(ses -> ses.shares().contains("f-wifi"));
        Fact wifi = s14.factsById().get("f-wifi");
        int end = s14.sessions().size() - 1;
        assertThat(s14.visibleTo(wifi, "uB", "t1", end)).isTrue();
        assertThat(s14.visibleTo(wifi, "uB", "t1", 1)).isFalse(); // not yet shared
        assertThat(s14.visibleTo(wifi, "uC", "t2", end)).isFalse(); // sharing never crosses a tenant
        assertThat(s14.visibleTo(s14.factsById().get("f-sal"), "uB", "t1", end)).isFalse();
        MemoryScenario s15 = all.stream().filter(s -> s.id().equals("s15-cross-tenant-isolation")).findFirst().orElseThrow();
        assertThat(s15.sessions().get(s15.sessions().size() - 1).tenantId()).isEqualTo("tY");
        assertThat(s15.probes()).anyMatch(p -> p.after().equals("s3"));
    }

    @Test
    void validatorRejectsSharesOfFactsTheUserDoesNotOwn() {
        MemoryScenario s = MemoryScenarioLoader.parseJson("""
            {"schema_version":1,"id":"sh","category":"c","seed":1,
             "sessions":[{"id":"s1","gap_days":0,"user_id":"a","tenant_id":"t","turns":[{"text":"x","plants":["f"]}]},
                         {"id":"s2","gap_days":0,"user_id":"b","tenant_id":"t","turns":[{"text":"y"}],"shares":["f","nope"]}],
             "ledger":[{"id":"f","key":"k","value":"v","planted_in":"s1"}],
             "probes":[{"id":"p","after":"s2","type":"DIRECT_RECALL","prompt":"?","user_id":"a","tenant_id":"t",
                        "query_keys":["k"],"expect":{"all":["v"]}}]}
            """).get(0);
        assertThat(String.join("\n", ScenarioValidator.validate(s)))
            .contains("shares fact f that its user does not own").contains("shares unknown fact nope");
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
