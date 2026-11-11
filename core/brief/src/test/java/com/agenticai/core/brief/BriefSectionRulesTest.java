package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.brief.BriefValidationResult.Issue;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.brief.model.Brief.ChannelSpec;
import com.agenticai.core.brief.model.Brief.EnvBinding;
import com.agenticai.core.brief.model.Brief.EvalCase;
import com.agenticai.core.brief.model.Brief.EvalSet;
import com.agenticai.core.brief.model.Brief.Gate;
import com.agenticai.core.brief.model.Brief.KnowledgeSource;
import com.agenticai.core.brief.model.Brief.KnowledgeSpec;
import com.agenticai.core.brief.model.Brief.McpServerSpec;
import com.agenticai.core.brief.model.Brief.Persona;
import com.agenticai.core.brief.model.Brief.Rehearsal;
import com.agenticai.core.brief.model.Brief.SecretSpec;
import com.agenticai.core.brief.model.Brief.ToolAuth;
import com.agenticai.core.brief.model.Brief.ToolSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The per-section rules that need no platform lookup, exercised one failure at a time. */
class BriefSectionRulesTest {

    private static final Map<String, Character> REFS =
            Map.of(
                    "a1", BriefRefs.AGENT,
                    "w1", BriefRefs.WORKFLOW,
                    "s1", BriefRefs.SECRET,
                    "k1", BriefRefs.KNOWLEDGE);

    private static Brief with(
            List<ToolSpec> tools,
            List<McpServerSpec> mcp,
            List<KnowledgeSpec> knowledge,
            List<SecretSpec> secrets,
            List<ChannelSpec> channels,
            EvalSet evalSet,
            Rehearsal rehearsal) {
        return new Brief(
                1, "t", "g", "en", null, null, null, null, null, tools, mcp, knowledge, secrets,
                channels, evalSet, rehearsal, null);
    }

    private static Brief tools(ToolSpec... t) {
        return with(List.of(t), null, null, List.of(secret("s1", "API_KEY")), null, null, null);
    }

    private static SecretSpec secret(String ref, String name) {
        return new SecretSpec(ref, name, "TENANT", "why", "Settings > API");
    }

    private static List<String> sections(List<Issue> errors) {
        return errors.stream().map(Issue::section).toList();
    }

    // --- credential destinations ----------------------------------------

    @Test
    void anAuthenticatedOpenApiToolNamesTheHostThatReceivesTheSecret() {
        Brief b =
                tools(
                        new ToolSpec(
                                "t1",
                                "OPENAPI",
                                "Billing",
                                "https://api.billing.io/spec.json",
                                new ToolAuth("BEARER", "s1"),
                                null));
        List<String> warnings = new ArrayList<>();

        BriefSectionRules.warnCredentialDestinations(b, warnings);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .contains("Billing")
                .contains("API_KEY")
                .contains("api.billing.io");
    }

    @Test
    void unauthenticatedCodeAndNonHttpToolsRaiseNoWarning() {
        Brief b =
                tools(
                        new ToolSpec("t1", "OPENAPI", "A", "https://x.io/s", null, null),
                        new ToolSpec(
                                "t2",
                                "OPENAPI",
                                "B",
                                "https://x.io/s",
                                new ToolAuth("NONE", null),
                                null),
                        new ToolSpec(
                                "t3", "CODE", "C", null, new ToolAuth("BEARER", "s1"), "draft"),
                        new ToolSpec(
                                "t4",
                                "OPENAPI",
                                "D",
                                "ftp://x.io/s",
                                new ToolAuth("BEARER", "s1"),
                                null));
        List<String> warnings = new ArrayList<>();

        BriefSectionRules.warnCredentialDestinations(b, warnings);

        assertThat(warnings).isEmpty();
    }

    @Test
    void anUndeclaredSecretRefIsShownAsIsAndAnUnnamedToolByItsRef() {
        Brief b =
                tools(
                        new ToolSpec(
                                "t1",
                                "OPENAPI",
                                " ",
                                "https://api.x.io/spec.json",
                                new ToolAuth("API_KEY_HEADER", "missing"),
                                null));
        List<String> warnings = new ArrayList<>();

        BriefSectionRules.warnCredentialDestinations(b, warnings);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("Tool t1").contains("missing").contains("api.x.io");
    }

