package com.agenticai.core.common;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.agents.budget.PerUserBudgetExhaustedException;
import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.vision.VisionNotAllowedForPlanException;
import com.agenticai.core.approval.ApprovalRequestAlreadyDecidedException;
import com.agenticai.core.approval.ApprovalStatus;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.gateway.LlmGatewayLimitExceededException;
import com.agenticai.core.security.RequestCorrelationFilter;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.workflow.WorkflowValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Wire contract of every error body the ordered @RestControllerAdvice chain (see
 * FallbackExceptionHandler) writes: RFC 9457 members are added, legacy members are unchanged, and
 * {@code app.errors.problem-details.enabled=false} restores the legacy-only body. Runs the real
 * advices through MockMvc so Jackson serialization and the Content-Type header are what a client
 * actually receives.
 */
class ErrorResponseContractTest {

    private static final List<String> LEGACY_BASE = List.of("timestamp", "status", "error");
    private static final List<String> RFC_MEMBERS =
            List.of("type", "title", "status", "detail", "instance");

    @RestController
    static class ThrowingController {
        record Body(@NotBlank String name) {}

        @PostMapping("/t/validation")
        void validation(@Valid @RequestBody Body body) {}

        @GetMapping("/t/not-found")
        void notFound() {
            throw new AgentNotFoundException(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"));
        }

        @GetMapping("/t/forbidden")
        void forbidden() {
            throw new TenantLimitExceededException("Your plan allows up to 3 team members.");
        }

        @GetMapping("/t/forbidden-code")
        void forbiddenWithCode() {
            throw new VisionNotAllowedForPlanException("Live vision needs a paid plan.");
        }

        @GetMapping("/t/budget")
        void budget() {
            throw new PerUserBudgetExhaustedException(
                    "Monthly chat budget reached.",
                    "chat",
                    new BigDecimal("5.00"),
                    new BigDecimal("5.10"),
                    3600L);
        }

        @GetMapping("/t/rate")
        void rate() {
            throw new LlmGatewayLimitExceededException("Rate limit reached.");
        }

        @GetMapping("/t/conflict")
        void conflict() {
            throw new ApprovalRequestAlreadyDecidedException(
                    UUID.fromString("00000000-0000-0000-0000-000000000002"),
                    ApprovalStatus.APPROVED);
        }

        @GetMapping("/t/workflow")
        void workflow() {
            throw new WorkflowValidationException(
                    List.of("node a: missing input", "node b: cycle"));
        }

        @GetMapping("/t/boom")
        void boom() {
            throw new IllegalStateException("db password=hunter2 leaked in a stack");
        }
    }

    private final ObjectMapper mapper = com.agenticai.core.json.JsonMappers.plain();

