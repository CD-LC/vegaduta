package com.agenticai.core.providers.addons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.agenticai.core.providers.addons.AddonDefinition.Availability;
import com.agenticai.core.providers.addons.AddonDefinition.Kind;
import com.agenticai.core.providers.localmodels.ModelCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The shipped catalog is sound, matches what the repo really contains, and the validator rejects bad ones. */
class AddonCatalogTest {

    private final AddonCatalog shipped = new AddonCatalog();

    // ----------------------------------------------------------- the shipped file

    @Test
    void theShippedCatalogLoadsAndHasUniqueIds() {
        List<String> ids = shipped.all().stream().map(AddonDefinition::id).toList();
        assertFalse(ids.isEmpty());
        assertEquals(ids.size(), new HashSet<>(ids).size());
        assertFalse(ids.contains("host"), "'host' is reserved for /api/addons/host");
    }

    @Test
    void atMostThreePlannedAndNoneCanBeSwitchedOn() {
        List<AddonDefinition> planned =
                shipped.all().stream().filter(d -> d.availability() == Availability.PLANNED).toList();
        assertTrue(planned.size() <= 3, "planned: " + planned.size());
        for (AddonDefinition d : planned) {
            assertEquals(Kind.PLANNED, d.kind(), d.id());
            assertFalse(d.defaultOn(), d.id());
            assertEquals(null, d.compose(), d.id());
            assertEquals(null, d.model(), d.id());
            assertTrue(d.description().startsWith("Planned."), d.id() + " must say it is planned");
        }
    }

    @Test
    void everyAvailableAddonCanReallyBeSwitchedOn() {
        for (AddonDefinition d : shipped.all()) {
            if (d.availability() != Availability.AVAILABLE) {
                continue;
            }
            assertTrue(d.kind() == Kind.COMPOSE_PROFILE || d.kind() == Kind.MODEL_PULL, d.id() + " has no executor: " + d.kind());
            assertFalse(d.hostCommand().isBlank(), d.id());
        }
    }

    @Test
    void modelPacksAgreeWithTheLocalModelCatalog() {
        ModelCatalog models = new ModelCatalog();
        int checked = 0;
        for (AddonDefinition d : shipped.all()) {
            if (d.kind() != Kind.MODEL_PULL) {
                continue;
            }
            ModelCatalog.Entry e = models.find(d.model())
                    .orElseThrow(() -> new AssertionError(d.id() + ": " + d.model() + " is not in local-models/catalog.txt"));
            assertEquals(Math.round(e.downloadGb() * 1024), d.sizeMb(), d.id() + " sizeMb");
            assertEquals(Math.round(e.ramGb() * 1024), d.minRamMb(), d.id() + " minRamMb");
            assertEquals(e.licence(), d.license(), d.id() + " license");
            checked++;
        }
        assertTrue(checked >= 3, "expected several model packs, found " + checked);
    }

    @Test
    void composeAddonsUseOnlyWhatTheLauncherAccepts() throws IOException {
        Path script = repoRoot().resolve("scripts/vegaduta");
        assumeTrue(Files.isRegularFile(script), "scripts/vegaduta not found from " + Path.of("").toAbsolutePath());
        String text = Files.readString(script, StandardCharsets.UTF_8);
        for (AddonDefinition d : shipped.all()) {
            if (d.compose() == null) {
                continue;
            }
            for (String profile : d.compose().profiles()) {
                assertTrue(text.contains("rag workspace") && text.contains(profile), d.id() + ": launcher does not know profile " + profile);
            }
            for (String flag : d.compose().flags()) {
                assertTrue(text.contains(flag + ")"), d.id() + ": launcher does not parse " + flag);
            }
        }
    }

    @Test
    void learnMorePointsAtRealDocs() {
        Path root = repoRoot();
        assumeTrue(Files.isDirectory(root.resolve("docs")), "docs/ not found from " + Path.of("").toAbsolutePath());
        for (AddonDefinition d : shipped.all()) {
            String file = d.learnMore().split("#", 2)[0];
            assertTrue(Files.isRegularFile(root.resolve(file)), d.id() + ": " + file + " does not exist");
        }
    }

    @Test
    void unlockKeysAreUsedByAtLeastOneAddon() {
        Set<String> used = shipped.all().stream().flatMap(d -> d.unlocks().stream()).collect(Collectors.toSet());
        assertTrue(used.contains("web_search") && used.contains("documents") && used.contains("local_models"));
    }

