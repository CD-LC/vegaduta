package com.agenticai.core.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataTableCsvTest {

    private static final int CAP = 65_536;

    private static final List<DataTableColumn> COLUMNS = List.of(
        new DataTableColumn("name", DataTableColumnType.TEXT),
        new DataTableColumn("qty", DataTableColumnType.NUMBER),
        new DataTableColumn("active", DataTableColumnType.BOOLEAN),
        new DataTableColumn("due", DataTableColumnType.DATE),
        new DataTableColumn("meta", DataTableColumnType.JSON));

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void exportThenImportReturnsTheSameRows() {
        List<Map<String, Object>> original = List.of(
            row("name", "Ann, the \"first\"", "qty", 3L, "active", true, "due", "2026-03-04", "meta", Map.of("k", List.of(1, 2))),
            row("name", "multi\nline", "qty", -4L, "active", false),
            row("name", "=SUM(A1)", "qty", 2.5, "due", "2026-03-04T10:00:00Z"),
            row("name", "-dash", "qty", 0L));

        String csv = DataTableCsv.export(COLUMNS, original);
        DataTableCsv.Mapped back = DataTableCsv.toRows(COLUMNS, DataTableCsv.parse(csv, 100), CAP);

        assertThat(back.errors()).isEmpty();
        assertThat(back.rows()).isEqualTo(original);
    }

    @Test
    void quotePrefixedTextAndJsonStringsRoundTripAndAreNeutralised() {
        List<Map<String, Object>> original = List.of(
            row("name", "'=literal", "meta", "=HYPERLINK(\"x\")"),
            row("name", "plain", "meta", Map.of("k", 1)));

        String csv = DataTableCsv.export(COLUMNS, original);
        DataTableCsv.Mapped back = DataTableCsv.toRows(COLUMNS, DataTableCsv.parse(csv, 100), CAP);

        assertThat(csv).contains("''=literal").contains("'=HYPERLINK");
        assertThat(back.errors()).isEmpty();
        assertThat(back.rows()).isEqualTo(original);
    }

    @Test
    void exportNeutralisesFormulasInTextCellsOnly() {
        String csv = DataTableCsv.export(COLUMNS, List.of(row("name", "=1+1", "qty", -5L)));

        assertThat(csv).contains("'=1+1");
        assertThat(csv).contains(",-5,");
        assertThat(csv).startsWith("name,qty,active,due,meta\r\n");
    }

    @Test
    void importMapsHeadersCaseInsensitivelyAndReportsIgnoredOnes() {
        DataTableCsv.Parsed parsed = DataTableCsv.parse("NAME,Qty,extra\nAnn,5,zzz\n", 10);
        DataTableCsv.Mapped mapped = DataTableCsv.toRows(COLUMNS, parsed, CAP);

        assertThat(mapped.rows()).containsExactly(row("name", "Ann", "qty", 5L));
        assertThat(mapped.ignoredHeaders()).containsExactly("extra");
    }

    @Test
    void badRowsAreSkippedAndReportedTheRestImport() {
        DataTableCsv.Mapped mapped = DataTableCsv.toRows(COLUMNS,
            DataTableCsv.parse("name,qty\nok,1\nbad,notanumber\n,\nalso ok,2\n", 10), CAP);

        assertThat(mapped.rows()).hasSize(2);
        assertThat(mapped.skipped()).isEqualTo(2);
        assertThat(mapped.errors()).hasSize(1);
        assertThat(mapped.errors().get(0)).startsWith("Row 2:").contains("qty");
    }

    @Test
    void aFileWhoseHeadersMatchNothingIsRefused() {
        DataTableCsv.Parsed parsed = DataTableCsv.parse("foo,bar\n1,2\n", 10);
        assertThatThrownBy(() -> DataTableCsv.toRows(COLUMNS, parsed, CAP))
            .isInstanceOf(DataTableCsv.CsvException.class)
            .hasMessageContaining("None of the header names");
    }

    @Test
    void sizeCapsAndEmptyInputAreEnforced() {
        assertThatThrownBy(() -> DataTableCsv.parse("a\n1\n2\n3\n", 2))
            .isInstanceOf(DataTableCsv.CsvException.class).hasMessageContaining("More than 2 rows");
        assertThatThrownBy(() -> DataTableCsv.parse("", 10)).isInstanceOf(DataTableCsv.CsvException.class);
        // a CsvException is an IllegalArgumentException, so it maps to HTTP 400 like every other validation error
        assertThat(new DataTableCsv.CsvException("x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delimiterIsSniffedAndQuotesFollowRfc4180() {
        DataTableCsv.Parsed semi = DataTableCsv.parse("name;qty\n\"a;b\";2\n", 10);
        assertThat(semi.header()).containsExactly("name", "qty");
        assertThat(semi.rows().get(0)).containsExactly("a;b", "2");

        DataTableCsv.Parsed tab = DataTableCsv.parse("name\tqty\nx\t1\r\ny\t2", 10);
        assertThat(tab.rows()).hasSize(2);

        DataTableCsv.Parsed quoted = DataTableCsv.parse("name\n\"say \"\"hi\"\"\"\n", 10);
        assertThat(quoted.rows().get(0)).containsExactly("say \"hi\"");
    }

    @Test
    void aByteOrderMarkIsIgnored() {
        DataTableCsv.Parsed parsed = DataTableCsv.parse("\uFEFFname,qty\nAnn,1\n", 10);
        assertThat(parsed.header()).containsExactly("name", "qty");
    }

    @Test
    void columnsAreInferredFromTheValues() {
        DataTableCsv.Parsed parsed = DataTableCsv.parse(
            "Full Name,Qty,Done,Due,Phone,Mixed\nAnn,3,true,2026-01-02,0098765,1\nBob,4.5,false,2026-02-03,0123,x\n", 10);

        List<DataTableColumn> inferred = DataTableCsv.inferColumns(parsed, 50);

        assertThat(inferred).extracting(DataTableColumn::name)
            .containsExactly("Full_Name", "Qty", "Done", "Due", "Phone", "Mixed");
        assertThat(inferred).extracting(DataTableColumn::type).containsExactly(
            DataTableColumnType.TEXT, DataTableColumnType.NUMBER, DataTableColumnType.BOOLEAN, DataTableColumnType.DATE,
            DataTableColumnType.TEXT, DataTableColumnType.TEXT);
    }

    @Test
    void inferredNamesAreSanitisedAndUnique() {
        DataTableCsv.Parsed parsed = DataTableCsv.parse("a,A,,9lives,a!\n1,2,3,4,5\n", 10);
        List<String> names = new ArrayList<>();
        for (DataTableColumn c : DataTableCsv.inferColumns(parsed, 50)) {
            names.add(c.name());
        }
        assertThat(names).containsExactly("a", "A_2", "column_3", "c_9lives", "a_3");
        assertThatThrownBy(() -> DataTableCsv.inferColumns(parsed, 3)).isInstanceOf(DataTableCsv.CsvException.class);
    }
}
