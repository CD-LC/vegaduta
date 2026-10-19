package com.agenticai.core.tables;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.tables.DataTableFilter.Condition;
import com.agenticai.core.tables.DataTableFilter.Op;
import com.agenticai.core.tables.DataTableFilter.Sort;
import com.agenticai.core.tables.DataTableQueryBuilder.Where;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The op x column-type matrix, rendered to SQL without a database. */
class DataTableQueryBuilderTest {

    private static final int CAP = 65_536;

    private static final List<DataTableColumn> COLUMNS = List.of(
        new DataTableColumn("name", DataTableColumnType.TEXT),
        new DataTableColumn("qty", DataTableColumnType.NUMBER),
        new DataTableColumn("active", DataTableColumnType.BOOLEAN),
        new DataTableColumn("due", DataTableColumnType.DATE),
        new DataTableColumn("meta", DataTableColumnType.JSON));

    private static Where where(Condition... conditions) {
        return DataTableQueryBuilder.where(COLUMNS, List.of(conditions), CAP);
    }

    @Test
    void noConditionsMeansNoRestriction() {
        assertThat(DataTableQueryBuilder.where(COLUMNS, List.of(), CAP).isEmpty()).isTrue();
        assertThat(DataTableQueryBuilder.where(COLUMNS, null, CAP).isEmpty()).isTrue();
    }

    @Test
    void textEqualityAndInequalityBindTheValue() {
        Where eq = where(new Condition("name", Op.EQ, "Ann"));
        assertThat(eq.sql()).isEqualTo("(r.data ->> 'name') = :p0");
        assertThat(eq.params()).containsEntry("p0", "Ann");

        Where ne = where(new Condition("name", Op.NE, "Ann"));
        assertThat(ne.sql()).isEqualTo("((r.data ->> 'name') IS NULL OR (r.data ->> 'name') <> :p0)");
    }

    @Test
    void numbersCompareAsNumericWithBigDecimalParameters() {
        Where gt = where(new Condition("qty", Op.GT, "5"));
        assertThat(gt.sql()).isEqualTo("CAST((r.data ->> 'qty') AS numeric) > :p0");
        assertThat(gt.params().get("p0")).isEqualTo(BigDecimal.valueOf(5L));

        assertThat(where(new Condition("qty", Op.LTE, 2.5)).sql()).isEqualTo("CAST((r.data ->> 'qty') AS numeric) <= :p0");
        assertThat(where(new Condition("qty", Op.GTE, 1)).sql()).contains(">= :p0");
        assertThat(where(new Condition("qty", Op.LT, 1)).sql()).contains("< :p0");
    }

    @Test
    void booleansCompareAsBoolean() {
        Where w = where(new Condition("active", Op.EQ, "yes"));
        assertThat(w.sql()).isEqualTo("CAST((r.data ->> 'active') AS boolean) = :p0");
        assertThat(w.params()).containsEntry("p0", true);
    }

    @Test
    void datesCompareAsNormalisedText() {
        Where w = where(new Condition("due", Op.GTE, "2026-01-01T00:00:00+00:00"));
        assertThat(w.sql()).isEqualTo("(r.data ->> 'due') >= :p0");
        assertThat(w.params()).containsEntry("p0", "2026-01-01T00:00:00Z");
    }

    @Test
    void containsIsCaseInsensitiveAndEscapesLikeWildcards() {
        Where w = where(new Condition("name", Op.CONTAINS, "50%_Off"));
        assertThat(w.sql()).isEqualTo("LOWER((r.data ->> 'name')) LIKE :p0");
        assertThat(w.params()).containsEntry("p0", "%50\\%\\_off%");
    }

    @Test
    void startsWithIsCaseSensitivePrefix() {
        Where w = where(new Condition("name", Op.STARTS_WITH, "Ab"));
        assertThat(w.sql()).isEqualTo("(r.data ->> 'name') LIKE :p0");
        assertThat(w.params()).containsEntry("p0", "Ab%");
    }

