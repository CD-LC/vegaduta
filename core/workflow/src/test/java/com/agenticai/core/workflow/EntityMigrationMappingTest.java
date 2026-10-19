package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * No Postgres is available to the unit suite, and Hibernate only checks a column when it first runs a query,
 * so a typo in an entity's {@code @Column} would pass every test and then fail in production. This reads the
 * Flyway migration's SQL text and checks the entity against it: table, every mapped column, nullability, key
 * columns, length and a compatible type, and that no NOT NULL column without a default is left unmapped.
 *
 * <p>It is a text check, not a schema check: it proves the names and constraints agree, not that the
 * migration applies (that needs the boot-the-jar step against deploy-postgres).
 */
class EntityMigrationMappingTest {

    private static final String TENANT_DIR = "core/app/src/main/resources/db/migration/tenant/";

    /** One parsed column definition line. */
    private record SqlColumn(String name, String type, Integer length, boolean notNull, boolean hasDefault) {
    }

    private record SqlTable(Map<String, SqlColumn> columns, Set<String> primaryKey) {
    }

    @Test
    void runNodeOutputEntityMatchesV373() {
        assertMatches(WorkflowRunNodeOutput.class, parseTable(RepoFileLocator.read(TENANT_DIR + "V373__workflow_run_node_output.sql"),
            "workflow_run_node_output"));
    }

    @Test
    void nodePinEntityMatchesV374() {
        assertMatches(WorkflowNodePin.class, parseTable(RepoFileLocator.read(TENANT_DIR + "V374__workflow_node_pin.sql"),
            "workflow_node_pin"));
    }

    @Test
    void workflowRunRunModeColumnMatchesTheV374Alter() throws Exception {
        String sql = RepoFileLocator.read(TENANT_DIR + "V374__workflow_node_pin.sql");
        Matcher m = Pattern.compile("(?im)^ALTER TABLE workflow_run ADD COLUMN IF NOT EXISTS\\s+(\\w+)\\s+(.*?);").matcher(sql);
        assertTrue(m.find(), "V374 no longer adds a column to workflow_run");
        SqlColumn sqlCol = parseColumn(m.group(1) + " " + m.group(2));

        Field f = WorkflowRun.class.getDeclaredField("runMode");
        Column c = f.getAnnotation(Column.class);
        assertEquals(sqlCol.name(), c.name());
        assertEquals(sqlCol.notNull(), !c.nullable());
        assertEquals(sqlCol.length(), c.length());
        assertTrue(sqlCol.hasDefault(), "existing rows must read as production runs");
        assertTrue(sqlCol.type().startsWith("varchar"), sqlCol.type());
    }

    // ---- entity vs table

    private static void assertMatches(Class<?> entity, SqlTable table) {
        Table t = entity.getAnnotation(Table.class);
        assertTrue(t != null && !t.name().isEmpty(), entity.getSimpleName() + " has no @Table name");

        Set<String> mapped = new LinkedHashSet<>();
        Set<String> idColumns = new LinkedHashSet<>();
        for (Field f : persistentFields(entity)) {
            Column c = f.getAnnotation(Column.class);
            String name = c != null && !c.name().isEmpty() ? c.name() : snake(f.getName());
            mapped.add(name);
            if (f.isAnnotationPresent(Id.class)) {
                idColumns.add(name);
            }
            SqlColumn col = table.columns().get(name);
            assertTrue(col != null, entity.getSimpleName() + "." + f.getName() + " maps column '" + name
                + "' which the migration does not create. Migration columns: " + table.columns().keySet());

            boolean entityNotNull = c != null && !c.nullable();
            boolean keyColumn = table.primaryKey().contains(name);
            if (entityNotNull) {
                assertTrue(col.notNull() || keyColumn,
                    entity.getSimpleName() + "." + f.getName() + " is nullable=false but '" + name + "' is nullable in SQL");
            }
            if (c != null && c.length() != 255 && col.length() != null) {
                assertEquals(col.length(), c.length(), entity.getSimpleName() + "." + f.getName() + " length vs SQL");
            }
            assertTrue(compatible(f.getType(), col.type()),
                entity.getSimpleName() + "." + f.getName() + " (" + f.getType().getSimpleName() + ") vs SQL type " + col.type());
        }

        assertEquals(table.primaryKey(), idColumns, entity.getSimpleName() + " @Id fields vs PRIMARY KEY");

        List<String> unmapped = new ArrayList<>();
        for (SqlColumn col : table.columns().values()) {
            if (!mapped.contains(col.name()) && col.notNull() && !col.hasDefault()) {
                unmapped.add(col.name());
            }
        }
        assertTrue(unmapped.isEmpty(),
            "NOT NULL columns with no default that the entity never sets, so every insert would fail: " + unmapped);
    }

