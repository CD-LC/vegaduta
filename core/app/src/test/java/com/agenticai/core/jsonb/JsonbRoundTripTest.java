package com.agenticai.core.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.format.FormatMapper;
import org.hibernate.type.spi.TypeConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * P0 characterisation (ADR-0012, section 6 P0.2, unit level): every entity attribute mapped with
 * {@code @JdbcTypeCode(SqlTypes.JSON)} reads and writes JSON through Hibernate's {@link
 * FormatMapper}. Boot 3.4 / Hibernate 6.6 use {@code JacksonJsonFormatMapper} (Jackson 2).
 * Hibernate 7 with Jackson 3 may auto-detect a different mapper, so P2 pins the mapper explicitly;
 * these tests define what "unchanged" means.
 *
 * <p>For each of the JSON-mapped attributes a fixture JSON document, shaped like rows the CURRENT
 * code writes, is read via the entity mapping and written back; the result must be unchanged. The
 * Postgres side (jsonb normalisation, column types, real Hibernate binding) is covered by {@code
 * JsonbRoundTripIT} (Testcontainers, not part of the default build).
 *
 * <p>Findings pinned here on purpose: a {@code String} attribute is passed through VERBATIM
 * (Hibernate does not quote or re-serialise it), a {@code Map}/{@code JsonNode} attribute is
 * re-serialised (so number formats and key order follow the mapper), and a decimal like {@code
 * 1.10} becomes {@code 1.1}.
 */
class JsonbRoundTripTest {

    /**
     * Class#field -> fixture document. The keys are the complete set of JSON-mapped attributes; the
     * scan test below fails when an entity adds one, so a new JSON column cannot skip this suite.
     */
    private static final Map<String, String> STRING_FIXTURES =
            new TreeMap<>(
                    Map.of(
                            "com.agenticai.core.agents.learning.LearningProposal#evidence",
                            "{\"source\":\"assistant-turn\",\"refs\":[{\"kind\":\"session\",\"id\":\"s1\"}],\"note\":null}",
                            "com.agenticai.core.agents.learning.LearningProposal#payload",
                            "{\"kind\":\"skill\",\"name\":\"summarize\",\"body\":\"Summarize the thread.\",\"unicode\":\"café ₹\"}",
                            "com.agenticai.core.agents.skills.Skill#pendingFilesJson",
                            "[{\"path\":\"scripts/run.py\",\"bytes\":120,\"sha256\":\"ab12\"}]",
                            "com.agenticai.core.brief.BriefTrustEventEntity#evidenceJson",
                            "{\"source\":\"eval\",\"score\":0.82,\"items\":[\"a\",\"b\"]}",
                            "com.agenticai.core.brief.BriefVersionEntity#manifestJson",
                            "{\"schema\":\"brief.v1\",\"name\":\"x\",\"tools\":[],\"limits\":{\"maxSteps\":25}}",
                            "com.agenticai.core.guardrails.procedures.ProcedureRule#guardConfig",
                            "{\"maxAmountPaise\":50000,\"requireApproval\":true,\"note\":null}",
                            "com.agenticai.core.knowledge.connector.KnowledgeConnector#configJson",
                            "{\"repo\":\"org/repo\",\"branch\":\"main\",\"paths\":[\"docs/\"],\"pollMinutes\":15}",
                            "com.agenticai.core.providers.capability.CapabilityPolicy#overrides",
                            "{\"costRouting\":false}",
                            "com.agenticai.core.providers.capability.ProviderCredential#extraConfig",
                            "{\"voiceId\":\"abc\",\"region\":\"ap-south-1\"}",
                            "com.agenticai.core.workflow.humaninput.WorkflowInputRequest#formSchemaJson",
                            "{\"fields\":[{\"name\":\"email\",\"type\":\"string\",\"required\":true}]}"));

    static {
        STRING_FIXTURES.put(
                "com.agenticai.core.agents.skills.SkillVersion#filesJson",
                "{\"scripts/run.py\":\"print('hi')\",\"notes.md\":\"caf\u00e9 \u20b9\"}");
        STRING_FIXTURES.put(
                "com.agenticai.core.tutor.StudyPlan#curriculumJson",
                "{\"modules\":[{\"id\":\"m1\",\"title\":\"Basics\",\"lessons\":[{\"id\":\"l1\",\"title\":\"Intro\",\"objective\":\"x\"}]}]}");
        STRING_FIXTURES.put(
                "com.agenticai.core.tutor.StudyPlan#reviewJson",
                "{\"cards\":[{\"id\":\"c1\",\"lessonId\":\"l1\",\"question\":\"q\",\"options\":[\"a\",\"b\"],\"correctIndex\":1}]}");
        STRING_FIXTURES.put(
                "com.agenticai.core.workflow.humaninput.WorkflowInputRequest#submittedValuesJson",
                "{\"email\":\"a@b.c\",\"age\":42,\"agree\":true,\"unicode\":\"café ₹\"}");
        STRING_FIXTURES.put(
                "com.agenticai.core.workflow.voicemenu.WorkflowVoiceMenuRequest#branchesJson",
                "[{\"digit\":\"1\",\"label\":\"Sales\"},{\"digit\":\"2\",\"label\":\"Support\"}]");
    }

