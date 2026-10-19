package com.agenticai.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** SEC-046: spreadsheet formula injection is neutralised in every CSV export that uses {@link CsvCells}. */
class CsvCellsTest {

    @Test
    void formulaTriggersArePrefixedWithAQuote() {
        assertEquals("'=1+1", CsvCells.neutraliseFormula("=1+1"));
        assertEquals("'+A1", CsvCells.neutraliseFormula("+A1"));
        assertEquals("'-A1", CsvCells.neutraliseFormula("-A1"));
        assertEquals("'@SUM(A1)", CsvCells.neutraliseFormula("@SUM(A1)"));
        assertEquals("'\t=1", CsvCells.neutraliseFormula("\t=1"));
        assertEquals("'\r=1", CsvCells.neutraliseFormula("\r=1"));
        assertEquals("'-2+3+cmd|' /C calc'!A0", CsvCells.neutraliseFormula("-2+3+cmd|' /C calc'!A0"));
    }

    @Test
    void plainNumbersAndOrdinaryTextAreUnchanged() {
        assertEquals("-5", CsvCells.neutraliseFormula("-5"));
        assertEquals("+1.25", CsvCells.neutraliseFormula("+1.25"));
        assertEquals("-3e4", CsvCells.neutraliseFormula("-3e4"));
        assertEquals("hello", CsvCells.neutraliseFormula("hello"));
        assertEquals("a=b", CsvCells.neutraliseFormula("a=b"));
        assertEquals("", CsvCells.neutraliseFormula(""));
        assertEquals("", CsvCells.neutraliseFormula(null));
    }

    @Test
    void escapeNeutralisesThenQuotes() {
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"", CsvCells.escape("=HYPERLINK(\"http://x\",\"y\")"));
        assertEquals("\"a,b\"", CsvCells.escape("a,b"));
        assertEquals("plain", CsvCells.escape("plain"));
        assertEquals("", CsvCells.escape(null));
    }
}
