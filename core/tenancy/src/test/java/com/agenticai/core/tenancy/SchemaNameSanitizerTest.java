package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link SchemaNameSanitizer} is the one shared place every raw tenant
 * identifier (a JWT {@code tenant_id} claim on the hot request path, or a
 * signup form's org name at provisioning time) passes through on its way to
 * becoming a literal Postgres schema name / {@code SET search_path} value -
 * see its own javadoc. Its correctness is a direct tenant-isolation
 * guarantee: any raw string this method fails to neutralize is a potential
 * SQL-injection or schema-collision vector, or (see {@link
 * TenantSchemaResolver#currentSchemaName()}) a straight path to querying
 * the wrong tenant's data. This class had zero test coverage before this
 * suite despite being exactly the kind of pure, dependency-free logic that
 * is cheapest to test and most dangerous to get wrong.
 */
class SchemaNameSanitizerTest {

    /**
     * A valid unquoted Postgres identifier per this class's own contract:
     * only lowercase letters, digits, and underscores, and never starting
     * with a digit (underscore-led is fine - see {@code
     * inputThatSanitizesToAllInvalidCharsIsKeptAsIs} below). Used for
     * inputs whose exact transliteration is tedious/error-prone to
     * hand-verify character-by-character - asserting the safety invariant
     * the method exists to guarantee is both more robust and more honest
     * about what actually matters than a brittle hardcoded literal.
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]*$");

    @Test
    void nullInputReturnsDefaultSchema() {
        assertEquals(SchemaNameSanitizer.DEFAULT_SCHEMA, SchemaNameSanitizer.sanitize(null));
    }

    @Test
    void blankInputReturnsDefaultSchema() {
        assertEquals(SchemaNameSanitizer.DEFAULT_SCHEMA, SchemaNameSanitizer.sanitize("   "));
    }

    @Test
    void emptyInputReturnsDefaultSchema() {
        assertEquals(SchemaNameSanitizer.DEFAULT_SCHEMA, SchemaNameSanitizer.sanitize(""));
    }

    @Test
    void nullInputReturnsCallerSuppliedFallback() {
        assertEquals("custom_fallback", SchemaNameSanitizer.sanitize(null, "custom_fallback"));
    }

    @Test
    void wellFormedIdentifierPassesThroughUnchanged() {
        assertEquals("tenant_b", SchemaNameSanitizer.sanitize("tenant_b"));
    }

    @Test
    void uppercaseIsLowered() {
        // Postgres unquoted identifiers are case-folded to lowercase - two
        // tenants named "Acme" and "acme" must resolve to the SAME schema
        // (a provisioning-time collision the caller is expected to catch
        // via TenantRepository#findBySchemaName), not two different ones
        // that would otherwise both look "valid" to this method alone.
        assertEquals("acme_corp", SchemaNameSanitizer.sanitize("Acme_Corp"));
    }

    @Test
    void spacesAreReplacedWithUnderscores() {
        assertEquals("acme_inc", SchemaNameSanitizer.sanitize("Acme Inc"));
    }

    @Test
    void sqlMetacharactersAreNeutralized() {
        // The exact class of input this method exists to defang: a raw
        // org name or claim value containing characters that would be
        // dangerous if ever concatenated into a literal `CREATE SCHEMA`
        // or `SET search_path` statement instead of passed as a bind
        // parameter. Asserting the safety invariant rather than a
        // hand-counted literal, since exact underscore counts here are
        // easy to miscompute and not actually what matters.
        String result = SchemaNameSanitizer.sanitize("Robert'; DROP TABLE students;--");
        assertTrue(SAFE_IDENTIFIER.matcher(result).matches(), "not a safe identifier: " + result);
        assertFalse(result.contains("'"));
        assertFalse(result.contains(";"));
        assertFalse(result.contains(" "));
        assertFalse(result.contains("-"));
        // Letters themselves are untouched, just re-cased and re-joined -
        // confirms this isn't accidentally stripping/rejecting the input
        // outright and silently falling back to a default schema instead.
        assertTrue(result.contains("robert"));
        assertTrue(result.contains("drop"));
        assertTrue(result.contains("table"));
        assertTrue(result.contains("students"));
    }

    @Test
    void doubleQuotesAndSemicolonsAreNeutralized() {
        String result = SchemaNameSanitizer.sanitize("a\";\"b;c");
        assertTrue(SAFE_IDENTIFIER.matcher(result).matches(), "not a safe identifier: " + result);
        assertFalse(result.contains("\""));
        assertFalse(result.contains(";"));
    }

    @Test
    void leadingDigitGetsPrefixed() {
        // Postgres unquoted identifiers cannot start with a digit -
        // confirms the "t_" prefix branch, not just that digits survive.
        assertEquals("t_123abc", SchemaNameSanitizer.sanitize("123abc"));
    }

    @Test
    void inputThatSanitizesToAllInvalidCharsIsKeptAsIs() {
        // Every character replaced with "_" then... the whole thing is
        // just underscores. isBlank() checks for whitespace, not
        // underscores, so this does NOT hit the fallback branch - it's
        // still a syntactically valid (if useless) Postgres identifier.
        // Documented here as characterization of actual behavior, not
        // endorsement - a caller relying on "garbage in -> default schema
        // out" would be wrong to assume that from reading this method's
        // name alone.
        assertEquals("____", SchemaNameSanitizer.sanitize("!@#$"));
    }

    @Test
    void resultIsTruncatedToPostgresIdentifierLimit() {
        String raw = "a".repeat(100);
        String result = SchemaNameSanitizer.sanitize(raw);
        assertEquals(63, result.length());
        assertEquals("a".repeat(63), result);
    }

    @Test
    void exactlySixtyThreeCharsIsNotTruncated() {
        String raw = "b".repeat(63);
        assertEquals(raw, SchemaNameSanitizer.sanitize(raw));
    }

    @Test
    void unicodeCharactersAreNeutralizedNotPreserved() {
        // A non-ASCII org name (a real signup scenario) must not silently
        // pass through as an unquoted Postgres identifier just because
        // Java's case-folding treats it as a normal "letter".
        String result = SchemaNameSanitizer.sanitize("Ünïcödé Örg");
        assertTrue(SAFE_IDENTIFIER.matcher(result).matches(), "not a safe identifier: " + result);
        assertFalse(result.contains("ü"));
        assertFalse(result.contains("ö"));
        assertFalse(result.contains("é"));
    }

    @Test
    void alreadyValidLowercaseWithDigitsAndUnderscoresIsIdempotent() {
        String valid = "tenant_42_prod";
        assertEquals(valid, SchemaNameSanitizer.sanitize(valid));
        // Sanitizing an already-sanitized value must be a no-op - this
        // property is what makes it safe for TenantProvisioningService to
        // re-derive a schema name from a stored value without drift.
        assertEquals(valid, SchemaNameSanitizer.sanitize(SchemaNameSanitizer.sanitize(valid)));
    }
}