    private static final String MAP_FIELD = "com.agenticai.core.omnichannel.crm.LeadActivity#meta";

    /**
     * Map&lt;String, Object&gt; attributes besides {@link #MAP_FIELD}, with a fixture shaped like
     * what the code writes.
     */
    private static final Map<String, String> MAP_FIXTURES =
            Map.of(
                    "com.agenticai.core.tables.DataTableRow#data",
                    "{\"email\":\"a@b.c\",\"score\":42,\"active\":true,\"note\":null,\"city\":\"Pune ₹\"}");

    /**
     * List&lt;Map&lt;String, Object&gt;&gt; attributes, with a fixture shaped like what the code
     * writes.
     */
    private static final Map<String, String> LIST_FIXTURES =
            Map.of(
                    "com.agenticai.core.tables.DataTable#columns",
                    "[{\"name\":\"email\",\"type\":\"text\"},{\"name\":\"score\",\"type\":\"number\"}]",
                    "com.agenticai.core.agents.chatport.ImportedChat#messages",
                    "[{\"role\":\"user\",\"content\":\"hi\",\"ts\":\"2026-10-01T10:00:00Z\"},{\"role\":\"assistant\",\"content\":\"hello\",\"ts\":null}]");

    private static final String NODE_FIELD =
            "com.agenticai.core.common.security.ChallengeNonce#metadata";

    private final FormatMapper hibernateJson =
            com.agenticai.core.tenancy.HibernateJsonFormatMapperCustomizer.formatMapper();
    private final TypeConfiguration types = new TypeConfiguration();

    @SuppressWarnings("unchecked")
    private <T> JavaType<T> javaType(Class<T> type) {
        return (JavaType<T>) types.getJavaTypeRegistry().resolveDescriptor(type);
    }

    // ---------------------------------------------------------------- scan

