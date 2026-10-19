package com.agenticai.core.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.billing.gateway.BillingWebhookEventLedger;
import com.agenticai.core.knowledge.KnowledgeIngestionService;
import com.agenticai.core.knowledge.KnowledgeMetadataFilter;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * P0 characterisation (ADR-0012, section 6 P0.2): the places that write JSON into a {@code jsonb}
 * column through {@code JdbcTemplate} and a {@code ?::jsonb} cast, i.e. OUTSIDE Hibernate's
 * FormatMapper. They build the bind value with a plain Jackson mapper (or pass a raw string through),
 * so a Jackson 3 migration or the P1 {@code JsonMappers} factory can change the exact text without
 * any Hibernate test noticing. The DB side of the same writers is in {@code JsonbRoundTripIT}.
 */
class JsonbJdbcWritersTest {

    @Test
    void billingWebhookLedgerBindsTheRawBodyVerbatimIntoTheJsonbCast() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        // Deliberately awkward: insignificant whitespace, a duplicate key, unicode and an escaped quote.
        String rawBody = "{ \"event\" : \"payment.captured\",\n \"amount\":100.50, \"amount\":1, \"note\":\"café \\\"x\\\"\" }";

        BillingWebhookEventLedger.Claim claim =
            new BillingWebhookEventLedger(jdbc).claim("razorpay", "tenant-state", "evt_1", "payment.captured", rawBody);

        assertThat(claim).isEqualTo(BillingWebhookEventLedger.Claim.NEW);
        Invocation insert = Mockito.mockingDetails(jdbc).getInvocations().stream()
            .filter(i -> i.getMethod().getName().equals("update")).findFirst().orElseThrow();
        List<Object> args = List.of(insert.getArguments());
        assertThat((String) args.get(0)).contains("INSERT INTO control_plane.billing_webhook_event").contains("?::jsonb");
        assertThat(args.get(args.size() - 1)).as("payload column bind value").isEqualTo(rawBody);
        assertThat(args.get(2)).isEqualTo("razorpay");
        assertThat(args.get(3)).isEqualTo("tenant-state");
        assertThat(args.get(4)).isEqualTo("evt_1");
        assertThat(args.get(5)).isEqualTo("payment.captured");
    }

    @Test
    void knowledgeMetadataFilterBuildsAContainmentDocumentWithNestedDottedKeysInInsertionOrder() {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("source_type", "pdf");
        filter.put("author.team", "legal");
        filter.put("author.level", 3);
        filter.put("published", true);
        filter.put("score", 1.10);

        KnowledgeMetadataFilter.SqlPredicate predicate = KnowledgeMetadataFilter.toSql(filter, "c.metadata");

        assertThat(predicate.sql()).isEqualTo(" AND c.metadata @> ?::jsonb");
        assertThat(predicate.jsonParam())
            .isEqualTo("{\"source_type\":\"pdf\",\"author\":{\"team\":\"legal\",\"level\":3},\"published\":true,\"score\":1.1}");
    }

    @Test
    void knowledgeMetadataFilterIsTypeSensitiveForStringVersusNumber() {
        String stringFive = KnowledgeMetadataFilter.toSql(Map.of("n", "5"), "metadata").jsonParam();
        String numberFive = KnowledgeMetadataFilter.toSql(Map.of("n", 5), "metadata").jsonParam();

        assertThat(stringFive).isEqualTo("{\"n\":\"5\"}");
        assertThat(numberFive).isEqualTo("{\"n\":5}");
    }

    @Test
    void knowledgeIngestionWritesTheSourceTypeMetadataDocument() throws Exception {
        Method toMetadataJson = KnowledgeIngestionService.class.getDeclaredMethod("toMetadataJson", String.class);
        toMetadataJson.setAccessible(true);

        assertThat(toMetadataJson.invoke(null, "pdf")).isEqualTo("{\"source_type\":\"pdf\"}");
        assertThat(toMetadataJson.invoke(null, "café \"q\"")).isEqualTo("{\"source_type\":\"café \\\"q\\\"\"}");
    }
}
