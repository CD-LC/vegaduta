package com.agenticai.core.omnichannel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link SignalEnvelope#parse}: the filter that decides which signal-cli frames reach the agent -
 * only a real text/attachment message from a person, never a receipt, reaction or the account's own
 * echo.
 */
class SignalEnvelopeTest {

    private static final String ACCOUNT = "+15550001111";
    private static final String SENDER = "+15551234567";
    private static final String SENDER_UUID = "123e4567-e89b-12d3-a456-426614174000";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String direct(String extra) {
        return "{\"envelope\":{\"sourceNumber\":\""
                + SENDER
                + "\",\"sourceUuid\":\""
                + SENDER_UUID
                + "\",\"dataMessage\":{\"timestamp\":12345,\"message\":\"hello\""
                + extra
                + "}}}";
    }

    @Test
    void parseAcceptsAPlainDirectMessage() {
        Optional<SignalEnvelope> parsed = SignalEnvelope.parse(json(direct("")), ACCOUNT);

        assertTrue(parsed.isPresent());
        SignalEnvelope env = parsed.get();
        assertEquals(SENDER, env.senderNumber());
        assertEquals(SENDER_UUID, env.senderUuid());
        assertEquals(12345L, env.timestamp());
        assertEquals("hello", env.text());
        assertFalse(env.isGroup());
        assertEquals(SENDER, env.conversationId());
        assertEquals(SENDER + ":" + SENDER + ":12345", env.dedupKey());
        assertFalse(env.addressedToAccount());
    }

    @Test
    void parseAcceptsTheRpcStyleParamsWrapper() {
        String wrapped =
                "{\"params\":{\"envelope\":{\"sourceNumber\":\""
                        + SENDER
                        + "\",\"dataMessage\":{\"message\":\"hi\"}}}}";
        assertTrue(SignalEnvelope.parse(json(wrapped), ACCOUNT).isPresent());
    }

    @Test
    void parseDropsAnythingWithoutADataMessage() {
        assertTrue(SignalEnvelope.parse(null, ACCOUNT).isEmpty());
        assertTrue(SignalEnvelope.parse(json("[1,2]"), ACCOUNT).isEmpty());
        assertTrue(SignalEnvelope.parse(json("{\"envelope\":{\"receiptMessage\":{}}}"), ACCOUNT).isEmpty());
    }

    @Test
    void parseDropsReactionsDeletesAndStoryContext() {
        assertTrue(SignalEnvelope.parse(json(direct(",\"reaction\":{\"emoji\":\"x\"}")), ACCOUNT).isEmpty());
        assertTrue(SignalEnvelope.parse(json(direct(",\"remoteDelete\":{}")), ACCOUNT).isEmpty());
        assertTrue(SignalEnvelope.parse(json(direct(",\"storyContext\":{}")), ACCOUNT).isEmpty());
    }

    @Test
    void parseDropsTheAccountsOwnEcho() {
        String own =
                "{\"envelope\":{\"sourceNumber\":\""
                        + ACCOUNT
                        + "\",\"dataMessage\":{\"message\":\"me\"}}}";
        assertTrue(SignalEnvelope.parse(json(own), ACCOUNT).isEmpty());
    }

    @Test
    void parseFallsBackToTheUuidWhenTheNumberIsHidden() {
        String hidden =
                "{\"envelope\":{\"source\":\""
                        + SENDER_UUID
                        + "\",\"sourceUuid\":\""
                        + SENDER_UUID
                        + "\",\"dataMessage\":{\"message\":\"hi\"}}}";
        Optional<SignalEnvelope> parsed = SignalEnvelope.parse(json(hidden), ACCOUNT);
        assertTrue(parsed.isPresent());
        assertEquals(SENDER_UUID, parsed.get().senderId());
        assertEquals(SENDER_UUID, parsed.get().conversationId());
    }

