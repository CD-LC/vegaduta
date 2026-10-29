package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.agenticai.core.workflow.expression.TemplateScanner.Match;

/** {@link TemplateScanner}: span finding, quoting, nesting and the per-span size bound. */
class TemplateScannerTest {

    private static final int MAX = 2_000;

    @Test
    void plainTextAndUnclosedOpenersFindNothing() {
        assertNull(TemplateScanner.next("no spans", 0, MAX));
        assertNull(TemplateScanner.next("a {{ b", 0, MAX), "unclosed opener is literal text");
        assertNull(TemplateScanner.next("{{", 0, MAX));
        assertNull(TemplateScanner.next("}}", 0, MAX));
        assertNull(TemplateScanner.next("{{ a", 0, MAX), "no closer anywhere after the opener");
    }

    @Test
    void aSimpleSpanReportsBothRanges() {
        Match m = TemplateScanner.next("{{ x }}", 0, MAX);
        assertEquals(0, m.start());
        assertEquals(7, m.end());
        assertEquals(" x ", "{{ x }}".substring(m.exprStart(), m.exprEnd()));
    }

    @Test
    void blankAndEmptySpansAreSkipped() {
        assertNull(TemplateScanner.next("{{}}", 0, MAX));
        assertNull(TemplateScanner.next("{{   }}", 0, MAX));
        Match m = TemplateScanner.next("{{}} {{ y }}", 0, MAX);
        assertEquals(" y ", "{{}} {{ y }}".substring(m.exprStart(), m.exprEnd()));
    }

    @Test
    void closersInsideStringLiteralsDoNotEndTheSpan() {
        String t = "{{ '}}' }}";
        Match m = TemplateScanner.next(t, 0, MAX);
        assertEquals(t.length(), m.end());
        assertEquals(" '}}' ", t.substring(m.exprStart(), m.exprEnd()));

        String escaped = "{{ 'a\\'b}}' }}";
        Match e = TemplateScanner.next(escaped, 0, MAX);
        assertEquals(escaped.length(), e.end());
    }

    @Test
    void nestedObjectBracesAreBalanced() {
        String t = "{{ {a: 1} }}";
        Match m = TemplateScanner.next(t, 0, MAX);
        assertEquals(t.length(), m.end());
        assertEquals(" {a: 1} ", t.substring(m.exprStart(), m.exprEnd()));
    }

    @Test
    void scanningResumesAfterThePreviousSpan() {
        String t = "{{ a }} middle {{ b }}";
        Match first = TemplateScanner.next(t, 0, MAX);
        assertEquals(" a ", t.substring(first.exprStart(), first.exprEnd()));
        Match second = TemplateScanner.next(t, first.end(), MAX);
        assertEquals(" b ", t.substring(second.exprStart(), second.exprEnd()));
        assertNull(TemplateScanner.next(t, second.end(), MAX));
    }

    @Test
    void aSpanLongerThanTheBoundIsLiteralTextNotAnError() {
        String t = "{{ " + "x".repeat(50) + " }} tail";
        assertNull(TemplateScanner.next(t, 0, 10), "an over-long span is skipped as text");
    }

    @Test
    void manyStrayOpenersStopBeingScannedOnceTheWorkBudgetIsSpent() {
        String t = "{{ ".repeat(600) + "}}";
        assertNull(TemplateScanner.next(t, 0, 10));
    }
}