    // ------------------------------------------------------------ requirement walks

    private static String entry(String id, String requires) {
        return """
            {"id":"%s","name":"N %s","description":"D","category":"models","kind":"compose-profile","availability":"available",
             "sizeMb":1,"minRamMb":1,"needsGpu":false,"license":"MIT","free":true,"requires":[%s],"defaultOn":false,
             "hostCommand":"./scripts/vegaduta personal --search","learnMore":"docs/x.md","unlocks":[],"model":null,
             "compose":{"profiles":[],"flags":["--search"],"env":{}},"observe":null}
            """.formatted(id, id, requires);
    }

    private static String catalog(String... entries) {
        return "{\"schemaVersion\":1,\"addons\":[" + String.join(",", entries) + "]}";
    }

    private static String quoted(String... ids) {
        return java.util.Arrays.stream(ids).map(i -> "\"" + i + "\"").collect(Collectors.joining(","));
    }

    @Test
    void requirementsComeFirstAndDependentsLeafFirst() {
        AddonCatalog c = AddonCatalog.parse(catalog(
                entry("app", quoted("lib", "base")), entry("lib", quoted("base")), entry("base", ""), entry("other", "")));
        assertEquals(List.of("base", "lib"), c.requirementsOf("app").stream().map(AddonDefinition::id).toList());
        assertEquals(List.of(), c.requirementsOf("base"));
        assertEquals(List.of("app", "lib"), c.dependentsOf("base").stream().map(AddonDefinition::id).toList());
        assertEquals(List.of("app"), c.dependentsOf("lib").stream().map(AddonDefinition::id).toList());
        assertEquals(List.of(), c.dependentsOf("other"));
    }

    // ----------------------------------------------------------- validator rules

    private static void rejects(String json, String fragment) {
        AddonCatalogException e = assertThrows(AddonCatalogException.class, () -> AddonCatalog.parse(json));
        assertTrue(e.getMessage().contains(fragment), "expected '" + fragment + "' in: " + e.getMessage());
    }

    @Test
    void rejectsDuplicateIds() {
        rejects(catalog(entry("aa", ""), entry("aa", "")), "duplicate id 'aa'");
    }

    @Test
    void rejectsUnknownRequires() {
        rejects(catalog(entry("aa", quoted("ghost"))), "requires unknown add-on 'ghost'");
    }

    @Test
    void rejectsSelfRequirement() {
        rejects(catalog(entry("aa", quoted("aa"))), "requires itself");
    }

    @Test
    void rejectsCyclesAndNamesThePath() {
        rejects(catalog(entry("aa", quoted("bb")), entry("bb", quoted("cc")), entry("cc", quoted("aa"))), "dependency cycle: aa -> bb -> cc -> aa");
    }

    @Test
    void rejectsReservedAndMalformedIds() {
        rejects(catalog(entry("host", "")), "reserved");
        rejects(catalog(entry("Bad_Id", "")), "must match");
    }

    @Test
    void rejectsAnAvailableAddonThatNeedsAPlannedOne() {
        String planned = """
            {"id":"later","name":"L","description":"Planned.","category":"media","kind":"planned","availability":"planned",
             "sizeMb":0,"minRamMb":0,"needsGpu":false,"license":"","free":true,"requires":[],"defaultOn":false,
             "hostCommand":"","learnMore":"docs/x.md","unlocks":[],"model":null,"compose":null,"observe":null}
            """;
        rejects(catalog(planned, entry("aa", quoted("later"))), "requires planned add-on 'later'");
    }

    @Test
    void rejectsMoreThanThreePlanned() {
        String tpl = """
            {"id":"p%d","name":"L","description":"Planned.","category":"media","kind":"planned","availability":"planned",
             "sizeMb":0,"minRamMb":0,"needsGpu":false,"license":"","free":true,"requires":[],"defaultOn":false,
             "hostCommand":"","learnMore":"docs/x.md","unlocks":[],"model":null,"compose":null,"observe":null}
            """;
        rejects(catalog(tpl.formatted(1), tpl.formatted(2), tpl.formatted(3), tpl.formatted(4)), "at most 3 add-ons may be planned");
    }

    @Test
    void rejectsKindAndAvailabilityThatDisagree() {
        rejects(entryWith("\"kind\":\"compose-profile\"", "\"kind\":\"planned\""), "go together");
    }

    @Test
    void rejectsAnMcpServerThatClaimsToBeAvailable() {
        rejects(entryWith("\"kind\":\"compose-profile\"", "\"kind\":\"mcp-server\""), "has no executor yet");
    }