    // --- tools -----------------------------------------------------------

    @Test
    void toolRulesCoverKindSpecUrlDescriptionAndAuth() {
        List<Issue> errors = new ArrayList<>();
        Brief b =
                tools(
                        new ToolSpec("t1", "SOAP", "x", null, null, null),
                        new ToolSpec("t2", "OPENAPI", "x", "not a url", null, null),
                        new ToolSpec("t3", "CODE", "x", null, null, " "),
                        new ToolSpec(
                                "t4",
                                "OPENAPI",
                                "x",
                                "https://a.io/s",
                                new ToolAuth("MAGIC", null),
                                null),
                        new ToolSpec(
                                "t5",
                                "OPENAPI",
                                "x",
                                "https://a.io/s",
                                new ToolAuth(null, null),
                                null),
                        new ToolSpec(
                                "t6",
                                "OPENAPI",
                                "x",
                                "https://a.io/s",
                                new ToolAuth("BEARER", "zz"),
                                null),
                        new ToolSpec(
                                "t7",
                                "OPENAPI",
                                "x",
                                "https://a.io/s",
                                new ToolAuth("BEARER", "s1"),
                                null));

        BriefSectionRules.validateTools(b, REFS, errors);

        assertThat(sections(errors))
                .containsExactly(
                        "tools[t1].kind",
                        "tools[t2].specUrl",
                        "tools[t3].codeDescription",
                        "tools[t4].auth.type",
                        "tools[t5].auth.type",
                        "tools[t6].auth.secretRef");
    }

    @Test
    void moreThanEightToolsIsRejected() {
        ToolSpec[] nine = new ToolSpec[9];
        for (int i = 0; i < 9; i++) {
            nine[i] = new ToolSpec("t" + i, "CODE", "n", null, null, "draft");
        }
        List<Issue> errors = new ArrayList<>();

        BriefSectionRules.validateTools(tools(nine), REFS, errors);

        assertThat(sections(errors)).containsExactly("tools");
    }

    // --- mcp -------------------------------------------------------------

    @Test
    void mcpServersNeedARegistryIdAndSecretBackedEnvVariables() {
        Map<String, EnvBinding> env = new LinkedHashMap<>();
        env.put(" ", new EnvBinding("s1"));
        env.put("NULL_BINDING", null);
        env.put("BAD_REF", new EnvBinding("s9"));
        env.put("GOOD", new EnvBinding("s1"));
        Brief b =
                with(
                        null,
                        List.of(new McpServerSpec("m1", " ", "srv", env)),
                        null,
                        null,
                        null,
                        null,
                        null);
        List<Issue> errors = new ArrayList<>();

        BriefSectionRules.validateMcp(b, REFS, errors);

        assertThat(sections(errors))
                .containsExactly(
                        "mcpServers[m1].registryId",
                        "mcpServers[m1].env",
                        "mcpServers[m1].env.NULL_BINDING",
                        "mcpServers[m1].env.BAD_REF.secretRef");
    }

    @Test
    void moreThanSixMcpServersIsRejected() {
        List<McpServerSpec> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seven.add(new McpServerSpec("m" + i, "reg", "n", Map.of()));
        }
        List<Issue> errors = new ArrayList<>();

        BriefSectionRules.validateMcp(
                with(null, seven, null, null, null, null, null), REFS, errors);

