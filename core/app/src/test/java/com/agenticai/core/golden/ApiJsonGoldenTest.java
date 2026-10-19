package com.agenticai.core.golden;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.chat.SpendReport;
import com.agenticai.core.common.ErrorResponseFactory;
import com.agenticai.core.common.datausage.TenantDataUsageResponse;
import com.agenticai.core.payments.PaymentDtos;
import com.agenticai.core.tenancy.dto.TenantSettingsResponse;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * P0 characterisation (ADR-0012, section 6 P0.1): the JSON the API emits, frozen as golden files
 * under {@code src/test/resources/golden/}.
 *
 * <p>The mapper is NOT hand-configured: it is the {@code ObjectMapper} Spring Boot builds for the app
 * ({@code JacksonAutoConfiguration}) after loading the app's own {@code application.yml}, i.e. the
 * exact date, null and property-order settings the controllers use. A Boot 4 / Jackson 3 upgrade
 * changes the defaults (dates, property ordering, {@code Optional}/Jdk8 handling, {@code
 * PageImpl} shape); any such change surfaces here as a readable diff instead of as a client-side
 * regression.
 *
 * <p>What each golden pins: {@code error-*} the RFC 9457 + legacy error bodies; {@code agent-*},
 * {@code chat-turn}, {@code usage-*}, {@code workflow-run-*}, {@code tenant-*}, {@code mandate},
 * {@code transaction} the DTOs named in the ADR (populated and "sparse" = nulls / empty collections);
 * {@code page-of-transactions} the paginated list envelope; {@code mapper-probe} the primitive
 * behaviours (Instant nanos, LocalDate, Duration, BigDecimal scale, Optional, enums, byte[], map
 * order, null handling) independent of any DTO.
 *
 * <p>Regenerate with {@code -Dgolden.update=true}; see docs/engineering/CHARACTERISATION-TESTS.md.
 */
class ApiJsonGoldenTest {

    private static ObjectMapper mapper;

    /** Only the error body's {@code timestamp} is non-deterministic (it is {@code Instant.now()}). */
    private static final UnaryOperator<String> MASK_NOW =
        json -> json.replaceAll("\"timestamp\" : \"[^\"]*\"", "\"timestamp\" : \"<now>\"");

    @BeforeAll
    static void loadTheAppsMapper() {
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .run(context -> mapper = context.getBean(ObjectMapper.class));
        assertThat(mapper).as("JacksonAutoConfiguration must provide the app mapper").isNotNull();
    }

