package com.agenticai.core.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Collection;
import org.hibernate.mapping.PersistentClass;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the cross-schema bug tracked in
 * docs/AGENT-COORDINATION.md's carried-forward backlog: {@link
 * AgentQualityRecord} previously had no {@code schema} on its {@code @Table}
 * (or on its two {@code @ElementCollection} maps' {@code @CollectionTable}s),
 * so Hibernate emitted unqualified SQL that resolved against whatever schema
 * happened to be on the connection's {@code search_path} - the ACTIVE
 * TENANT's schema for every real request, not the {@code control_plane}
 * schema this genuinely control-plane table (V152/V186, both in
 * {@code db/migration/controlplane/}) actually lives in. See the entity's
 * class javadoc and V218's migration header for the full diagnosis.
 *
 * <p>This builds Hibernate {@link Metadata} directly from the annotated
 * class - no JDBC connection, no {@code TenantContext}, no application
 * context - and asserts the schema Hibernate baked into the mapping itself.
 * That is deliberate: the whole point of the fix is that the generated SQL
 * is schema-qualified UNCONDITIONALLY, so it is correct for every tenant's
 * connection (whatever schema is on that connection's {@code search_path})
 * without ever depending on which one happens to be active - a live-DB test
 * that flips {@code TenantContext}/{@code search_path} per case would only
 * be proving Postgres resolves qualified identifiers correctly, not
 * anything specific to this entity's mapping. Building metadata needs no
 * live database (only {@link Metadata#buildSessionFactory()} or schema
 * export would), so this runs with the same zero-manual-setup posture as
 * every other unit test in this module - no live Postgres required, unlike
 * this repo's {@code core/app} integration tests.
 */
class AgentQualityRecordSchemaMappingTest {

    private static final String EXPECTED_SCHEMA = "control_plane";

    private static StandardServiceRegistry registry;
    private static Metadata metadata;

    @BeforeAll
    static void buildMetadata() {
        // Pin the dialect explicitly so Hibernate never tries to open a real
        // JDBC connection to auto-detect one - metadata-building stays
        // connection-free.
        registry = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
            .build();
        metadata = new MetadataSources(registry)
            .addAnnotatedClass(AgentQualityRecord.class)
            .buildMetadata();
    }

    @AfterAll
    static void tearDown() {
        if (registry != null) {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    @Test
    void primaryTableIsQualifiedWithControlPlaneSchema() {
        PersistentClass entityBinding = metadata.getEntityBinding(AgentQualityRecord.class.getName());

        assertEquals(EXPECTED_SCHEMA, entityBinding.getTable().getSchema(),
            "agent_quality_record must always resolve against control_plane, "
                + "regardless of whichever tenant schema is active on the "
                + "querying connection's search_path");
        assertEquals("agent_quality_record", entityBinding.getTable().getName());
    }

    @Test
    void scoresElementCollectionTableIsQualifiedWithControlPlaneSchema() {
        Collection scoresCollection = metadata.getCollectionBinding(
            AgentQualityRecord.class.getName() + ".scores");

        assertEquals(EXPECTED_SCHEMA, scoresCollection.getCollectionTable().getSchema(),
            "agent_quality_record_scores must stay in control_plane too - it "
                + "carries a FK back to the (now schema-qualified) parent row");
        assertEquals("agent_quality_record_scores", scoresCollection.getCollectionTable().getName());
    }

    @Test
    void feedbackElementCollectionTableIsQualifiedWithControlPlaneSchema() {
        Collection feedbackCollection = metadata.getCollectionBinding(
            AgentQualityRecord.class.getName() + ".feedback");

        assertEquals(EXPECTED_SCHEMA, feedbackCollection.getCollectionTable().getSchema(),
            "agent_quality_record_feedback must stay in control_plane too - it "
                + "carries a FK back to the (now schema-qualified) parent row");
        assertEquals("agent_quality_record_feedback", feedbackCollection.getCollectionTable().getName());
    }
}