        assertThat(sections(errors)).containsExactly("mcpServers");
    }

    // --- knowledge -------------------------------------------------------

    @Test
    void knowledgeSourcesAreCheckedPerKind() {
        KnowledgeSpec k =
                new KnowledgeSpec(
                        "k1",
                        " ",
                        java.util.Arrays.asList(
                                null,
                                new KnowledgeSource("FTP", null, null, null),
                                new KnowledgeSource("URL", "nope", null, null),
                                new KnowledgeSource("CONNECTOR", null, " ", null),
                                new KnowledgeSource("URL", "https://ok.io/doc", null, null),
                                new KnowledgeSource("UPLOAD_LATER", null, null, "hint")));
        List<Issue> errors = new ArrayList<>();

        BriefSectionRules.validateKnowledge(
                with(null, null, List.of(k), null, null, null, null), errors);

        assertThat(sections(errors))
                .containsExactly(
                        "knowledge[k1].name",
                        "knowledge[k1].sources[0].kind",
                        "knowledge[k1].sources[1].kind",
                        "knowledge[k1].sources[2].url",
                        "knowledge[k1].sources[3].connectorType");
    }

    @Test
    void moreThanFourKnowledgeCollectionsIsRejected() {
        List<KnowledgeSpec> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(new KnowledgeSpec("k" + i, "n", List.of()));
        }
        List<Issue> errors = new ArrayList<>();

        BriefSectionRules.validateKnowledge(with(null, null, five, null, null, null, null), errors);

        assertThat(sections(errors)).containsExactly("knowledge");
    }

    // --- secrets ---------------------------------------------------------

    @Test
    void secretRulesCoverNameScopeReasonDuplicatesAndPastedValues() {
        List<SecretSpec> secrets =
                List.of(
                        new SecretSpec("s1", "lowercase", "TENANT", "why", null),
                        new SecretSpec("s2", null, "TENANT", "why", null),
                        new SecretSpec("s3", "DUP_KEY", "TENANT", "why", null),
                        new SecretSpec("s4", "DUP_KEY", "TENANT", "why", null),
                        new SecretSpec("s5", "OTHER_KEY", "GLOBAL", " ", null),
                        new SecretSpec("s6", "THIRD_KEY", null, "why", "sk-abcdefghijklmnop1234"),
                        new SecretSpec("s7", "FOURTH_KEY", "USER", "why", "a".repeat(40)));
        List<Issue> errors = new ArrayList<>();

        BriefSectionRules.validateSecrets(
                with(null, null, null, secrets, null, null, null), errors);

        assertThat(sections(errors))
                .containsExactly(
                        "secrets[s1].name",
                        "secrets[s2].name",
                        "secrets[s4].name",
                        "secrets[s5].scope",
                        "secrets[s5].why",
                        "secrets[s6].scope",
                        "secrets[s6].obtainAt",
                        "secrets[s7].obtainAt");
    }

    @Test
    void sameSecretNameInADifferentScopeIsNotADuplicate() {
        List<SecretSpec> secrets =
                List.of(
                        new SecretSpec("s1", "API_KEY", "TENANT", "why", null),
                        new SecretSpec("s2", "API_KEY", "USER", "why", null));
        List<Issue> errors = new ArrayList<>();

        BriefSectionRules.validateSecrets(
                with(null, null, null, secrets, null, null, null), errors);

        assertThat(errors).isEmpty();
    }

    @Test
    void moreThanTenSecretsIsRejected() {
        List<SecretSpec> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(secret("s" + i, "KEY_" + i));
        }
        List<Issue> errors = new ArrayList<>();

        BriefSectionRules.validateSecrets(with(null, null, null, eleven, null, null, null), errors);

        assertThat(sections(errors)).containsExactly("secrets");
    }

    // --- channels --------------------------------------------------------

    @Test
    void channelsMustHaveAKnownKindAndBindToADeclaredAgentOrWorkflow() {
        List<ChannelSpec> channels =
                List.of(
                        new ChannelSpec("c1", "CARRIER_PIGEON", "a1", null),
                        new ChannelSpec("c2", "EMAIL", " ", null),
                        new ChannelSpec("c3", "EMAIL", "k1", null),
                        new ChannelSpec("c4", "SLACK", "a2", true));
        List<Issue> errors = new ArrayList<>();

        BriefSectionRules.validateChannels(
                with(null, null, null, null, channels, null, null), REFS, errors);

        assertThat(sections(errors))
                .containsExactly(
                        "channels[c1].kind",
                        "channels[c2].bindTo",
                        "channels[c3].bindTo",
                        "channels[c4].bindTo");
    }

    @Test
    void moreThanFourChannelsIsRejected() {
        List<ChannelSpec> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(new ChannelSpec("c" + i, "WEB", "a1", null));
        }
        List<Issue> errors = new ArrayList<>();

        BriefSectionRules.validateChannels(
                with(null, null, null, null, five, null, null), REFS, errors);

        assertThat(sections(errors)).containsExactly("channels");
    }

    // --- eval set --------------------------------------------------------

    private static List<EvalCase> cases(int n) {
        List<EvalCase> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new EvalCase("in " + i, null, "rubric"));
        }
        return out;
    }

    @Test
    void aMissingEvalSetIsAWarningNotAnError() {
        List<Issue> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        BriefSectionRules.validateEvalSet(
                with(null, null, null, null, null, null, null), REFS, errors, warnings);

        assertThat(errors).isEmpty();
        assertThat(warnings).hasSize(1).first().asString().contains("No eval set");
    }

    @Test
    void evalSetRulesCoverCaseCountCaseContentTargetAndGate() {
        List<EvalCase> bad = new ArrayList<>(cases(3));
        bad.add(new EvalCase(" ", null, "r"));
        bad.add(null);
        Brief b = with(null, null, null, null, null, new EvalSet("a9", bad, new Gate(1.5)), null);
        List<Issue> errors = new ArrayList<>();

        BriefSectionRules.validateEvalSet(b, REFS, errors, new ArrayList<>());

        assertThat(sections(errors))
                .containsExactly(
                        "evalSet.cases[3]",
                        "evalSet.cases[4]",
                        "evalSet.targetAgent",
                        "evalSet.gate.minScore");
    }

    @Test
    void anEvalSetOutsideFourToSixteenCasesIsRejected() {
        List<Issue> few = new ArrayList<>();
        BriefSectionRules.validateEvalSet(
                with(null, null, null, null, null, new EvalSet(null, cases(3), null), null),
                REFS,
                few,
                new ArrayList<>());
        List<Issue> many = new ArrayList<>();
        BriefSectionRules.validateEvalSet(
                with(
                        null,
                        null,
                        null,
                        null,
                        null,
                        new EvalSet(null, cases(17), new Gate(-0.1)),
                        null),
                REFS,
                many,
                new ArrayList<>());

        assertThat(sections(few)).containsExactly("evalSet.cases");
        assertThat(sections(many)).containsExactly("evalSet.cases", "evalSet.gate.minScore");
    }

    @Test
    void aWellFormedEvalSetPasses() {
        List<Issue> errors = new ArrayList<>();
        BriefSectionRules.validateEvalSet(
                with(
                        null,
                        null,
                        null,
                        null,
                        null,
                        new EvalSet("a1", cases(4), new Gate(0.7)),
                        null),
                REFS,
                errors,
                new ArrayList<>());
        assertThat(errors).isEmpty();
    }

    // --- rehearsal -------------------------------------------------------

    @Test
    void rehearsalPersonasNeedANameAndATraitAndAreCappedAtFour() {
        List<Persona> personas =
                java.util.Arrays.asList(
                        new Persona(" ", List.of("angry"), null),
                        new Persona("Ann", List.of(), null),
                        null,
                        new Persona("Bob", List.of("terse"), "hi"),
                        new Persona("Cy", List.of("calm"), null));
        List<Issue> errors = new ArrayList<>();

        BriefSectionRules.validateRehearsal(
                with(null, null, null, null, null, null, new Rehearsal(personas)), errors);

        assertThat(sections(errors))
                .containsExactly(
                        "rehearsal.personas",
                        "rehearsal.personas[0]",
                        "rehearsal.personas[1]",
                        "rehearsal.personas[2]");
    }

    @Test
    void noRehearsalBlockIsFine() {
        List<Issue> errors = new ArrayList<>();
        BriefSectionRules.validateRehearsal(with(null, null, null, null, null, null, null), errors);
        assertThat(errors).isEmpty();
    }
}