    @AfterEach
    void clean() {
        MDC.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    // -------------------------------------------------------------- errors

    @Test
    void errorBodyProblemDetailsWithExtensionsNullsAndNestedValues() {
        ErrorResponseFactory factory = new ErrorResponseFactory(true, ErrorResponseFactory.DEFAULT_TYPE_PREFIX);
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("code", "VISION_PLAN_LOCKED");
        extensions.put("upstreamStatus", null);
        extensions.put("budget", new BigDecimal("12.50"));
        Map<String, Object> fieldError = new LinkedHashMap<>();
        fieldError.put("field", "name");
        fieldError.put("message", "must not be blank");
        extensions.put("errors", List.of(fieldError));
        extensions.put("retryable", false);
        RequestContextHolder.setRequestAttributes(
            new ServletRequestAttributes(new MockHttpServletRequest("POST", "/api/agents/abc")));
        MDC.put("requestId", "req-123");
        MDC.put("traceId", "trace-456");

        Map<String, Object> body = factory.body(HttpStatus.FORBIDDEN, "Live vision needs a paid plan.", "vision-plan-locked", extensions);

        GoldenJson.assertMatches("error-problem-details", mapper, body, MASK_NOW);
    }

    @Test
    void errorBodyNotFoundWithoutRequestContext() {
        ErrorResponseFactory factory = new ErrorResponseFactory(true, ErrorResponseFactory.DEFAULT_TYPE_PREFIX);

        Map<String, Object> body = factory.body(HttpStatus.NOT_FOUND, "Agent not found: " + RecordFixtures.UUID_1, null, null);

        GoldenJson.assertMatches("error-not-found", mapper, body, MASK_NOW);
    }

    @Test
    void errorBodyLegacyShapeWhenProblemDetailsAreDisabled() {
        ErrorResponseFactory factory = new ErrorResponseFactory(false, ErrorResponseFactory.DEFAULT_TYPE_PREFIX);

        Map<String, Object> body = factory.body(HttpStatus.BAD_REQUEST, "name must not be blank", "validation", new LinkedHashMap<>(Map.of("code", "X")));

        GoldenJson.assertMatches("error-legacy", mapper, body, MASK_NOW);
    }

    // ---------------------------------------------------------- agent / chat

    @Test
    void agentResponsePopulated() {
        GoldenJson.assertMatches("agent-populated", mapper, RecordFixtures.populated(AgentResponse.class));
    }

    @Test
    void agentResponseSparseNullsAndEmptyCollections() {
        GoldenJson.assertMatches("agent-sparse", mapper, RecordFixtures.sparse(AgentResponse.class));
    }

    @Test
    void chatTurnList() {
        List<ChatTurn> turns = List.of(
            new ChatTurn(com.agenticai.core.agents.memory.MessageRole.USER, "What is my balance?", RecordFixtures.INSTANT),
            new ChatTurn(com.agenticai.core.agents.memory.MessageRole.ASSISTANT, "Your balance is ₹1,234.50 \"today\".\nBye",
                RecordFixtures.INSTANT_WHOLE_SECOND));
        GoldenJson.assertMatches("chat-turns", mapper, turns);
    }

    @Test
    void usageSpendReportWithBigDecimalNullableDoublesAndLocalDates() {
        SpendReport.UsageMetrics metrics = new SpendReport.UsageMetrics(
            12, 1, 0.9166666666666666, 4500, 1200, 5700, 24, 3, new BigDecimal("0.004200"), 812.5, null, 1500.0, null);
        SpendReport.Summary summary = new SpendReport.Summary(
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), true, metrics);
        List<SpendReport.DayUsage> days = List.of(new SpendReport.DayUsage(LocalDate.of(2026, 9, 30), metrics));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("summary", summary);
        envelope.put("days", days);
        envelope.put("byModel", List.of(new SpendReport.ModelUsage("claude-sonnet-4-5", "text", metrics)));
        GoldenJson.assertMatches("usage-spend-report", mapper, envelope);
    }

    // ------------------------------------------------------------- workflow

    @Test
    void workflowRunResponsePopulated() {
        GoldenJson.assertMatches("workflow-run-populated", mapper, RecordFixtures.populated(WorkflowRunResponse.class));
    }

    @Test
    void workflowRunResponseSparse() {
        GoldenJson.assertMatches("workflow-run-sparse", mapper, RecordFixtures.sparse(WorkflowRunResponse.class));
    }

    // --------------------------------------------------------------- tenant

    @Test
    void tenantSettingsPopulated() {
        GoldenJson.assertMatches("tenant-settings-populated", mapper, RecordFixtures.populated(TenantSettingsResponse.class));
    }

    @Test
    void tenantSettingsSparse() {
        GoldenJson.assertMatches("tenant-settings-sparse", mapper, RecordFixtures.sparse(TenantSettingsResponse.class));
    }

    @Test
    void tenantDataUsageWithMixedValueTypes() {
        TenantDataUsageResponse response = new TenantDataUsageResponse(
            RecordFixtures.INSTANT_WHOLE_SECOND, "11111111-1111-1111-1111-111111111111",
            List.of(
                new TenantDataUsageResponse.SystemUsage("postgres", "Postgres", "PostgreSQL", "primary store",
                    "schema per tenant", "isolated by schema", new TenantDataUsageResponse.LiveStat("rows", 1234L), "active"),
                new TenantDataUsageResponse.SystemUsage("temporal", "Temporal", "Temporal", "workflows",
                    "durable execution", "namespace", null, "available_not_engaged"),
                new TenantDataUsageResponse.SystemUsage("redis", "Redis", "Valkey", "cache",
                    "ephemeral", "key prefix", new TenantDataUsageResponse.LiveStat("hit ratio", 0.93), "active")));
        GoldenJson.assertMatches("tenant-data-usage", mapper, response);
    }