    @Test
    void parseDropsAMessageWithNoTextAndNoAttachment() {
        String empty =
                "{\"envelope\":{\"sourceNumber\":\""
                        + SENDER
                        + "\",\"dataMessage\":{\"timestamp\":1}}}";
        assertTrue(SignalEnvelope.parse(json(empty), ACCOUNT).isEmpty());
    }

    @Test
    void parseAppendsAttachmentNotesToTheText() {
        String withFile =
                "{\"envelope\":{\"sourceNumber\":\""
                        + SENDER
                        + "\",\"dataMessage\":{\"message\":\"see this\","
                        + "\"attachments\":[{\"contentType\":\"image/png\",\"filename\":\"a.png\"}]}}}";
        SignalEnvelope env = SignalEnvelope.parse(json(withFile), ACCOUNT).orElseThrow();
        assertEquals("see this\n[attachment: image/png a.png]", env.text());
    }

    @Test
    void parseBuildsTextFromAnAttachmentOnlyMessage() {
        String fileOnly =
                "{\"envelope\":{\"sourceNumber\":\""
                        + SENDER
                        + "\",\"dataMessage\":{\"attachments\":[{\"contentType\":\"application/pdf\"}]}}}";
        SignalEnvelope env = SignalEnvelope.parse(json(fileOnly), ACCOUNT).orElseThrow();
        assertEquals("[attachment: application/pdf]", env.text());
    }

    @Test
    void parseStripsControlCharactersAndCapsTheText() {
        String longText = "x".repeat(9_000);
        String json =
                "{\"envelope\":{\"sourceNumber\":\""
                        + SENDER
                        + "\",\"dataMessage\":{\"message\":\"\\u0001"
                        + longText
                        + "\"}}}";
        SignalEnvelope env = SignalEnvelope.parse(json(json), ACCOUNT).orElseThrow();
        assertEquals(SignalEnvelope.MAX_TEXT_CHARS, env.text().length());
        assertFalse(env.text().contains("\u0001"));
    }

    @Test
    void parseRoutesAGroupMessageUnderItsGroupExternalId() {
        String group =
                "{\"envelope\":{\"sourceNumber\":\""
                        + SENDER
                        + "\",\"dataMessage\":{\"message\":\"hi group\","
                        + "\"groupInfo\":{\"groupId\":\"groupid123\"}}}}";
        SignalEnvelope env = SignalEnvelope.parse(json(group), ACCOUNT).orElseThrow();
        assertTrue(env.isGroup());
        assertEquals("groupid123", env.groupId());
        assertEquals("group:groupid123", env.conversationId());
    }

    @Test
    void parseDropsAMessageWithAnUnusableGroupId() {
        String bad =
                "{\"envelope\":{\"sourceNumber\":\""
                        + SENDER
                        + "\",\"dataMessage\":{\"message\":\"hi\","
                        + "\"groupInfo\":{\"groupId\":\"bad id\"}}}}";
        assertTrue(SignalEnvelope.parse(json(bad), ACCOUNT).isEmpty());
    }

    @Test
    void parseFlagsAMentionOfTheAccount() {
        String mention =
                "{\"envelope\":{\"sourceNumber\":\""
                        + SENDER
                        + "\",\"dataMessage\":{\"message\":\"hi\","
                        + "\"mentions\":[{\"number\":\""
                        + ACCOUNT
                        + "\"}]}}}";
        assertTrue(SignalEnvelope.parse(json(mention), ACCOUNT).orElseThrow().addressedToAccount());
    }

    @Test
    void parseFlagsAQuoteOfTheAccountsOwnMessage() {
        String quote =
                "{\"envelope\":{\"sourceNumber\":\""
                        + SENDER
                        + "\",\"dataMessage\":{\"message\":\"reply\","
                        + "\"quote\":{\"authorNumber\":\""
                        + ACCOUNT
                        + "\"}}}}";
        assertTrue(SignalEnvelope.parse(json(quote), ACCOUNT).orElseThrow().addressedToAccount());
    }
}