    private static List<Field> persistentFields(Class<?> entity) {
        List<Field> out = new ArrayList<>();
        for (Field f : entity.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || f.isAnnotationPresent(Transient.class) || f.isSynthetic()) {
                continue;
            }
            out.add(f);
        }
        return out;
    }

    private static boolean compatible(Class<?> javaType, String sqlType) {
        String s = sqlType.toLowerCase(Locale.ROOT);
        if (javaType == UUID.class) {
            return s.equals("uuid");
        }
        if (javaType == String.class) {
            return s.equals("text") || s.startsWith("varchar");
        }
        if (javaType == int.class || javaType == Integer.class) {
            return s.equals("integer") || s.equals("int");
        }
        if (javaType == long.class || javaType == Long.class) {
            return s.equals("bigint");
        }
        if (javaType == boolean.class || javaType == Boolean.class) {
            return s.equals("boolean");
        }
        if (javaType == Instant.class) {
            return s.startsWith("timestamp");
        }
        return true;
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    // ---- SQL text parsing

    /** The columns and primary key of {@code CREATE TABLE [IF NOT EXISTS] name ( ... );}, one definition per line. */
    static SqlTable parseTable(String sql, String table) {
        String noComments = sql.lines().map(l -> {
            int i = l.indexOf("--");
            return i >= 0 ? l.substring(0, i) : l;
        }).reduce("", (a, b) -> a + b + "\n");
        Matcher start = Pattern.compile("(?i)CREATE TABLE (IF NOT EXISTS )?" + Pattern.quote(table) + "\\s*\\(").matcher(noComments);
        assertTrue(start.find(), "CREATE TABLE " + table + " not found in the migration");
        int end = noComments.indexOf("\n);", start.end());
        assertTrue(end > 0, "end of CREATE TABLE " + table + " not found");
        String body = noComments.substring(start.end(), end);

        Map<String, SqlColumn> columns = new LinkedHashMap<>();
        Set<String> pk = new LinkedHashSet<>();
        for (String raw : body.split("\n")) {
            String line = raw.strip();
            if (line.endsWith(",")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty()) {
                continue;
            }
            Matcher pkm = Pattern.compile("(?i)^PRIMARY KEY\\s*\\(([^)]*)\\)").matcher(line);
            if (pkm.find()) {
                for (String col : pkm.group(1).split(",")) {
                    pk.add(col.strip().toLowerCase(Locale.ROOT));
                }
                continue;
            }
            if (line.matches("(?i)^(CONSTRAINT|UNIQUE|FOREIGN|CHECK)\\b.*")) {
                continue;
            }
            SqlColumn col = parseColumn(line);
            columns.put(col.name(), col);
            if (line.toUpperCase(Locale.ROOT).contains("PRIMARY KEY")) {
                pk.add(col.name());
            }
        }
        assertFalse(columns.isEmpty(), "no columns parsed for " + table);
        assertFalse(pk.isEmpty(), "no primary key parsed for " + table);
        return new SqlTable(columns, pk);
    }

    private static SqlColumn parseColumn(String definition) {
        String[] parts = definition.strip().split("\\s+", 3);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? definition.strip().substring(parts[0].length()).strip() : "";
        Matcher type = Pattern.compile("^([A-Za-z]+)\\s*(?:\\((\\d+)\\))?").matcher(rest);
        assertTrue(type.find(), "cannot parse column definition: " + definition);
        String upper = rest.toUpperCase(Locale.ROOT);
        return new SqlColumn(name, type.group(1).toLowerCase(Locale.ROOT),
            type.group(2) == null ? null : Integer.valueOf(type.group(2)),
            upper.contains("NOT NULL") || upper.contains("PRIMARY KEY"), upper.contains("DEFAULT"));
    }
}
