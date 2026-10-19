package com.agenticai.core.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataTableValidatorTest {

    private static final int CAP = 65_536;

    private static final List<DataTableColumn> COLUMNS = List.of(
        new DataTableColumn("name", DataTableColumnType.TEXT),
        new DataTableColumn("qty", DataTableColumnType.NUMBER),
        new DataTableColumn("active", DataTableColumnType.BOOLEAN),
        new DataTableColumn("due", DataTableColumnType.DATE),
        new DataTableColumn("meta", DataTableColumnType.JSON));

    private static Object coerce(String column, Object value) {
        return DataTableValidator.coerce(DataTableValidator.find(COLUMNS, column), value, CAP);
    }

    @Test
    void numbersNormaliseToLongWhenIntegralAndDoubleOtherwise() {
        assertThat(coerce("qty", "12")).isEqualTo(12L);
        assertThat(coerce("qty", 12.0)).isEqualTo(12L);
        assertThat(coerce("qty", "1e3")).isEqualTo(1000L);
        assertThat(coerce("qty", " 12.5 ")).isEqualTo(12.5);
        assertThat(coerce("qty", 0.0)).isEqualTo(0L);
        assertThat(coerce("qty", "-7")).isEqualTo(-7L);
    }

    @Test
    void badNumbersAreRejectedWithTheColumnNamed() {
        assertThatThrownBy(() -> coerce("qty", "twelve")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("qty");
        assertThatThrownBy(() -> coerce("qty", Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coerce("qty", true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void booleansAcceptTheCommonSpellings() {
        assertThat(coerce("active", "Yes")).isEqualTo(true);
        assertThat(coerce("active", "0")).isEqualTo(false);
        assertThat(coerce("active", 1)).isEqualTo(true);
        assertThatThrownBy(() -> coerce("active", "maybe")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void datesNormaliseToIsoDateOrUtcInstant() {
        assertThat(coerce("due", "2026-03-04")).isEqualTo("2026-03-04");
        assertThat(coerce("due", "2026-03-04T10:00:00+05:30")).isEqualTo("2026-03-04T04:30:00Z");
        assertThat(coerce("due", "2026-03-04T10:00:00")).isEqualTo("2026-03-04T10:00:00Z");
        assertThatThrownBy(() -> coerce("due", "04/03/2026")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyStringIsNoValueExceptForText() {
        assertThat(coerce("qty", "")).isNull();
        assertThat(coerce("due", "")).isNull();
        assertThat(coerce("name", "")).isEqualTo("");
        assertThat(coerce("name", null)).isNull();
    }

    @Test
    void textTakesScalarsButNotObjects() {
        assertThat(coerce("name", 5)).isEqualTo("5");
        assertThatThrownBy(() -> coerce("name", Map.of("a", 1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonParsesObjectStringsAndKeepsPlainStrings() {
        assertThat(coerce("meta", "{\"a\":1}")).isEqualTo(Map.of("a", 1));
        assertThat(coerce("meta", "plain")).isEqualTo("plain");
        assertThatThrownBy(() -> coerce("meta", "{not json")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cellsOverTheByteCapAreRejected() {
        DataTableColumn name = DataTableValidator.find(COLUMNS, "name");
        assertThat(DataTableValidator.coerce(name, "x".repeat(100), 200)).isEqualTo("x".repeat(100));
        assertThatThrownBy(() -> DataTableValidator.coerce(name, "x".repeat(300), 200))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limit is 200 bytes");
        // the cap counts UTF-8 bytes, not characters
        assertThatThrownBy(() -> DataTableValidator.coerce(name, "é".repeat(120), 200))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizeRowRejectsUnknownColumnsAndListsTheKnownOnes() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("nme", "x");
        assertThatThrownBy(() -> DataTableValidator.normalizeRow(COLUMNS, raw, CAP))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown column 'nme'")
            .hasMessageContaining("name");
    }

    @Test
    void normalizeRowMatchesColumnsCaseInsensitivelyAndKeepsNullsForPatches() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("NAME", "Ann");
        raw.put("qty", null);
        Map<String, Object> out = DataTableValidator.normalizeRow(COLUMNS, raw, CAP);

        assertThat(out).containsEntry("name", "Ann").containsKey("qty");
        assertThat(out.get("qty")).isNull();
        assertThat(DataTableValidator.withoutNulls(out)).containsOnlyKeys("name");
    }

    @Test
    void columnNamesFollowTheIdentifierGrammar() {
        assertThat(new DataTableColumn(" ok_1 ", DataTableColumnType.TEXT).name()).isEqualTo("ok_1");
        for (String bad : new String[] {"", "1abc", "_hidden", "has space", "a'b", "x-y", "a".repeat(65)}) {
            assertThatThrownBy(() -> new DataTableColumn(bad, DataTableColumnType.TEXT))
                .as("name %s", bad)
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> DataTableColumnType.parse("blob")).isInstanceOf(IllegalArgumentException.class);
    }
}
