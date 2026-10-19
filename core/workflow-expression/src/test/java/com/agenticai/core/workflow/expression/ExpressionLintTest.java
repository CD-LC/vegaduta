package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExpressionLintTest {

    private final ExpressionEngine engine = new ExpressionEngine();

    private static LintContext lc() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("Fetch", "n1");
        labels.put("Lookup", "n2");
        labels.put("Dup", "n3");
        return new LintContext(labels, Set.of("Dup"), Set.of("name", "age"));
    }

    private List<Diagnostic> lint(String t) {
        return engine.lint(t, lc());
    }

    private static Diagnostic only(List<Diagnostic> d, String code) {
        List<Diagnostic> m = d.stream().filter(x -> x.code().equals(code)).toList();
        assertEquals(1, m.size(), code + " in " + d);
        return m.get(0);
    }

    @Test
    void cleanTemplatesHaveNoDiagnostics() {
        assertTrue(lint("plain text").isEmpty());
        assertTrue(lint("{{input}} {{name}} {{ $node[\"Fetch\"].x }}").isEmpty());
        assertTrue(lint("{{ $json.items.map(i => i.p).sum() }}").isEmpty());
        assertTrue(lint("{{ $node.id(\"n2\").a }}").isEmpty());
        assertTrue(lint("{{}} {{ }} {{ never closed").isEmpty());
        assertTrue(engine.lint(null, lc()).isEmpty());
        assertTrue(engine.lint("{{ whatever }}", null).isEmpty());
    }

    @Test
    void parseErrorWithTemplatePositions() {
        String t = "hello {{ 1 + * 2 }}";
        Diagnostic d = only(lint(t), "PARSE_ERROR");
        assertEquals(Diagnostic.Severity.ERROR, d.severity());
        assertEquals(t.indexOf('*'), d.start());
        assertTrue(d.end() > d.start());
    }

    @Test
    void unknownFunctionWithDidYouMean() {
        String t = "{{ uper('a') }}";
        Diagnostic d = only(lint(t), "UNKNOWN_FUNCTION");
        assertEquals(Diagnostic.Severity.ERROR, d.severity());
        assertTrue(d.message().contains("upper"), d.message());
        assertEquals(t.indexOf("uper"), d.start());
        only(lint("{{ 'a'.nosuch() }}"), "UNKNOWN_FUNCTION");
    }

    @Test
    void wrongArgumentCount() {
        only(lint("{{ lower() }}"), "WRONG_ARGUMENT_COUNT");
        only(lint("{{ lower('a', 'b') }}"), "WRONG_ARGUMENT_COUNT");
        assertTrue(lint("{{ 'a'.lower() }}").isEmpty(), "method sugar supplies the first argument");
    }

    @Test
    void unknownNodeLabel() {
        String t = "{{ $node[\"Fetsh\"].x }}";
        Diagnostic d = only(lint(t), "UNKNOWN_NODE");
        assertEquals(Diagnostic.Severity.ERROR, d.severity());
        assertTrue(d.message().contains("Fetch"), "did you mean: " + d.message());
        only(lint("{{ $node.Nope.x }}"), "UNKNOWN_NODE");
        only(lint("{{ $node.id(\"zz\").x }}"), "UNKNOWN_NODE");
    }

    @Test
    void ambiguousLabelIsAWarning() {
        Diagnostic d = only(lint("{{ $node[\"Dup\"].x }}"), "AMBIGUOUS_NODE");
        assertEquals(Diagnostic.Severity.WARNING, d.severity());
    }

    @Test
    void legacyUnknownBareNameIsAWarning() {
        String t = "Hi {{nope}}";
        Diagnostic d = only(lint(t), "LEGACY_UNKNOWN_NAME");
        assertEquals(Diagnostic.Severity.WARNING, d.severity());
        assertEquals(t.indexOf("nope"), d.start());
        assertEquals(t.indexOf("nope") + 4, d.end());
        assertTrue(lint("{{input}}{{name}}{{age}}").isEmpty());
        // without knowledge of the input fields the check is off
        assertTrue(engine.lint("{{nope}}", LintContext.empty()).isEmpty());
    }

    @Test
    void unknownRoot() {
        only(lint("{{ $jsn.a }}"), "UNKNOWN_ROOT");
    }

    @Test
    void lambdaParametersAreNotLegacyNames() {
        assertTrue(lint("{{ $json.rows.map(zzz => zzz.p) }}").isEmpty());
    }

    @Test
    void multipleFindingsAreAllReported() {
        List<Diagnostic> d = lint("{{ uper('a') }} {{ $node[\"X\"].y }} {{ 1 + }} {{ nope }}");
        assertEquals(
                Set.of("UNKNOWN_FUNCTION", "UNKNOWN_NODE", "PARSE_ERROR", "LEGACY_UNKNOWN_NAME"),
                d.stream().map(Diagnostic::code).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void referencedNodeLabelsAndIds() {
        String t =
                "{{ $node[\"Fetch\"].a }} {{ $node.Lookup.b + $node['Fetch'].c }} {{ $node.id(\"n7\").x }} {{ map([1], n => n) }}";
        assertEquals(Set.of("Fetch", "Lookup"), engine.referencedNodeLabels(t));
        assertEquals(Set.of("n7"), engine.referencedNodeIds(t));
        assertTrue(
                engine.referencedNodeLabels("{{ 1 + }} {{ $node[\"Ok\"] }}").contains("Ok"),
                "unparsable span is skipped");
        assertTrue(engine.referencedNodeLabels(null).isEmpty());
    }
}