    @Test
    void inExpandsOneParameterPerValueFromAListOrCommaText() {
        Where text = where(new Condition("name", Op.IN, List.of("a", "b")));
        assertThat(text.sql()).isEqualTo("(r.data ->> 'name') IN (:p0, :p1)");

        Where nums = where(new Condition("qty", Op.IN, "1, 2,3"));
        assertThat(nums.sql()).isEqualTo("CAST((r.data ->> 'qty') AS numeric) IN (:p0, :p1, :p2)");
        assertThat(nums.params()).hasSize(3);

        assertThatThrownBy(() -> where(new Condition("name", Op.IN, List.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> where(new Condition("name", Op.IN, java.util.Collections.nCopies(101, "x"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most");
    }

    @Test
    void nullChecksNeedNoValueAndWorkOnEveryType() {
        for (DataTableColumn c : COLUMNS) {
            assertThat(where(new Condition(c.name(), Op.IS_NULL, null)).sql()).isEqualTo("(r.data ->> '" + c.name() + "') IS NULL");
            assertThat(where(new Condition(c.name(), Op.NOT_NULL, null)).sql()).isEqualTo("(r.data ->> '" + c.name() + "') IS NOT NULL");
        }
    }

    @Test
    void jsonColumnsCompareAsJsonbOrSearchAsText() {
        Where eq = where(new Condition("meta", Op.EQ, "{\"a\":1}"));
        assertThat(eq.sql()).isEqualTo("(r.data -> 'meta') = CAST(:p0 AS jsonb)");
        assertThat(eq.params()).containsEntry("p0", "{\"a\":1}");

        assertThat(where(new Condition("meta", Op.CONTAINS, "x")).sql()).isEqualTo("LOWER((r.data ->> 'meta')) LIKE :p0");
        assertThatThrownBy(() -> where(new Condition("meta", Op.IN, "a"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rowIdSupportsEqualityAndInOnly() {
        String id = "6f0c2b6e-5f0a-4a43-9d57-0d6a7b2c9a11";
        assertThat(where(new Condition("_id", Op.EQ, id)).sql()).isEqualTo("CAST(r.id AS text) = :p0");
        assertThat(where(new Condition("_id", Op.IN, List.of(id, id))).sql()).isEqualTo("CAST(r.id AS text) IN (:p0, :p1)");
        assertThatThrownBy(() -> where(new Condition("_id", Op.EQ, "not-a-uuid"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> where(new Condition("_id", Op.GT, id))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conditionsAreAndedWithDistinctParameters() {
        Where w = where(new Condition("name", Op.EQ, "a"), new Condition("qty", Op.GT, 1), new Condition("active", Op.EQ, true));
        assertThat(w.sql()).isEqualTo(
            "(r.data ->> 'name') = :p0 AND CAST((r.data ->> 'qty') AS numeric) > :p1 AND CAST((r.data ->> 'active') AS boolean) = :p2");
        assertThat(w.params()).containsOnlyKeys("p0", "p1", "p2");
    }

    @Test
    void operatorsThatDoNotFitTheTypeAreRefused() {
        assertThatThrownBy(() -> where(new Condition("active", Op.GT, true))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not apply");
        assertThatThrownBy(() -> where(new Condition("qty", Op.CONTAINS, "1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> where(new Condition("qty", Op.STARTS_WITH, "1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> where(new Condition("meta", Op.GT, "1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valuesThatDoNotFitTheColumnTypeAreRefused() {
        assertThatThrownBy(() -> where(new Condition("qty", Op.EQ, "abc"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> where(new Condition("due", Op.EQ, "yesterday"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> where(new Condition("name", Op.EQ, null))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("needs a value");
    }

    @Test
    void unknownColumnsAndInjectionAttemptsNeverReachTheSql() {
        assertThatThrownBy(() -> where(new Condition("nope", Op.EQ, "x"))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown column");
        assertThatThrownBy(() -> where(new Condition("name' OR '1'='1", Op.EQ, "x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataTableQueryBuilder.literal("a'b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataTableQueryBuilder.literal("_id")).isInstanceOf(IllegalArgumentException.class);
        // a value is only ever a bound parameter, never SQL text
        Where w = where(new Condition("name", Op.EQ, "x'; DROP TABLE data_table; --"));
        assertThat(w.sql()).doesNotContain("DROP");
        assertThat(w.params()).containsEntry("p0", "x'; DROP TABLE data_table; --");
    }

    @Test
    void orderByAlwaysEndsInAStableTiebreaker() {
        assertThat(DataTableQueryBuilder.orderBy(COLUMNS, null)).isEqualTo(" ORDER BY r.created_at ASC, r.id ASC");
        assertThat(DataTableQueryBuilder.orderBy(COLUMNS, new Sort("name", true)))
            .isEqualTo(" ORDER BY (r.data ->> 'name') DESC NULLS LAST, r.created_at ASC, r.id ASC");
        assertThat(DataTableQueryBuilder.orderBy(COLUMNS, new Sort("qty", false)))
            .isEqualTo(" ORDER BY CAST((r.data ->> 'qty') AS numeric) ASC NULLS LAST, r.created_at ASC, r.id ASC");
        assertThat(DataTableQueryBuilder.orderBy(COLUMNS, new Sort("meta", false)))
            .isEqualTo(" ORDER BY (r.data -> 'meta') ASC NULLS LAST, r.created_at ASC, r.id ASC");
        assertThat(DataTableQueryBuilder.orderBy(COLUMNS, new Sort("_updated_at", true)))
            .isEqualTo(" ORDER BY r.updated_at DESC, r.id ASC");
        assertThatThrownBy(() -> DataTableQueryBuilder.orderBy(COLUMNS, new Sort("nope", false)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
