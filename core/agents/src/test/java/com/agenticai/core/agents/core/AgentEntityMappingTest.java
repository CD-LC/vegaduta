package com.agenticai.core.agents.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Table;
import org.junit.jupiter.api.Test;

/**
 * ApplicationContext-free, database-free pin of the {@code agent} table mapping: boots Hibernate's
 * metadata model for {@link Agent} alone and compares every mapped column (name, type, nullability,
 * length, precision/scale) plus the element-collection tables against a golden list captured from
 * the entity BEFORE its fields were grouped into embeddable value objects. Grouping fields must
 * not move, rename or retype a column - there is deliberately no migration - so any drift here is a
 * schema change in disguise.
 */
class AgentEntityMappingTest {

    private static Metadata metadata() {
        StandardServiceRegistry registry =
                new StandardServiceRegistryBuilder()
                        .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                        .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                        .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                        .build();
        return new MetadataSources(registry).addAnnotatedClass(Agent.class).buildMetadata();
    }

    static List<String> describe(Metadata metadata) {
        PersistentClass agent = metadata.getEntityBinding(Agent.class.getName());
        List<String> out = new ArrayList<>();
        Table table = agent.getTable();
        for (Column c : table.getColumns()) {
            out.add(
                    table.getName()
                            + "."
                            + c.getName()
                            + "|"
                            + c.getSqlType(metadata)
                            + "|null="
                            + c.isNullable()
                            + "|len="
                            + c.getLength()
                            + "|prec="
                            + c.getPrecision()
                            + "|scale="
                            + c.getScale());
        }
        for (var coll : metadata.getCollectionBindings()) {
            if (!coll.getRole().startsWith(Agent.class.getName() + ".")) continue;
            Table ct = coll.getCollectionTable();
            for (Column c : ct.getColumns()) {
                out.add(
                        ct.getName()
                                + "."
                                + c.getName()
                                + "|"
                                + c.getSqlType(metadata)
                                + "|null="
                                + c.isNullable()
                                + "|len="
                                + c.getLength());
            }
        }
        return out.stream().sorted().toList();
    }

    @Test
    void agentTableColumnsAreExactlyTheGoldenSet() {
        List<String> actual = describe(metadata());
        try {
            // Diagnostic dump for regenerating the golden list deliberately (never in CI output).
            java.nio.file.Files.write(java.nio.file.Path.of("target", "agent-mapping-actual.txt"), actual);
        } catch (java.io.IOException ignored) {
            // diagnostic only
        }
        assertThat(actual).containsExactlyElementsOf(AgentEntityMappingGolden.COLUMNS);
    }

    /**
     * The hand-written HQL strings on {@link AgentRepository} reference entity attribute
     * paths; building the SessionFactory (no connection needed) and parsing them against the
     * metamodel proves they still resolve after attributes move into embeddables.
     */
    @Test
    void repositoryHqlStillResolvesAgainstTheMetamodel() {
        try (var sf = metadata().buildSessionFactory();
                var session = sf.openSession()) {
            int checked = 0;
            for (var method : AgentRepository.class.getDeclaredMethods()) {
                var q = method.getAnnotation(org.springframework.data.jpa.repository.Query.class);
                if (q == null) continue;
                String hql = q.value();
                if (hql.trim().toUpperCase(java.util.Locale.ROOT).startsWith("SELECT")) {
                    session.createSelectionQuery(hql, Object.class);
                } else {
                    session.createMutationQuery(hql);
                }
                checked++;
            }
            assertThat(checked).isEqualTo(4);
        }
    }
}
