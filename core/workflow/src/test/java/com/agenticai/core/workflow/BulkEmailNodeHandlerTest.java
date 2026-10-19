package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.contact.ChannelContactRepository;
import com.agenticai.core.omnichannel.email.EmailBulkEmailLedger;
import com.agenticai.core.omnichannel.email.EmailMessage;
import com.agenticai.core.omnichannel.email.EmailMessageRepository;
import com.agenticai.core.omnichannel.email.EmailSuppression;
import com.agenticai.core.omnichannel.email.EmailSuppressionRepository;
import com.agenticai.core.omnichannel.email.EmailUnsubscribeTokenService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.output.BulkEmailSender;
import com.agenticai.core.workflow.output.WorkflowOutputDedupGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BulkEmailNodeHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private BulkEmailSender sender;
    private EmailMessageRepository messages;
    private EmailSuppressionRepository suppressions;
    private WorkflowOutputDedupGuard dedup;
    private final List<Long> sleeps = new ArrayList<>();
    private BulkEmailNodeHandler handler;
    private final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    void setUp() {
        sender = mock(BulkEmailSender.class);
        messages = mock(EmailMessageRepository.class);
        suppressions = mock(EmailSuppressionRepository.class);
        dedup = mock(WorkflowOutputDedupGuard.class);
        when(dedup.identityKey(anyString(), anyString(), anyString()))
                .thenAnswer(i -> i.getArgument(0) + ":" + i.getArgument(1));
        when(dedup.tryClaim(anyString())).thenReturn(true);
        when(suppressions.findByEmailIn(any())).thenReturn(List.of());
        when(sender.send(anyString(), anyString(), anyString(), anyBoolean(), any(), any()))
                .thenAnswer(
                        i -> new BulkEmailSender.Sent("mid-" + ids.incrementAndGet() + "@x.test"));
        handler =
                new BulkEmailNodeHandler(
                        sender,
                        new EmailBulkEmailLedger(
                                messages,
                                suppressions,
                                new EmailUnsubscribeTokenService(
                                        "secret", "https://api.example.test")),
                        mock(ChannelContactRepository.class),
                        dedup,
                        mapper,
                        sleeps::add);
        TenantContext.set(TenantContext.of("tenant-a", "tester", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private WorkflowNodeDto node(Map<String, Object> config) {
        Map<String, Object> cfg =
                new HashMap<>(
                        Map.of(
                                "subject",
                                "Hi {{name}}",
                                "body",
                                "Hello {{name}} from {{company}}"));
        cfg.putAll(config);
        return new WorkflowNodeDto(
                "n1", "bulk_email", Map.of(), Map.of("config", cfg, "label", "Bulk"));
    }

    private JsonNode json(NodeExecutionResult r) throws Exception {
        return mapper.readTree(r.output());
    }

    @Test
    void personalizesSkipsSuppressedInvalidAndDuplicateAndTracksEveryRow() throws Exception {
        when(suppressions.findByEmailIn(any()))
                .thenReturn(List.of(new EmailSuppression("gone@x.test", "UNSUBSCRIBE")));
        String input =
                "[{\"email\":\"a@x.test\",\"name\":\"Ann\",\"company\":\"Acme\"},"
                        + "{\"email\":\"Gone@x.test\",\"name\":\"G\"},{\"email\":\"not-an-email\"},{\"email\":\"A@x.test\"},"
                        + "\"plain@x.test\"]";

        NodeExecutionResult result = handler.execute("run-1", node(Map.of()), input);

        assertEquals("OK", result.status());
        JsonNode out = json(result);
        assertEquals(2, out.get("sent").asInt());
        assertEquals(3, out.get("skipped").asInt());
        assertEquals(0, out.get("failed").asInt());
        assertEquals(5, out.get("rows").size());
        assertEquals("suppressed", out.get("rows").get(1).get("reason").asText());
        assertEquals("invalid address", out.get("rows").get(2).get("reason").asText());
        assertEquals("duplicate in list", out.get("rows").get(3).get("reason").asText());

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> unsub = ArgumentCaptor.forClass(String.class);
        verify(sender)
                .send(
                        eq("a@x.test"),
                        subject.capture(),
                        body.capture(),
                        eq(false),
                        isNull(),
                        unsub.capture());
        assertEquals("Hi Ann", subject.getValue());
        assertTrue(body.getValue().startsWith("Hello Ann from Acme"));
        assertTrue(
                body.getValue()
                        .contains(
                                "Unsubscribe: https://api.example.test/api/public/email/unsubscribe?t="));
        assertTrue(
                unsub.getValue()
                        .startsWith("https://api.example.test/api/public/email/unsubscribe?t="));

        ArgumentCaptor<EmailMessage> saved = ArgumentCaptor.forClass(EmailMessage.class);
        verify(messages, org.mockito.Mockito.times(2)).save(saved.capture());
        assertEquals("SENT", saved.getAllValues().get(0).getStatus());
        assertEquals("mid-1@x.test", saved.getAllValues().get(0).getMessageId());
        assertEquals("run-1", saved.getAllValues().get(0).getRunId());
        assertEquals("n1", saved.getAllValues().get(0).getNodeId());
    }

    @Test
    void throttleSleepsBetweenSendsAndDefersOnceTheBudgetIsSpent() throws Exception {
        // 1/min => 60s delay; budget 90s => 1 sleep fits, the third recipient is deferred.
        NodeExecutionResult result =
                handler.execute(
                        "run-1",
                        node(
                                Map.of(
                                        "recipientsSource",
                                        "static",
                                        "staticRecipients",
                                        "a@x.test, b@x.test, c@x.test",
                                        "maxPerMinute",
                                        1)),
                        "{}");

        JsonNode out = json(result);
        assertEquals(2, out.get("sent").asInt());
        assertEquals(1, out.get("deferred").asInt());
        assertEquals(List.of(60_000L), sleeps);
        assertEquals("deferred", out.get("rows").get(2).get("status").asText());
        // A deferred recipient must not keep its dedup claim, or a follow-up run could never send
        // it.
        verify(dedup).release(anyString());
    }

    @Test
    void defaultRateIsOneSecondBetweenSends() {
        handler.execute(
                "run-1",
                node(Map.of("recipientsSource", "static", "staticRecipients", "a@x.test,b@x.test")),
                "{}");
        assertEquals(List.of(1_000L), sleeps);
    }

    @Test
    void recipientCapTruncatesAndReportsIt() throws Exception {
        NodeExecutionResult result =
                handler.execute(
                        "run-1",
                        node(
                                Map.of(
                                        "recipientsSource",
                                        "static",
                                        "staticRecipients",
                                        "a@x.test,b@x.test,c@x.test",
                                        "maxRecipients",
                                        2)),
                        "{}");
        JsonNode out = json(result);
        assertEquals(1, out.get("truncated").asInt());
        assertEquals(2, out.get("total").asInt());
    }

    @Test
    void skipIfRepliedSuppressesRecipientsWhoAnswered() throws Exception {
        when(messages.existsHumanReplyFrom("a@x.test")).thenReturn(true);
        NodeExecutionResult result =
                handler.execute(
                        "run-1",
                        node(
                                Map.of(
                                        "recipientsSource",
                                        "static",
                                        "staticRecipients",
                                        "a@x.test,b@x.test",
                                        "skipIfReplied",
                                        true)),
                        "{}");
        JsonNode out = json(result);
        assertEquals(1, out.get("sent").asInt());
        assertEquals("already replied", out.get("rows").get(0).get("reason").asText());
        verify(sender, never())
                .send(eq("a@x.test"), anyString(), anyString(), anyBoolean(), any(), any());
    }

    @Test
    void dedupClaimedRecipientIsNotSentAgain() throws Exception {
        when(dedup.tryClaim(anyString())).thenReturn(false);
        NodeExecutionResult result =
                handler.execute(
                        "run-1",
                        node(Map.of("recipientsSource", "static", "staticRecipients", "a@x.test")),
                        "{}");
        assertEquals(0, json(result).get("sent").asInt());
        assertEquals(1, json(result).get("skipped").asInt());
        verify(sender, never())
                .send(anyString(), anyString(), anyString(), anyBoolean(), any(), any());
    }

    @Test
    void perRecipientFailureIsRecordedReleasedAndDoesNotAbortTheBatch() throws Exception {
        doThrow(new ChannelDeliveryException("email", "550 rejected"))
                .when(sender)
                .send(eq("bad@x.test"), anyString(), anyString(), anyBoolean(), any(), any());
        NodeExecutionResult result =
                handler.execute(
                        "run-1",
                        node(
                                Map.of(
                                        "recipientsSource",
                                        "static",
                                        "staticRecipients",
                                        "bad@x.test,ok@x.test")),
                        "{}");
        JsonNode out = json(result);
        assertEquals("OK", result.status());
        assertEquals(1, out.get("sent").asInt());
        assertEquals(1, out.get("failed").asInt());
        assertEquals("550 rejected", out.get("rows").get(0).get("error").asText());
        verify(dedup).release(anyString());
        ArgumentCaptor<EmailMessage> saved = ArgumentCaptor.forClass(EmailMessage.class);
        verify(messages, org.mockito.Mockito.times(2)).save(saved.capture());
        assertEquals("FAILED", saved.getAllValues().get(0).getStatus());
    }

    @Test
    void recipientWhoseTemplateFailsToRenderIsReportedAndDoesNotAbortOrStrandTheBatch()
            throws Exception {
        WorkflowExpressionResolver.RecipientRenderer renderer =
                (field, template, vars, index) -> {
                    if ("b@x.test".equals(vars.get("email")) && "body".equals(field)) {
                        throw new WorkflowExpressionException(
                                "Expression {{ x }} in field \"body\": boom");
                    }
                    return template;
                };
        NodeExecutionResult result =
                handler.execute(
                        "run-1",
                        node(
                                Map.of(
                                        "recipientsSource",
                                        "static",
                                        "staticRecipients",
                                        "a@x.test,b@x.test,c@x.test")),
                        "{}",
                        renderer);

        JsonNode out = json(result);
        assertEquals("OK", result.status());
        assertEquals(2, out.get("sent").asInt());
        assertEquals(1, out.get("failed").asInt());
        assertTrue(out.get("rows").get(1).get("error").asText().contains("template error"));
        verify(dedup).release(anyString()); // b's claim is given back, so a retry can still reach b
        verify(sender, never())
                .send(eq("b@x.test"), anyString(), anyString(), anyBoolean(), any(), any());
        verify(sender).send(eq("c@x.test"), anyString(), anyString(), anyBoolean(), any(), any());
    }

    @Test
    void htmlBodyEscapesRecipientDataAndAppendsUnsubscribeLink() {
        handler.execute(
                "run-1",
                node(
                        Map.of(
                                "recipientsSource",
                                "items",
                                "html",
                                true,
                                "body",
                                "<p>Hi {{name}}</p>")),
                "[{\"email\":\"a@x.test\",\"name\":\"<b>Ann</b>\"}]");
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("a@x.test"), anyString(), body.capture(), eq(true), any(), any());
        assertTrue(body.getValue().contains("Hi &lt;b&gt;Ann&lt;/b&gt;"));
        assertTrue(
                body.getValue()
                        .contains(
                                "<a href=\"https://api.example.test/api/public/email/unsubscribe?t="));
        assertFalse(body.getValue().contains("<b>Ann"));
    }

    @Test
    void subjectCannotInjectHeaders() {
        handler.execute(
                "run-1",
                node(Map.of("subject", "Hi {{name}}")),
                "[{\"email\":\"a@x.test\",\"name\":\"Ann\\r\\nBcc: evil@x.test\"}]");
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(sender)
                .send(eq("a@x.test"), subject.capture(), anyString(), anyBoolean(), any(), any());
        assertFalse(subject.getValue().contains("\n"));
        assertFalse(subject.getValue().contains("\r"));
    }

    @Test
    void itemsFieldAndEmailFieldSelectTheRecipientArray() throws Exception {
        NodeExecutionResult result =
                handler.execute(
                        "run-1",
                        node(Map.of("itemsField", "leads", "emailField", "mail")),
                        "{\"leads\":[{\"mail\":\"a@x.test\",\"name\":\"Ann\"}]}");
        assertEquals(1, json(result).get("sent").asInt());
    }

    @Test
    void unconfiguredSmtpSkipsTheNodeWithoutTouchingRecipients() {
        doThrow(new ChannelConfigurationException("email", "No SMTP server configured"))
                .when(sender)
                .requireConfigured();
        NodeExecutionResult result =
                handler.execute(
                        "run-1",
                        node(Map.of("recipientsSource", "static", "staticRecipients", "a@x.test")),
                        "{}");
        assertEquals("SKIPPED", result.status());
        verify(suppressions, never()).findByEmailIn(any());
    }

    @Test
    void unreadableSuppressionListFailsClosed() {
        when(suppressions.findByEmailIn(any())).thenThrow(new RuntimeException("db down"));
        NodeExecutionResult result =
                handler.execute(
                        "run-1",
                        node(Map.of("recipientsSource", "static", "staticRecipients", "a@x.test")),
                        "{}");
        assertEquals("SKIPPED", result.status());
        verify(sender, never())
                .send(anyString(), anyString(), anyString(), anyBoolean(), any(), any());
    }
}
