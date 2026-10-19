package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link TenantSchemaGuard#requirePlainIdentifier} / {@link TenantSchemaGuard#isPlainIdentifier}
 * / {@link TenantSchemaGuard#quoteIdentifier}: the ONE validation choke point every schema, table,
 * column and index name passes through before it is interpolated into SQL text (the migrator,
 * erasure, export, the platform migration status page and provisioning all route through it).
 * Loosening the pattern in one place now loosens it everywhere, which is why its exact edges -
 * case, length, punctuation, null - are pinned here rather than in each caller's suite.
 */
class TenantSchemaGuardTest {

    private static final String SIXTY_THREE_CHARS = "t_" + "a".repeat(61);
    private static final String SIXTY_FOUR_CHARS = SIXTY_THREE_CHARS + "a";

    @Test
    void acceptsPlainLowercaseIdentifiers() {
        for (String ok : new String[] {"t_abc123", "acme_corp", "_leading_underscore", "public", "a", "x9",
            SIXTY_THREE_CHARS}) {
            assertTrue(TenantSchemaGuard.isPlainIdentifier(ok), ok);
            assertSame(ok, TenantSchemaGuard.requirePlainIdentifier(ok));
            assertEquals("\"" + ok + "\"", TenantSchemaGuard.quoteIdentifier(ok));
        }
        assertEquals(63, SIXTY_THREE_CHARS.length());
    }

    @Test
    void rejectsUppercase() {
        assertRejected("Acme");
        assertRejected("t_ABC");
    }

    @Test
    void rejectsHyphenAndOtherPunctuation() {
        assertRejected("acme-corp");
        assertRejected("acme corp");
        assertRejected("acme.corp");
        assertRejected("acme;drop");
    }

    @Test
    void rejectsEmbeddedQuotesInsteadOfDoublingThem() {
        // The old lenient renderer would have produced "tenant""quoted"; the choke point refuses it.
        assertRejected("tenant\"quoted");
        assertRejected("\"");
    }

    @Test
    void rejectsLeadingDigitAndEmpty() {
        assertRejected("9abc");
        assertRejected("");
    }

    @Test
    void rejectsSixtyFourCharacters() {
        assertEquals(64, SIXTY_FOUR_CHARS.length());
        assertRejected(SIXTY_FOUR_CHARS);
    }

    @Test
    void rejectsNullWithoutThrowingNpe() {
        assertFalse(TenantSchemaGuard.isPlainIdentifier(null));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> TenantSchemaGuard.requirePlainIdentifier(null));
        assertTrue(ex.getMessage().contains("'null'"), ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> TenantSchemaGuard.quoteIdentifier(null));
    }

    @Test
    void validatorDoesNotApplyTheProtectedSchemaList() {
        // The migrator and the startup strategy legitimately read/record history for "public";
        // refusing to DROP it is every destructive caller's own isProtectedSchema check.
        assertTrue(TenantSchemaGuard.isPlainIdentifier("public"));
        assertEquals("\"public\"", TenantSchemaGuard.quoteIdentifier("public"));
        assertTrue(TenantSchemaGuard.isProtectedSchema("public"));
        assertTrue(TenantSchemaGuard.isProtectedSchema("pg_catalog"));
        assertTrue(TenantSchemaGuard.isProtectedSchema("Not Plain"));
        assertTrue(TenantSchemaGuard.isProtectedSchema(null));
        assertFalse(TenantSchemaGuard.isProtectedSchema("t_abc"));
    }

    private static void assertRejected(String value) {
        assertFalse(TenantSchemaGuard.isPlainIdentifier(value), value);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> TenantSchemaGuard.requirePlainIdentifier(value), value);
        assertTrue(ex.getMessage().contains("'" + value + "'"), ex.getMessage());
        assertTrue(ex.getMessage().contains(TenantSchemaGuard.PLAIN_IDENTIFIER.pattern()), ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> TenantSchemaGuard.quoteIdentifier(value), value);
    }
}