    @Test
    void theSetOfJsonMappedEntityAttributesIsExactlyTheFixtureSet() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<String> found = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.agenticai.core")) {
            Class<?> entity = Class.forName(definition.getBeanClassName());
            for (Field field : entity.getDeclaredFields()) {
                JdbcTypeCode code = field.getAnnotation(JdbcTypeCode.class);
                if (code != null && code.value() == SqlTypes.JSON) {
                    found.add(entity.getName() + "#" + field.getName());
                }
            }
        }

        Set<String> expected = new TreeSet<>(STRING_FIXTURES.keySet());
        expected.add(MAP_FIELD);
        expected.add(NODE_FIELD);
        expected.addAll(MAP_FIXTURES.keySet());
        expected.addAll(LIST_FIXTURES.keySet());
        assertThat(found)
                .as(
                        "A new @JdbcTypeCode(SqlTypes.JSON) attribute needs a fixture here and a case in JsonbRoundTripIT")
                .isEqualTo(expected);
        assertThat(found).hasSize(20);
    }

    @Test
    void theDeclaredJavaTypeOfEachAttributeMatchesWhatTheFixtureAssumes() throws Exception {
        for (String key : STRING_FIXTURES.keySet()) {
            assertThat(field(key).getType()).as(key).isEqualTo(String.class);
        }
        assertThat(field(MAP_FIELD).getType()).isEqualTo(Map.class);
        for (String key : MAP_FIXTURES.keySet()) {
            assertThat(field(key).getType()).as(key).isEqualTo(Map.class);
        }
        for (String key : LIST_FIXTURES.keySet()) {
            assertThat(field(key).getType()).as(key).isEqualTo(java.util.List.class);
        }
        assertThat(field(NODE_FIELD).getType()).isEqualTo(JsonNode.class);
    }

    private static Field field(String key) throws Exception {
        String[] parts = key.split("#");
        return Class.forName(parts[0]).getDeclaredField(parts[1]);
    }

    // ------------------------------------------------------ String attributes

    @Test
    void stringAttributesAreReadAndWrittenVerbatim() {
        JavaType<String> string = javaType(String.class);
        STRING_FIXTURES.forEach(
                (key, json) -> {
                    String read = hibernateJson.fromString(json, string, null);
                    String written = hibernateJson.toString(read, string, null);

                    assertThat(read).as("read " + key).isEqualTo(json);
                    assertThat(written).as("write-back " + key).isEqualTo(json);
                });
    }

    @Test
    void stringAttributeWithNonCanonicalWhitespaceIsNotReformattedByHibernate() {
        // Postgres jsonb normalises whitespace on the way in ("{"a": 1}"); Hibernate itself must
        // not.
        String json = "{ \"a\" :   1,\n \"b\":[ 1 , 2 ] }";
        JavaType<String> string = javaType(String.class);

        assertThat(
                        hibernateJson.toString(
                                hibernateJson.fromString(json, string, null), string, null))
                .isEqualTo(json);
    }

    // --------------------------------------------------- Map / JsonNode attributes

    @Test
    @SuppressWarnings("unchecked")
    void mapAttributeRoundTripsPreservingKeyOrderAndNesting() throws Exception {
        String json =
                "{\"channel\":\"whatsapp\",\"score\":3,\"tags\":[\"hot\",\"new\"],\"nested\":{\"k\":\"v\",\"n\":null},\"z\":1,\"a\":2}";
        JavaType<Map> map = javaType(Map.class);

        Map<String, Object> read = hibernateJson.fromString(json, map, null);
        String written = hibernateJson.toString(read, map, null);

        assertThat(read.keySet()).containsExactly("channel", "score", "tags", "nested", "z", "a");
        assertThat(written).isEqualTo(json);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapFixturesRoundTripVerbatim() {
        JavaType<Map> map = javaType(Map.class);
        MAP_FIXTURES.forEach(
                (key, json) -> {
                    Map<String, Object> read = hibernateJson.fromString(json, map, null);
                    assertThat(hibernateJson.toString(read, map, null)).as(key).isEqualTo(json);
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void listOfMapAttributesRoundTripPreservingOrderKeysAndNulls() {
        JavaType<java.util.List> list = javaType(java.util.List.class);
        LIST_FIXTURES.forEach(
                (key, json) -> {
                    java.util.List<Map<String, Object>> read =
                            hibernateJson.fromString(json, list, null);
                    assertThat(read)
                            .as(key)
                            .allSatisfy(item -> assertThat(item).isInstanceOf(Map.class));
                    assertThat(hibernateJson.toString(read, list, null)).as(key).isEqualTo(json);
                });
    }

    @Test
    void jsonNodeAttributeRoundTripsPreservingKeyOrderAndNesting() {
        String json =
                "{\"reason\":\"velocity\",\"retry_count\":2,\"ips\":[\"1.1.1.1\"],\"z\":null,\"a\":true}";
        JavaType<JsonNode> node = javaType(JsonNode.class);

        JsonNode read = hibernateJson.fromString(json, node, null);
        String written = hibernateJson.toString(read, node, null);

        assertThat(read.path("retry_count").isInt()).isTrue();
        assertThat(written).isEqualTo(json);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reSerialisedAttributesNormaliseDecimalsAndBigIntegersInTheWayTodaysMapperDoes() {
        // Pinned so a Jackson 3 default (e.g. BigDecimal handling) or a new Hibernate mapper is
        // visible.
        JavaType<Map> map = javaType(Map.class);
        JavaType<JsonNode> node = javaType(JsonNode.class);
        String json = "{\"price\":1.10,\"big\":12345678901234567890,\"exp\":1E+3,\"whole\":3.0}";

        Map<String, Object> asMap = hibernateJson.fromString(json, map, null);
        JsonNode asNode = hibernateJson.fromString(json, node, null);

        assertThat(hibernateJson.toString(asMap, map, null))
                .isEqualTo(
                        "{\"price\":1.1,\"big\":12345678901234567890,\"exp\":1000.0,\"whole\":3.0}");
        assertThat(hibernateJson.toString(asNode, node, null))
                .isEqualTo(
                        "{\"price\":1.1,\"big\":12345678901234567890,\"exp\":1000.0,\"whole\":3.0}");
    }

    // ---------------------------------- app mapper vs plain mapper (writers)

    @Test
    void theHibernateDefaultMapperAndAPlainObjectMapperWriteTheSameTextForTypicalDocuments()
            throws Exception {
        // 179 sites build JSON for these columns with a bare
        // `com.agenticai.core.json.JsonMappers.plain()` (ADR section 10); the
        // JsonMappers refactor in P1 must not change what they write.
        ObjectMapper plain = com.agenticai.core.json.JsonMappers.plain();
        Map<String, Object> doc = new java.util.LinkedHashMap<>();
        doc.put("b", 1);
        doc.put("a", java.util.List.of("x", "y"));
        doc.put("c", null);
        doc.put("d", new java.math.BigDecimal("2.50"));
        JavaType<Map> map = javaType(Map.class);

        assertThat(hibernateJson.toString(doc, map, null)).isEqualTo(plain.writeValueAsString(doc));
    }
}