    @Test
    void rejectsComposeThingsTheLauncherDoesNotKnow() {
        rejects(entryWith("\"profiles\":[]", "\"profiles\":[\"voice\"]"), "compose profile 'voice'");
        rejects(entryWith("\"flags\":[\"--search\"]", "\"flags\":[\"--search\",\"--rm-rf\"]"), "compose flag '--rm-rf'");
        rejects(entryWith("\"env\":{}", "\"env\":{\"lower\":\"x\"}"), "env key 'lower'");
        rejects(entryWith("\"env\":{}", "\"env\":{\"A_B\":\"x y\"}"), "env value of A_B");
        rejects(entryWith("\"env\":{}", "\"env\":{\"A_B\":\"$(id)\"}"), "env value of A_B");
    }

    @Test
    void rejectsAHostCommandThatDoesNotDoWhatTheEntryDeclares() {
        rejects(entryWith("\"hostCommand\":\"./scripts/vegaduta personal --search\"", "\"hostCommand\":\"./scripts/vegaduta personal\""), "hostCommand must contain '--search'");
    }

    @Test
    void rejectsTwoAddonsSettingOneEnvKeyDifferently() {
        rejects(catalog(entryEnv("aa", "K", "1"), entryEnv("bb", "K", "2")), "env key K is set to different values");
        AddonCatalog ok = AddonCatalog.parse(catalog(entryEnv("aa", "K", "1"), entryEnv("bb", "K", "1")));
        assertEquals(2, ok.all().size());
    }

    private static String entryEnv(String id, String key, String value) {
        return entry(id, "")
                .replace("\"env\":{}", "\"env\":{\"" + key + "\":\"" + value + "\"}")
                .replace("./scripts/vegaduta personal --search", "set " + key + "=" + value + " then ./scripts/vegaduta personal --search");
    }

    @Test
    void rejectsUnknownJsonPropertiesAndDuplicateKeys() {
        rejects(entryWith("\"free\":true", "\"free\":true,\"surprise\":1"), "cannot be parsed");
        rejects("{\"schemaVersion\":1,\"schemaVersion\":1,\"addons\":[]}", "cannot be parsed");
        rejects("{\"schemaVersion\":2,\"addons\":[]}", "schemaVersion must be 1");
        rejects("{\"schemaVersion\":1,\"addons\":[]}", "non-empty");
        rejects("not json", "cannot be parsed");
    }

    @Test
    void rejectsUnknownUnlockKeysAndBadLearnMore() {
        rejects(entryWith("\"unlocks\":[]", "\"unlocks\":[\"teleport\"]"), "unknown unlocks key 'teleport'");
        rejects(entryWith("\"learnMore\":\"docs/x.md\"", "\"learnMore\":\"https://evil.example/x\""), "learnMore must be");
        rejects(entryWith("\"learnMore\":\"docs/x.md\"", "\"learnMore\":\"docs/../../etc/passwd\""), "learnMore must be");
    }

    @Test
    void rejectsAModelPullWithoutAValidTag() {
        String model = """
            {"id":"mm","name":"M","description":"D","category":"models","kind":"model-pull","availability":"available",
             "sizeMb":1,"minRamMb":1,"needsGpu":false,"license":"MIT","free":true,"requires":[],"defaultOn":false,
             "hostCommand":"ollama pull x","learnMore":"docs/x.md","unlocks":[],"model":"bad tag!","compose":null,"observe":null}
            """;
        rejects(catalog(model), "valid Ollama tag");
    }

    @Test
    void listsEveryProblemNotJustTheFirst() {
        AddonCatalogException e = assertThrows(
                AddonCatalogException.class,
                () -> AddonCatalog.parse(catalog(entry("aa", quoted("ghost")), entry("aa", quoted("ghost2")))));
        assertTrue(e.getMessage().contains("duplicate id") && e.getMessage().contains("ghost2"), e.getMessage());
    }

    /** The one-entry base catalog with one fragment replaced. */
    private static String entryWith(String from, String to) {
        String base = entry("aa", "");
        assertTrue(base.contains(from), "fixture lost: " + from);
        return catalog(base.replace(from, to));
    }

    // ------------------------------------------------------------------ helpers

    /** The repository root, found by walking up from the module directory. */
    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("scripts")) ) {
            p = p.getParent();
        }
        return p == null ? Path.of("").toAbsolutePath() : p;
    }
}