    private MockMvc mvc(boolean problemDetails) {
        ErrorResponseFactory errors =
                new ErrorResponseFactory(problemDetails, ErrorResponseFactory.DEFAULT_TYPE_PREFIX);
        PlatformIssueService issues = mock(PlatformIssueService.class);
        // The whole chain, so a request resolves exactly as it does in the running app
        // (standalone MockMvc sorts the advices by their @Order like the real resolver does).
        Object[] advices = {
            new RequestBindingExceptionHandler(errors),
            new ResourceStateExceptionHandler(errors),
            new ClientRequestExceptionHandler(null, null, errors),
            new ServiceCredentialExceptionHandler(errors),
            new ExecutionPolicyExceptionHandler(errors),
            new UpstreamDependencyExceptionHandler(issues, null, null, errors),
            new FallbackExceptionHandler(issues, errors)
        };
        return MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(advices)
                // Boot orders the JSON converter ahead of the XML one; standalone
                // MockMvc does not, so pin JSON-only to mirror the real app.
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private static List<String> keys(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.propertyNames().iterator().forEachRemaining(out::add);
        return out;
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Nested
    class ProblemDetailsEnabled {

        private final MockMvc mvc = mvc(true);

        @Test
        void validation400() throws Exception {
            mvc.perform(
                            post("/t/validation")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"name\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:vegaduta:problem:validation-failed"))
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail", startsWith("name: ")))
                    .andExpect(jsonPath("$.instance").value("/t/validation"))
                    .andExpect(jsonPath("$.error", startsWith("name: ")))
                    .andExpect(jsonPath("$.timestamp").isString());
        }

        @Test
        void notFound404() throws Exception {
            MvcResult r =
                    mvc.perform(get("/t/not-found"))
                            .andExpect(status().isNotFound())
                            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                            .andExpect(
                                    jsonPath("$.type")
                                            .value("urn:vegaduta:problem:agent-not-found"))
                            .andExpect(jsonPath("$.title").value("Not Found"))
                            .andExpect(jsonPath("$.status").value(404))
                            .andExpect(
                                    jsonPath("$.error")
                                            .value(
                                                    "No agent found with id"
                                                            + " 00000000-0000-0000-0000-000000000001"))
                            .andExpect(
                                    jsonPath("$.detail")
                                            .value(
                                                    "No agent found with id"
                                                            + " 00000000-0000-0000-0000-000000000001"))
                            .andReturn();
            JsonNode b = body(r);
            assertEquals(true, keys(b).containsAll(LEGACY_BASE));
            assertEquals(true, keys(b).containsAll(RFC_MEMBERS));
            assertFalse(b.has("code"), "no code member where the legacy body had none");
        }

        @Test
        void forbidden403() throws Exception {
            mvc.perform(get("/t/forbidden"))
                    .andExpect(status().isForbidden())
                    .andExpect(
                            jsonPath("$.type").value("urn:vegaduta:problem:tenant-limit-exceeded"))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Your plan allows up to 3 team members."));
        }

        @Test
        void forbidden403KeepsLegacyCodeAndDerivesTypeFromIt() throws Exception {
            mvc.perform(get("/t/forbidden-code"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("VISION_PLAN_LOCKED"))
                    .andExpect(jsonPath("$.type").value("urn:vegaduta:problem:vision-plan-locked"));
        }

        @Test
        void planLimit402KeepsStructuredBudgetMembers() throws Exception {
            mvc.perform(get("/t/budget"))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(
                            jsonPath("$.type")
                                    .value("urn:vegaduta:problem:per-user-budget-exhausted"))
                    .andExpect(jsonPath("$.status").value(402))
                    .andExpect(jsonPath("$.error").value("Monthly chat budget reached."))
                    .andExpect(jsonPath("$.budget.modality").value("chat"))
                    .andExpect(jsonPath("$.budget.resetSeconds").value(3600))
                    .andExpect(jsonPath("$.topUp.purchaseUrl").value("/api/budget/top-up"));
        }

        @Test
        void rateLimit429() throws Exception {
            mvc.perform(get("/t/rate"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(
                            jsonPath("$.type")
                                    .value("urn:vegaduta:problem:llm-gateway-limit-exceeded"))
                    .andExpect(jsonPath("$.title").value("Too Many Requests"))
                    .andExpect(jsonPath("$.error").value("Rate limit reached."));
        }

        @Test
        void conflict409() throws Exception {
            mvc.perform(get("/t/conflict"))
                    .andExpect(status().isConflict())
                    .andExpect(
                            jsonPath("$.type")
                                    .value("urn:vegaduta:problem:approval-request-already-decided"))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.error", containsString("already APPROVED")));
        }

        @Test
        void workflowValidationKeepsErrorsArray() throws Exception {
            mvc.perform(get("/t/workflow"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0]").value("node a: missing input"))
                    .andExpect(jsonPath("$.errors[1]").value("node b: cycle"));
        }

        @Test
        void internal500IsGenericAndLeaksNothing() throws Exception {
            MvcResult r =
                    mvc.perform(get("/t/boom"))
                            .andExpect(status().isInternalServerError())
                            .andExpect(
                                    jsonPath("$.type")
                                            .value("urn:vegaduta:problem:internal-server-error"))
                            .andExpect(
                                    jsonPath("$.error")
                                            .value("Something went wrong. Please try again."))
                            .andExpect(
                                    jsonPath("$.detail")
                                            .value("Something went wrong. Please try again."))
                            .andReturn();
            String raw = r.getResponse().getContentAsString();
            assertFalse(raw.contains("hunter2"), raw);
            assertFalse(raw.contains("IllegalStateException"), raw);
            assertFalse(raw.contains("at com."), raw);
        }

        @Test
        void correlationIdsFromMdcAreIncluded() throws Exception {
            MDC.put(RequestCorrelationFilter.MDC_KEY, "req-123");
            MDC.put("traceId", "abc123");
            mvc.perform(get("/t/not-found"))
                    .andExpect(jsonPath("$.requestId").value("req-123"))
                    .andExpect(jsonPath("$.traceId").value("abc123"));
        }

        @Test
        void errorOnAnSseRequestStillGetsAJsonBody() throws Exception {
            mvc.perform(get("/t/not-found").accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.error").isString());
        }
    }

    @Nested
    class ProblemDetailsDisabled {

        private final MockMvc mvc = mvc(false);

        @Test
        void legacyBodyIsExactlyTimestampStatusError() throws Exception {
            MDC.put(RequestCorrelationFilter.MDC_KEY, "req-123");
            MvcResult r =
                    mvc.perform(get("/t/not-found"))
                            .andExpect(status().isNotFound())
                            .andExpect(
                                    header().string(
                                                    "Content-Type",
                                                    startsWith(MediaType.APPLICATION_JSON_VALUE)))
                            .andExpect(
                                    jsonPath("$.error")
                                            .value(
                                                    "No agent found with id"
                                                            + " 00000000-0000-0000-0000-000000000001"))
                            .andExpect(jsonPath("$.type").doesNotExist())
                            .andExpect(jsonPath("$.requestId").doesNotExist())
                            .andReturn();
            assertEquals(LEGACY_BASE, keys(body(r)));
        }

        @Test
        void legacyCodeBodyOrderIsUnchanged() throws Exception {
            MvcResult r =
                    mvc.perform(get("/t/forbidden-code"))
                            .andExpect(status().isForbidden())
                            .andReturn();
            assertEquals(List.of("timestamp", "status", "error", "code"), keys(body(r)));
        }

        @Test
        void legacyStructuredBodyOrderIsUnchanged() throws Exception {
            MvcResult r =
                    mvc.perform(get("/t/budget"))
                            .andExpect(status().isPaymentRequired())
                            .andReturn();
            assertEquals(List.of("timestamp", "status", "error", "budget", "topUp"), keys(body(r)));
        }

        @Test
        void legacyValidationBody() throws Exception {
            mvc.perform(
                            post("/t/validation")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"name\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.detail").doesNotExist())
                    .andExpect(jsonPath("$.instance").doesNotExist());
        }
    }

    @Test
    void slugDerivation() {
        assertEquals(
                "agent-not-found",
                ErrorResponseFactory.slugFor(
                        new AgentNotFoundException(UUID.randomUUID()),
                        org.springframework.http.HttpStatus.NOT_FOUND));
        assertEquals(
                "bad-request",
                ErrorResponseFactory.slugFor(
                        new IllegalArgumentException("x"),
                        org.springframework.http.HttpStatus.BAD_REQUEST));
        assertEquals("e2b-api", ErrorResponseFactory.kebab("E2bApi"));
        assertEquals(
                "llm-gateway-limit-exceeded",
                ErrorResponseFactory.kebab("LlmGatewayLimitExceeded"));
        assertEquals(
                "vision-approval-required",
                ErrorResponseFactory.slugForCode("VISION_APPROVAL_REQUIRED"));
    }
}
