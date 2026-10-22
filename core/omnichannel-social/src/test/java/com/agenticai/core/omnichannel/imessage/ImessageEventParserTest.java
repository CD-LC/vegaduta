package com.agenticai.core.omnichannel.imessage;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.json.JsonMappers;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ImessageEventParserTest {

    private static JsonNode json(String s) throws Exception {
        return JsonMappers.plain().readTree(s);
    }

    private static final String DIRECT =
            "{\"type\":\"new-message\",\"data\":{\"guid\":\"msg-1\",\"text\":\"hello\",\"isFromMe\":false,"
                    + "\"dateCreated\":1700000000000,\"handle\":{\"address\":\"+1 (555) 010-0100\"},"
                    + "\"chats\":[{\"guid\":\"iMessage;-;+15550100100\",\"style\":45}],\"attachments\":[]}}";

    @Test
    void parsesAWebhookEventForADirectChat() throws Exception {
        ImessageEventParser.Inbound in = ImessageEventParser.parse(json(DIRECT)).orElseThrow();

        assertThat(in.guid()).isEqualTo("msg-1");
        assertThat(in.chatGuid()).isEqualTo("iMessage;-;+15550100100");
        assertThat(in.sender()).isEqualTo("+15550100100");
        assertThat(in.text()).isEqualTo("hello");
        assertThat(in.group()).isFalse();
        assertThat(in.dateMs()).isEqualTo(1700000000000L);
    }

    @Test
    void parsesABareMessageFromAQuery() throws Exception {
        JsonNode bare = json(DIRECT).path("data");
        assertThat(ImessageEventParser.parse(bare)).isPresent();
    }

    @Test
    void detectsGroupsByStyleOrGuid() throws Exception {
        String byStyle =
                DIRECT.replace(
                        "iMessage;-;+15550100100\",\"style\":45",
                        "iMessage;+;chat9\",\"style\":43");
        assertThat(ImessageEventParser.parse(json(byStyle)).orElseThrow().group()).isTrue();
        String byGuid =
                DIRECT.replace("\"style\":45", "\"style\":0").replace("iMessage;-;", "iMessage;+;");
        assertThat(ImessageEventParser.parse(json(byGuid)).orElseThrow().group()).isTrue();
    }

    @Test
    void ownMessagesAreDroppedSoTheAgentNeverAnswersItself() throws Exception {
        assertThat(
                        ImessageEventParser.parse(
                                json(DIRECT.replace("\"isFromMe\":false", "\"isFromMe\":true"))))
                .isEmpty();
    }

    @Test
    void otherEventTypesAreDropped() throws Exception {
        assertThat(
                        ImessageEventParser.parse(
                                json(DIRECT.replace("new-message", "typing-indicator"))))
                .isEmpty();
        assertThat(
                        ImessageEventParser.parse(
                                json(DIRECT.replace("new-message", "updated-message"))))
                .isEmpty();
    }

    @Test
    void reactionsAndGroupEventsAreDropped() throws Exception {
        assertThat(
                        ImessageEventParser.parse(
                                json(
                                        DIRECT.replace(
                                                "\"isFromMe\":false",
                                                "\"isFromMe\":false,\"associatedMessageGuid\":\"p:0/abc\""))))
                .isEmpty();
        assertThat(
                        ImessageEventParser.parse(
                                json(
                                        DIRECT.replace(
                                                "\"isFromMe\":false",
                                                "\"isFromMe\":false,\"itemType\":1"))))
                .isEmpty();
    }

    @Test
    void emptyTextAndMissingIdsAreDropped() throws Exception {
        assertThat(
                        ImessageEventParser.parse(
                                json(DIRECT.replace("\"text\":\"hello\"", "\"text\":\" \""))))
                .isEmpty();
        assertThat(
                        ImessageEventParser.parse(
                                json(DIRECT.replace("\"guid\":\"msg-1\"", "\"guid\":\"\""))))
                .isEmpty();
        assertThat(
                        ImessageEventParser.parse(
                                json(
                                        "{\"type\":\"new-message\",\"data\":{\"guid\":\"x\",\"text\":\"t\"}}")))
                .isEmpty();
        assertThat(ImessageEventParser.parse(null)).isEmpty();
    }

    @Test
    void aDirectChatFallsBackToTheChatIdentifierForTheSender() throws Exception {
        String noHandle = DIRECT.replace("\"handle\":{\"address\":\"+1 (555) 010-0100\"},", "");
        assertThat(ImessageEventParser.parse(json(noHandle)).orElseThrow().sender())
                .isEqualTo("+15550100100");
    }

    @Test
    void aGroupMessageWithoutAHandleIsDroppedBecauseTheSpeakerIsUnknown() throws Exception {
        String group =
                DIRECT.replace("\"handle\":{\"address\":\"+1 (555) 010-0100\"},", "")
                        .replace(
                                "iMessage;-;+15550100100\",\"style\":45",
                                "iMessage;+;chat9\",\"style\":43");
        assertThat(ImessageEventParser.parse(json(group))).isEmpty();
    }

    @Test
    void aChatGuidLongerThanTheSessionColumnIsDroppedNotPassedToTheDatabase() throws Exception {
        String longGuid = "iMessage;-;" + "a".repeat(60) + "@example.com";
        assertThat(longGuid.length()).isGreaterThan(ImessageEventParser.MAX_CHAT_GUID_CHARS);
        String event =
                DIRECT.replace(
                        "iMessage;-;+15550100100\",\"style\":45", longGuid + "\",\"style\":45");
        assertThat(ImessageEventParser.parse(json(event))).isEmpty();
        // exactly at the limit is still served
        String atLimit =
                "iMessage;-;"
                        + "b".repeat(ImessageEventParser.MAX_CHAT_GUID_CHARS - 11 - 12)
                        + "@example.com";
        assertThat(atLimit).hasSize(ImessageEventParser.MAX_CHAT_GUID_CHARS);
        assertThat(
                        ImessageEventParser.parse(
                                json(
                                        DIRECT.replace(
                                                "iMessage;-;+15550100100\",\"style\":45",
                                                atLimit + "\",\"style\":45"))))
                .isPresent();
    }
}
