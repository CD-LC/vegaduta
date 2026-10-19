package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.billing.gateway.BillingWebhookEventLedger;
import com.agenticai.core.knowledge.KnowledgeMetadataFilter;
import com.agenticai.core.providers.capability.CapabilityPolicy;
import com.agenticai.core.providers.capability.CapabilityPolicyRepository;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * P0 characterisation (ADR-0012, section 6 P0.2): the JSONB paths against a real Postgres.
 *
 * <p>Runs in CI's {@code -Pit} suite (core job). Run locally with {@code mvn -o -P it -pl app -am
 * verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=JsonbRoundTripIT} (see
 * docs/engineering/INTEGRATION-TESTS.md for the image switch).
 *
 * <p>Pins: (1) every {@code @JdbcTypeCode(SqlTypes.JSON)} attribute is a real {@code jsonb} column
 * in the migrated schema (a mapping that drifted to varchar is the JAVA-011 failure); (2) a
 * Hibernate write and read of a String-typed JSON attribute preserves the document (Postgres
 * normalises whitespace and key order, so the comparison is semantic) and writing the value back
 * changes nothing; (3) the JdbcTemplate {@code ?::jsonb} writers store and query what the unit
 * tests say they bind. The mapper-level half is {@code JsonbRoundTripTest}.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class JsonbRoundTripIT extends AbstractContextIT {

    private static final String SCHEMA = "tenant_it_jsonb";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private TenantRepository tenantRepository;
    @Autowired private TenantMigrationService tenantMigrationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private CapabilityPolicyRepository capabilityPolicies;

    @BeforeEach
    void tenant() {
        if (tenantRepository.findAll().stream().noneMatch(t -> SCHEMA.equals(t.getSchemaName()))) {
            Tenant tenant =
                    new Tenant(
                            SCHEMA,
                            TenantPlan.FREE,
                            SCHEMA,
                            "agentic-ai",
                            AccountType.B2B,
                            ExperienceMode.SIMPLE);
            tenant.markActive();
            tenantRepository.save(tenant);
            assertThat(tenantMigrationService.migrateAllActiveTenants()).isEmpty();
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private <T> T inTenantTx(java.util.function.Supplier<T> work) {
        TenantContext.set(TenantContext.of(SCHEMA, "it-user", Set.of()));
        try {
            return new TransactionTemplate(transactionManager).execute(status -> work.get());
        } finally {
            TenantContext.clear();
        }
    }

    // ------------------------------------------------------------- column types

    @Test
    void everyJsonMappedAttributeIsAJsonbColumnInTheMigratedSchema() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents("com.agenticai.core")) {
            Class<?> entity = Class.forName(definition.getBeanClassName());
            for (Field field : entity.getDeclaredFields()) {
                JdbcTypeCode code = field.getAnnotation(JdbcTypeCode.class);
                if (code == null || code.value() != SqlTypes.JSON) {
                    continue;
                }
                checked++;
                String table = tableName(entity);
                String column = columnName(field);
                List<String> types =
                        jdbc.queryForList(
                                "SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name = ? "
                                        + "AND table_schema IN (?, 'control_plane', 'public')",
                                String.class,
                                table,
                                column,
                                SCHEMA);
                if (types.isEmpty() || !types.stream().allMatch("jsonb"::equals)) {
                    problems.add(
                            entity.getSimpleName()
                                    + "#"
                                    + field.getName()
                                    + " -> "
                                    + table
                                    + "."
                                    + column
                                    + " types="
                                    + types);
                }
            }
        }
        // A floor, not an exact count: the jsonb check below already covers every attribute the
        // scan finds, new ones included. The floor only catches a scan that silently finds too few.
        assertThat(checked).as("JSON-mapped attributes found").isGreaterThanOrEqualTo(17);
        assertThat(problems).as("JSON attributes whose column is not jsonb").isEmpty();
    }

    /**
     * Spring's default physical naming: CamelCase -> snake_case, unless {@code @Table(name)} says
     * otherwise.
     */
    private static String tableName(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name();
        }
        Entity e = entity.getAnnotation(Entity.class);
        String logical = e != null && !e.name().isBlank() ? e.name() : entity.getSimpleName();
        return snake(logical);
    }

    private static String columnName(Field field) {
        Column column = field.getAnnotation(Column.class);
        return column != null && !column.name().isBlank() ? column.name() : snake(field.getName());
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------- Hibernate entity round trip

    @Test
    void stringJsonAttributeRoundTripsThroughHibernateAndJsonbSemantically() throws Exception {
        String written =
                "{\"costRouting\":false,\"limits\":{\"maxSteps\":25,\"tags\":[\"a\",\"b\"]},\"note\":null}";
        String capability = "it-cap-" + UUID.randomUUID().toString().substring(0, 8);

        UUID id =
                inTenantTx(
                        () -> {
                            CapabilityPolicy saved =
                                    capabilityPolicies.saveAndFlush(
                                            new CapabilityPolicy(capability, written));
                            return saved.getId();
                        });

        String stored =
                inTenantTx(
                        () -> {
                            entityManager.clear();
                            return capabilityPolicies.findById(id).orElseThrow().getOverrides();
                        });
        assertThat(JSON.readTree(stored))
                .as("document survives the jsonb column")
                .isEqualTo(JSON.readTree(written));

        // Write the read value back unchanged: the stored document must not drift.
        inTenantTx(
                () -> {
                    CapabilityPolicy policy = capabilityPolicies.findById(id).orElseThrow();
                    policy.apply(stored);
                    capabilityPolicies.saveAndFlush(policy);
                    return null;
                });
        String afterRewrite =
                inTenantTx(
                        () -> {
                            entityManager.clear();
                            return capabilityPolicies.findById(id).orElseThrow().getOverrides();
                        });
        assertThat(JSON.readTree(afterRewrite)).isEqualTo(JSON.readTree(written));
        assertThat(afterRewrite)
                .as("second read is text-identical to the first (jsonb output is canonical)")
                .isEqualTo(stored);
    }

    // ----------------------------------------------------- JdbcTemplate writers

    @Test
    void billingLedgerRawBodyIsStoredAsJsonbWithLastDuplicateKeyWinning() throws Exception {
        String eventId = "evt_it_" + UUID.randomUUID();
        String rawBody = "{ \"event\" : \"payment.captured\", \"amount\":100.50, \"amount\":1 }";

        BillingWebhookEventLedger.Claim first =
                new BillingWebhookEventLedger(jdbc)
                        .claim("razorpay", "it-jsonb", eventId, "payment.captured", rawBody);
        BillingWebhookEventLedger.Claim second =
                new BillingWebhookEventLedger(jdbc)
                        .claim("razorpay", "it-jsonb", eventId, "payment.captured", rawBody);

        assertThat(first).isEqualTo(BillingWebhookEventLedger.Claim.NEW);
        assertThat(second).isEqualTo(BillingWebhookEventLedger.Claim.DUPLICATE);
        String stored =
                jdbc.queryForObject(
                        "SELECT payload::text FROM control_plane.billing_webhook_event WHERE consumer = 'it-jsonb' "
                                + "AND gateway_event_id = ?",
                        String.class,
                        eventId);
        assertThat(JSON.readTree(stored))
                .isEqualTo(JSON.readTree("{\"event\":\"payment.captured\",\"amount\":1}"));
    }

    @Test
    void metadataContainmentParameterMatchesTheDocumentTheIngestionWriterStores() {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS "
                        + SCHEMA
                        + ".json_probe (id uuid PRIMARY KEY, metadata jsonb NOT NULL)");
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO " + SCHEMA + ".json_probe (id, metadata) VALUES (?, ?::jsonb)",
                id,
                "{\"source_type\":\"pdf\",\"author\":{\"team\":\"legal\",\"level\":3},\"published\":true}");

        KnowledgeMetadataFilter.SqlPredicate hit =
                KnowledgeMetadataFilter.toSql(
                        Map.of("author.team", "legal", "published", true), "metadata");
        KnowledgeMetadataFilter.SqlPredicate typeMiss =
                KnowledgeMetadataFilter.toSql(Map.of("author.level", "3"), "metadata");
        KnowledgeMetadataFilter.SqlPredicate valueMiss =
                KnowledgeMetadataFilter.toSql(Map.of("source_type", "docx"), "metadata");

        assertThat(matches(id, hit)).as("nested dotted key + boolean").isTrue();
        assertThat(matches(id, typeMiss))
                .as("jsonb equality is type sensitive: \"3\" != 3")
                .isFalse();
        assertThat(matches(id, valueMiss)).isFalse();
    }

    private boolean matches(UUID id, KnowledgeMetadataFilter.SqlPredicate predicate) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM "
                                + SCHEMA
                                + ".json_probe WHERE id = ?"
                                + predicate.sql(),
                        Integer.class,
                        id,
                        predicate.jsonParam());
        return count != null && count == 1;
    }
}