    // -------------------------------------------------------------- payments

    @Test
    void mandateResponsePopulated() {
        GoldenJson.assertMatches("mandate-populated", mapper, RecordFixtures.populated(PaymentDtos.MandateResponse.class));
    }

    @Test
    void mandateResponseSparse() {
        GoldenJson.assertMatches("mandate-sparse", mapper, RecordFixtures.sparse(PaymentDtos.MandateResponse.class));
    }

    @Test
    void transactionResponsePopulated() {
        GoldenJson.assertMatches("transaction-populated", mapper, RecordFixtures.populated(PaymentDtos.TransactionResponse.class));
    }

    // ------------------------------------------------------------ pagination

    @Test
    void springDataPageEnvelope() {
        List<PaymentDtos.TransactionResponse> content = new ArrayList<>(RecordFixtures.listOf(PaymentDtos.TransactionResponse.class));
        PageImpl<PaymentDtos.TransactionResponse> page = new PageImpl<>(content, PageRequest.of(1, 2), 5);
        // PageImpl is a bean, not a record: its getter order is reflection order and varies per JVM run,
        // so keys are sorted before comparing (the key set and every value are still pinned).
        GoldenJson.assertMatchesSortedKeys("page-of-transactions", mapper, page);
    }

    // ----------------------------------------------------- mapper primitives

    /** Every value here is a pure Jackson-behaviour probe: dates, numbers, Optional, enums, order, nulls. */
    record Probe(
        Instant instantNanos,
        Instant instantWholeSecond,
        LocalDate localDate,
        LocalDateTime localDateTime,
        OffsetDateTime offsetDateTime,
        ZonedDateTime zonedDateTime,
        Duration duration,
        BigDecimal bigDecimalScale,
        BigDecimal bigDecimalLarge,
        double doubleWhole,
        long longBeyondJsNumberRange,
        Optional<String> optionalPresent,
        Optional<String> optionalEmpty,
        String nullString,
        List<String> emptyList,
        Map<String, Object> emptyMap,
        Map<String, Object> unorderedMap,
        Map<String, Object> sortedMap,
        UUID uuid,
        HttpStatus enumValue,
        byte[] bytes,
        Character character,
        String unicodeAndEscapes
    ) {}

    @Test
    void mapperProbeDatesNumbersOptionalsEnumsOrderAndNulls() {
        Map<String, Object> insertion = new LinkedHashMap<>();
        insertion.put("zebra", 1);
        insertion.put("apple", 2);
        insertion.put("mango", null);
        Probe probe = new Probe(
            RecordFixtures.INSTANT, RecordFixtures.INSTANT_WHOLE_SECOND,
            LocalDate.of(2026, 9, 30), LocalDateTime.of(2026, 9, 30, 10, 15, 30, 123_000_000),
            OffsetDateTime.of(2026, 9, 30, 10, 15, 30, 0, ZoneOffset.ofHoursMinutes(5, 30)),
            ZonedDateTime.of(2026, 9, 30, 10, 15, 30, 0, java.time.ZoneId.of("Asia/Kolkata")),
            Duration.ofSeconds(90, 500_000_000),
            new BigDecimal("1234.5000"), new BigDecimal("1E+3"),
            3.0d, 9_007_199_254_740_993L,
            Optional.of("present"), Optional.empty(),
            null, List.of(), Map.of(), insertion, new TreeMap<>(insertion.entrySet().stream()
                .filter(e -> e.getValue() != null).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))),
            RecordFixtures.UUID_1, HttpStatus.NOT_FOUND, new byte[] {1, 2, 3}, 'x',
            "café ₹ \"quoted\" <tag> \\ \t tab");
        GoldenJson.assertMatches("mapper-probe", mapper, probe);
    }

    @Test
    void theGoldenHelperDiffNamesTheChangedLines() {
        String diff = GoldenJson.diff("{\n  \"a\" : 1,\n  \"b\" : 2\n}\n", "{\n  \"b\" : 2,\n  \"a\" : 1\n}\n");
        assertThat(diff).contains("+ ").contains("- ");
    }
}
