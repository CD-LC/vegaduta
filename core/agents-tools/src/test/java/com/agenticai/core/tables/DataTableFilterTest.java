package com.agenticai.core.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.tables.DataTableFilter.Condition;
import com.agenticai.core.tables.DataTableFilter.Op;
import com.agenticai.core.tables.DataTableFilter.Sort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataTableFilterTest {

    @Test
    void nothingMeansNoFilter() {
        assertThat(DataTableFilter.parse(null)).isEmpty();
        assertThat(DataTableFilter.parse("")).isEmpty();
        assertThat(DataTableFilter.parse("  ")).isEmpty();
        assertThat(DataTableFilter.parse(List.of())).isEmpty();
        assertThat(DataTableFilter.parse(Map.of())).isEmpty();
    }

    @Test
    void objectShorthandMeansEqualityOnEachKey() {
        Map<String, Object> shorthand = new LinkedHashMap<>();
        shorthand.put("status", "open");
        shorthand.put("owner", "ravi");

        assertThat(DataTableFilter.parse(shorthand)).containsExactly(
            new Condition("status", Op.EQ, "open"), new Condition("owner", Op.EQ, "ravi"));
    }

    @Test
    void listFormCarriesColumnOpAndValueAndDefaultsToEq() {
        List<Object> raw = new ArrayList<>();
        raw.add(Map.of("column", "qty", "op", "gte", "value", 5));
        raw.add(Map.of("column", "name", "value", "x"));

        assertThat(DataTableFilter.parse(raw)).containsExactly(
            new Condition("qty", Op.GTE, 5), new Condition("name", Op.EQ, "x"));
    }

    @Test
    void jsonStringsOfEitherShapeParse() {
        assertThat(DataTableFilter.parse("{\"a\": 1}")).containsExactly(new Condition("a", Op.EQ, 1));
        assertThat(DataTableFilter.parse("[{\"column\":\"a\",\"op\":\"is_null\"}]")).containsExactly(new Condition("a", Op.IS_NULL, null));
        assertThatThrownBy(() -> DataTableFilter.parse("{oops")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not valid JSON");
    }

    @Test
    void operatorAliasesAndUnknownOperators() {
        assertThat(Op.parse(">=")).isEqualTo(Op.GTE);
        assertThat(Op.parse("!=")).isEqualTo(Op.NE);
        assertThat(Op.parse("LIKE")).isEqualTo(Op.CONTAINS);
        assertThat(Op.parse("starts-with")).isEqualTo(Op.STARTS_WITH);
        assertThat(Op.parse("is empty")).isEqualTo(Op.IS_NULL);
        assertThatThrownBy(() -> Op.parse("matches")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("matches");
        assertThat(Op.IS_NULL.takesValue()).isFalse();
        assertThat(Op.EQ.takesValue()).isTrue();
    }

    @Test
    void malformedConditionsAreRejected() {
        assertThatThrownBy(() -> DataTableFilter.parse(List.of("nope"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataTableFilter.parse(List.of(Map.of("op", "eq")))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("needs a column");
        assertThatThrownBy(() -> DataTableFilter.parse(5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooManyConditionsAreRejected() {
        List<Object> raw = new ArrayList<>();
        for (int i = 0; i <= DataTableFilter.MAX_CONDITIONS; i++) {
            raw.add(Map.of("column", "c" + i, "value", i));
        }
        assertThatThrownBy(() -> DataTableFilter.parse(raw)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most");
    }

    @Test
    void sortParsing() {
        assertThat(DataTableFilter.parseSort(null)).isNull();
        assertThat(DataTableFilter.parseSort("qty")).isEqualTo(new Sort("qty", false));
        assertThat(DataTableFilter.parseSort("qty:desc")).isEqualTo(new Sort("qty", true));
        assertThat(DataTableFilter.sortOf("qty", "DESC")).isEqualTo(new Sort("qty", true));
        assertThat(DataTableFilter.sortOf(" ", "asc")).isNull();
        assertThatThrownBy(() -> DataTableFilter.parseSort("qty:sideways")).isInstanceOf(IllegalArgumentException.class);
    }
}
