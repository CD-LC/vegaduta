package com.agenticai.core.workflow.portability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The n8n-to-VegaDuta expression rewrites: only the data accessors with a direct spelling are
 * translated ({@code $('N').item.json} -> {@code $node["N"]}, {@code $input.item.json} -> {@code
 * $json}); literal text is untouched.
 */
class PortableExpressionsTest {

    @Test
    void quoteProducesASingleQuotedLiteral() {
        assertThat(PortableExpressions.quote("hello")).isEqualTo("'hello'");
        assertThat(PortableExpressions.quote(null)).isEqualTo("''");
        assertThat(PortableExpressions.quote("it's")).isEqualTo("'it\\'s'");
        assertThat(PortableExpressions.quote("back\\slash")).isEqualTo("'back\\\\slash'");
        assertThat(PortableExpressions.quote("line\nfeed")).isEqualTo("'line\\nfeed'");
        assertThat(PortableExpressions.quote("a\rb\tc")).isEqualTo("'a\\rb\\tc'");
        // A control character below 0x20 becomes a \uXXXX escape.
        assertThat(PortableExpressions.quote("x" + (char) 1)).isEqualTo("'x\\u0001'");
    }

    @Test
    void nodeRefQuotesTheLabel() {
        assertThat(PortableExpressions.nodeRef("Start")).isEqualTo("$node[\"Start\"]");
        assertThat(PortableExpressions.nodeRef(null)).isEqualTo("$node[\"\"]");
        assertThat(PortableExpressions.nodeRef("say \"hi\"")).isEqualTo("$node[\"say \\\"hi\\\"\"]");
        assertThat(PortableExpressions.nodeRef("back\\slash")).isEqualTo("$node[\"back\\\\slash\"]");
    }

    @Test
    void pathAppendsAPlainIdentifierOrBracketSyntax() {
        assertThat(PortableExpressions.path("$node[\"A\"]", "id")).isEqualTo("$node[\"A\"].id");
        assertThat(PortableExpressions.path("$x", "a field")).isEqualTo("$x[\"a field\"]");
        assertThat(PortableExpressions.path("$x", null)).isEqualTo("$x");
        assertThat(PortableExpressions.path("$x", "")).isEqualTo("$x");
        assertThat(PortableExpressions.path("$x", "has\"quote")).isEqualTo("$x[\"has\\\"quote\"]");
    }

    @Test
    void n8nTextLeavesNonExpressionTextAlone() {
        assertThat(PortableExpressions.n8nText(null)).isNull();
        assertThat(PortableExpressions.n8nText("plain text")).isEqualTo("plain text");
        assertThat(PortableExpressions.n8nText("== still text")).isEqualTo("= still text");
    }

    @Test
    void n8nTextRewritesTheDocumentedAccessors() {
        assertThat(PortableExpressions.n8nText("=$('Fetch').item.json.id"))
                .isEqualTo("$node[\"Fetch\"].id");
        assertThat(PortableExpressions.n8nText("=$(\"A B\").first().json"))
                .isEqualTo("$node[\"A B\"]");
        assertThat(PortableExpressions.n8nText("=$('N').last().json.deep.x"))
                .isEqualTo("$node[\"N\"].deep.x");
        assertThat(PortableExpressions.n8nText("=$node[\"Orders\"].json.total"))
                .isEqualTo("$node[\"Orders\"].total");
        assertThat(PortableExpressions.n8nText("={{$input.item.json.q}}"))
                .isEqualTo("{{$json.q}}");
        assertThat(PortableExpressions.n8nText("={{$input.first().json}}")).isEqualTo("{{$json}}");
    }

    @Test
    void n8nTextLeavesUnrecognisedExpressionSyntaxAlone() {
        assertThat(PortableExpressions.n8nText("=1 + 2")).isEqualTo("1 + 2");
        assertThat(PortableExpressions.n8nText("=$json.field")).isEqualTo("$json.field");
    }

    @Test
    void isN8nExpressionNeedsTheMarkerAndATemplateSpan() {
        assertThat(PortableExpressions.isN8nExpression("={{$json.x}}")).isTrue();
        assertThat(PortableExpressions.isN8nExpression("=1+2")).isFalse();
        assertThat(PortableExpressions.isN8nExpression("literal {{x}}")).isFalse();
        assertThat(PortableExpressions.isN8nExpression(null)).isFalse();
    }
}
