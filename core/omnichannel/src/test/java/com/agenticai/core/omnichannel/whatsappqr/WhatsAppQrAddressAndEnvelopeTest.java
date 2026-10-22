package com.agenticai.core.omnichannel.whatsappqr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** JID -> external id mapping, addressability, masking and the bridge event parse. */
class WhatsAppQrAddressAndEnvelopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void externalIdKeepsDigitsForPeopleAndTheLidSuffixForHiddenNumbers() {
        assertThat(WhatsAppQrAddress.externalId("4915112345678@s.whatsapp.net"))
                .isEqualTo("4915112345678");
        assertThat(WhatsAppQrAddress.externalId("4915112345678:12@s.whatsapp.net"))
                .isEqualTo("4915112345678");
        assertThat(WhatsAppQrAddress.externalId("9988776655@lid")).isEqualTo("9988776655@lid");
        assertThat(WhatsAppQrAddress.externalId("1234-5678@g.us")).isNull();
        assertThat(WhatsAppQrAddress.externalId("status@broadcast")).isNull();
        assertThat(WhatsAppQrAddress.externalId(null)).isNull();
    }

    @Test
    void addressableIsOnlyWhatTheBridgeCanSendTo() {
        assertThat(WhatsAppQrAddress.isAddressable("4915112345678")).isTrue();
        assertThat(WhatsAppQrAddress.isAddressable("9988776655@lid")).isTrue();
        assertThat(WhatsAppQrAddress.isAddressable("+49 151")).isFalse();
        assertThat(WhatsAppQrAddress.isAddressable("abc@g.us")).isFalse();
        assertThat(WhatsAppQrAddress.isAddressable(null)).isFalse();
    }

    @Test
    void maskShowsOnlyTheTail() {
        assertThat(WhatsAppQrAddress.mask("4915112345678")).isEqualTo("***5678");
        assertThat(WhatsAppQrAddress.mask("123")).isEqualTo("***");
    }

    @Test
    void parseYieldsAPlainDirectMessage() throws Exception {
        JsonNode event =
                MAPPER.readTree(
                        "{\"id\":\"MSG1\",\"chatJid\":\"4917600000001@s.whatsapp.net\","
                                + "\"senderNumber\":\"4917600000001\",\"pushName\":\"Ann Example\","
                                + "\"text\":\"hello there\",\"timestampMs\":1700000000000}");

        Optional<WhatsAppQrEnvelope> parsed = WhatsAppQrEnvelope.parse(event);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().externalId()).isEqualTo("4917600000001");
        assertThat(parsed.get().senderNumber()).isEqualTo("4917600000001");
        assertThat(parsed.get().pushName()).isEqualTo("Ann Example");
        assertThat(parsed.get().text()).isEqualTo("hello there");
        assertThat(parsed.get().dedupKey()).isEqualTo("4917600000001:MSG1");
    }

    @Test
    void aLidSenderHasNoDialableNumber() throws Exception {
        JsonNode event =
                MAPPER.readTree(
                        "{\"id\":\"M2\",\"chatJid\":\"9988776655@lid\",\"senderNumber\":null,"
                                + "\"text\":\"hidden\"}");

        WhatsAppQrEnvelope envelope = WhatsAppQrEnvelope.parse(event).orElseThrow();

        assertThat(envelope.externalId()).isEqualTo("9988776655@lid");
        assertThat(envelope.senderNumber()).isNull();
    }

    @Test
    void malformedEventsAreDropped() throws Exception {
        assertThat(WhatsAppQrEnvelope.parse(MAPPER.readTree("{}"))).isEmpty();
        assertThat(
                        WhatsAppQrEnvelope.parse(
                                MAPPER.readTree(
                                        "{\"chatJid\":\"1@g.us\",\"id\":\"x\",\"text\":\"hi\"}")))
                .isEmpty();
        assertThat(
                        WhatsAppQrEnvelope.parse(
                                MAPPER.readTree(
                                        "{\"chatJid\":\"1@s.whatsapp.net\",\"id\":\"\",\"text\":\"hi\"}")))
                .isEmpty();
        assertThat(
                        WhatsAppQrEnvelope.parse(
                                MAPPER.readTree(
                                        "{\"chatJid\":\"1@s.whatsapp.net\",\"id\":\"x\",\"text\":\"  \"}")))
                .isEmpty();
        assertThat(WhatsAppQrEnvelope.parse(MAPPER.nullNode())).isEmpty();
    }

    @Test
    void controlCharactersAreStrippedAndTextIsCapped() throws Exception {
        String longText = "a".repeat(9000);
        JsonNode event =
                MAPPER.readTree(
                        MAPPER.writeValueAsString(
                                java.util.Map.of(
                                        "id", "M3",
                                        "chatJid", "1@s.whatsapp.net",
                                        "text", "ab\n" + longText)));
        WhatsAppQrEnvelope envelope = WhatsAppQrEnvelope.parse(event).orElseThrow();
        assertThat(envelope.text().length()).isLessThanOrEqualTo(WhatsAppQrEnvelope.MAX_TEXT_CHARS);
        assertThat(envelope.text()).doesNotContain("");
    }
}
