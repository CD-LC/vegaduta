package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link CypherLiteralEscaper}'s contract directly, DB-free like this module's other two
 * suites - and with the same honestly-disclosed ceiling {@link GraphMemoryServiceTest}'s javadoc
 * states: no real AGE-enabled Postgres is reachable from here, so these tests prove the encoder
 * emits the escape sequences the openCypher spec defines, not that AGE's lexer consumes them
 * (that residual gap is documented on the escaper itself).
 *
 * <p>The suite is organized around the two failure modes the old strip-based defense had:
 * corruption of legitimate values (O'Brien became OBrien - escaping must round-trip them) and
 * incompleteness against smuggled syntax (invisible unicode, dollar-quote termination - the
 * allowlist layers must leave every payload inert).
 */
class CypherLiteralEscaperTest {

    // --- legitimate values must survive (escape, don't strip) ---

    @Test
    void apostrophesAreEscapedNotStripped() {
        assertThat(CypherLiteralEscaper.escapeCypherString("O'Brien")).isEqualTo("O\\'Brien");
    }

    @Test
    void backslashesInWindowsPathsAreEscapedNotStripped() {
        assertThat(CypherLiteralEscaper.escapeCypherString("C:\\Users\\priya"))
            .isEqualTo("C:\\\\Users\\\\priya");
    }

    @Test
    void doubleQuotesAreEscapedNotStripped() {
        assertThat(CypherLiteralEscaper.escapeCypherString("the \"Acme\" deal"))
            .isEqualTo("the \\\"Acme\\\" deal");
    }

    @Test
    void unicodeHomoglyphQuotesPassThroughUnchanged_theyAreInertToTheCypherLexer() {
        // U+2019 (right single quotation mark) and U+FF07 (fullwidth apostrophe) LOOK like
        // quote characters but are ordinary letters to the Cypher lexer - stripping them would
        // corrupt legitimate typographic names for zero security gain, so they must survive.
        String typographic = "O\u2019Brien\uFF07s";
        assertThat(CypherLiteralEscaper.escapeCypherString(typographic)).isEqualTo(typographic);
    }

    @Test
    void nullEncodesToEmptyString_failOpenLikeTheCallers() {
        assertThat(CypherLiteralEscaper.escapeCypherString(null)).isEmpty();
    }

    // --- injection payloads must arrive defused ---

    @Test
    void quoteBreakInjectionCannotEscapeTheStringLiteral() {
        String escaped = CypherLiteralEscaper.escapeCypherString("x'}) DETACH DELETE n //");
        // Every apostrophe in the output must be the escaped form \' - removing all escaped
        // pairs must leave no bare quote behind to close the surrounding literal.
        assertThat(escaped).contains("x\\'})");
        assertThat(escaped.replace("\\'", "")).doesNotContain("'");
    }

    @Test
    void dollarSignsAreEncodedSoTheSqlDollarQuoteCanNeverBeTerminated() {
        // The Cypher text is embedded in a Postgres $$-dollar-quoted literal, which has no
        // escape mechanism at all - the only safe encoding is one whose OUTPUT contains no
        // dollar sign whatsoever. Semicolons stay: inside an intact string literal they are
        // inert, and stripping them was pure value-corruption under the old defense.
        String escaped = CypherLiteralEscaper.escapeCypherString("pay $$) AS x; DROP TABLE agents; --");
        assertThat(escaped).doesNotContain("$");
        assertThat(escaped).contains("\\u0024\\u0024");
        assertThat(escaped).contains(";");
    }

    @Test
    void cypherParameterSpoofIsNeutralized() {
        // '$victim' must not survive as a $-prefixed token AGE could ever read as a parameter
        // reference - the backslash-u0024 escape round-trips to a literal dollar in the stored value.
        assertThat(CypherLiteralEscaper.escapeCypherString("$victim")).isEqualTo("\\u0024victim");
    }

    @Test
    void controlAndInvisibleUnicodeIsStrippedOutright() {
        // U+200B zero-width space, U+0000 NUL, U+202E right-to-left override (the classic
        // payload-visual-spoofing char), U+FEFF BOM - all category C, all gone, nothing escaped.
        assertThat(CypherLiteralEscaper.escapeCypherString("Pri\u200Bya\u0000\u202Ename\uFEFF"))
            .isEqualTo("Priyaname");
    }

    @Test
    void escapedPayloadHiddenBehindInvisibleCharsIsStillDefused() {
        // Invisible chars interleaved INTO a quote-break attempt: the strip must not
        // accidentally reassemble the payload into something the escape pass then misses.
        String escaped = CypherLiteralEscaper.escapeCypherString("evil\u200B'\u200B; DETACH DELETE n");
        assertThat(escaped.replace("\\'", "")).doesNotContain("'");
        assertThat(escaped).doesNotContain("\u200B");
    }

    // --- length cap ---

    @Test
    void valuesAreCappedAtMaxLength() {
        String oversized = "a".repeat(CypherLiteralEscaper.MAX_VALUE_LENGTH + 100);
        assertThat(CypherLiteralEscaper.escapeCypherString(oversized))
            .hasSize(CypherLiteralEscaper.MAX_VALUE_LENGTH);
    }

    @Test
    void capCountsCodePointsSoSurrogatePairsAreNeverSplit() {
        // U+1F600 is a surrogate pair in UTF-16; a char-based truncation could cut one in half,
        // producing an unpaired surrogate (category Cs - the very thing the strip removes).
        String emoji = "\uD83D\uDE00".repeat(CypherLiteralEscaper.MAX_VALUE_LENGTH + 10);
        String escaped = CypherLiteralEscaper.escapeCypherString(emoji);
        assertThat(escaped.codePointCount(0, escaped.length()))
            .isEqualTo(CypherLiteralEscaper.MAX_VALUE_LENGTH);
        assertThat(escaped).endsWith("\uD83D\uDE00");
    }

    // --- identifiers: allowlist outright, no escaping ---

    @Test
    void safeIdentifierAcceptsPlainSnakeCaseNames() {
        assertThat(CypherLiteralEscaper.safeIdentifier("subject_key")).isEqualTo("subject_key");
        assertThat(CypherLiteralEscaper.safeIdentifier("RELATION")).isEqualTo("RELATION");
        assertThat(CypherLiteralEscaper.safeIdentifier("_Entity2")).isEqualTo("_Entity2");
    }

    @Test
    void safeIdentifierRejectsEverythingElse_identifiersCannotBeEscapedOnlyRefused() {
        assertThatThrownBy(() -> CypherLiteralEscaper.safeIdentifier("bad-name"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CypherLiteralEscaper.safeIdentifier("has space"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CypherLiteralEscaper.safeIdentifier("with'quote"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CypherLiteralEscaper.safeIdentifier("1leading_digit"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CypherLiteralEscaper.safeIdentifier(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CypherLiteralEscaper.safeIdentifier(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CypherLiteralEscaper.safeIdentifier("x".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
