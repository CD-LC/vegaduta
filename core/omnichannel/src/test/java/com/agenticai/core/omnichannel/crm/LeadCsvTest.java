package com.agenticai.core.omnichannel.crm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeadCsvTest {

    @Test
    void parsesHeaderRowWithSynonymsAndQuotes() {
        String csv = "\uFEFFFull Name,Email Address,Company,Deal Value,Status,Tags\r\n"
            + "\"Doe, Jane\",Jane@Acme.com,\"Acme \"\"Inc\"\"\",\"$1,500\",qualified,a;b\r\n";
        List<LeadCsv.ParsedRow> rows = LeadCsv.parse(csv);
        assertEquals(1, rows.size());
        LeadService.LeadInput in = rows.get(0).input();
        assertEquals("Doe, Jane", in.name());
        assertEquals("Jane@Acme.com", in.email());
        assertEquals("Acme \"Inc\"", in.company());
        assertEquals(new BigDecimal("1500"), in.value());
        assertEquals(LeadStage.QUALIFIED, in.stage());
        assertEquals(List.of("a", "b"), in.tags());
    }

    @Test
    void sniffsSemicolonAndTabDelimiters() {
        assertEquals("x@y.com", LeadCsv.parse("name;email\nA;x@y.com").get(0).input().email());
        assertEquals("x@y.com", LeadCsv.parse("name\temail\nA\tx@y.com").get(0).input().email());
    }

    @Test
    void headerlessFileIsPositional() {
        List<LeadCsv.ParsedRow> rows = LeadCsv.parse("Jane,jane@x.com,555,Acme,CEO\nBob,bob@x.com");
        assertEquals(2, rows.size());
        assertEquals("Jane", rows.get(0).input().name());
        assertEquals("CEO", rows.get(0).input().title());
        assertNull(rows.get(1).input().phone());
    }

    @Test
    void badRowsCarryErrorsAndDoNotAbortTheRest() {
        List<LeadCsv.ParsedRow> rows = LeadCsv.parse("name,email,stage,score\nA,a@x.com,bogus,\n,,,\nB,b@x.com,won,7\n");
        assertEquals(3, rows.size());
        assertNotNull(rows.get(0).error());
        assertNotNull(rows.get(1).error());
        assertNull(rows.get(2).error());
        assertEquals(7, rows.get(2).input().score());
    }

    @Test
    void embeddedNewlineInQuotedFieldStaysOneRecord() {
        List<LeadCsv.ParsedRow> rows = LeadCsv.parse("name,notes\nA,\"line1\nline2\"\n");
        assertEquals(1, rows.size());
        assertEquals("line1\nline2", rows.get(0).input().notes());
    }

    @Test
    void rowCapIsEnforced() {
        StringBuilder sb = new StringBuilder("name\n");
        for (int i = 0; i <= LeadCsv.MAX_ROWS; i++) {
            sb.append("n").append(i).append('\n');
        }
        assertThrows(LeadCsv.CsvException.class, () -> LeadCsv.parse(sb.toString()));
    }

    @Test
    void emptyInputYieldsNoRows() {
        assertTrue(LeadCsv.parse("").isEmpty());
        assertTrue(LeadCsv.parse(null).isEmpty());
    }

    @Test
    void exportEscapesQuotesAndNeutralisesFormulas() {
        Lead l = new Lead("=cmd|'/c calc'!A1", "a@x.com");
        l.setCompany("Acme, \"Inc\"");
        l.setTags("x,y");
        String out = LeadCsv.export(List.of(l));
        String[] lines = out.split("\r\n");
        assertEquals(String.join(",", LeadCsv.EXPORT_HEADER), lines[0]);
        assertTrue(lines[1].startsWith("'=cmd|'/c calc'!A1,a@x.com,"));
        assertTrue(lines[1].contains("\"Acme, \"\"Inc\"\"\""));
        assertTrue(lines[1].contains(",\"x;y\","));
    }
}
