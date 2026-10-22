package com.agenticai.core.omnichannel.imessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImessageApiClientTest {

    private static final String INFO =
            "{\"status\":200,\"data\":{\"server_version\":\"1.9.9\",\"os_version\":\"15.1\","
                    + "\"private_api\":true,\"helper_connected\":true}}";

    @Test
    void fetchServerInfoReadsTheServerAndSendsThePasswordAsQueryParameter() throws Exception {
        try (FakeBlueBubbles bb = new FakeBlueBubbles().on("GET /api/v1/server/info", 200, INFO)) {
            ImessageApiClient.ServerInfo info =
                    bb.client().fetchServerInfo(bb.url(), FakeBlueBubbles.PASSWORD);

            assertThat(info.serverVersion()).isEqualTo("1.9.9");
            assertThat(info.privateApi()).isTrue();
            assertThat(info.helperConnected()).isTrue();
            assertThat(bb.requests).hasSize(1);
            assertThat(bb.requests.get(0).password()).isEqualTo(FakeBlueBubbles.PASSWORD);
        }
    }

    @Test
    void aWrongPasswordIsReportedWithoutEchoingIt() throws Exception {
        try (FakeBlueBubbles bb = new FakeBlueBubbles().on("GET /api/v1/server/info", 200, INFO)) {
            assertThatThrownBy(() -> bb.client().fetchServerInfo(bb.url(), "not-the-password"))
                    .isInstanceOf(ChannelConfigurationException.class)
                    .hasMessageContaining("rejected the password")
                    .hasMessageNotContaining("not-the-password");
        }
    }

    @Test
    void serverErrorTextIsScrubbedOfThePassword() throws Exception {
        try (FakeBlueBubbles bb =
                new FakeBlueBubbles()
                        .on(
                                "GET /api/v1/server/info",
                                500,
                                "{\"status\":500,\"error\":{\"type\":\"x\",\"error\":\"boom "
                                        + FakeBlueBubbles.PASSWORD
                                        + " boom\"}}")) {
            assertThatThrownBy(
                            () -> bb.client().fetchServerInfo(bb.url(), FakeBlueBubbles.PASSWORD))
                    .isInstanceOf(ChannelConfigurationException.class)
                    .hasMessageContaining("HTTP 500")
                    .hasMessageNotContaining(FakeBlueBubbles.PASSWORD);
        }
    }

    @Test
    void sendTextPostsTheChatGuidAndTheMessage() throws Exception {
        try (FakeBlueBubbles bb =
                new FakeBlueBubbles()
                        .on("POST /api/v1/message/text", 200, "{\"status\":200,\"data\":{}}")) {
            String result =
                    bb.client()
                            .sendText(
                                    bb.url(),
                                    FakeBlueBubbles.PASSWORD,
                                    "iMessage;-;+15550100100",
                                    "hello there");

            JsonNode body = JsonMappers.plain().readTree(bb.requests.get(0).bodyText());
            assertThat(body.path("chatGuid").asText()).isEqualTo("iMessage;-;+15550100100");
            assertThat(body.path("message").asText()).isEqualTo("hello there");
            assertThat(body.path("tempGuid").asText()).isNotBlank();
            assertThat(result).doesNotContain("+15550100100");
        }
    }

    @Test
    void sendTextToABareNumberMapsToTheDirectChatGuid() throws Exception {
        try (FakeBlueBubbles bb =
                new FakeBlueBubbles()
                        .on("POST /api/v1/message/text", 200, "{\"status\":200,\"data\":{}}")) {
            bb.client().sendText(bb.url(), FakeBlueBubbles.PASSWORD, "+1 (555) 010-0100", "hi");

            JsonNode body = JsonMappers.plain().readTree(bb.requests.get(0).bodyText());
            assertThat(body.path("chatGuid").asText()).isEqualTo("iMessage;-;+15550100100");
        }
    }

    @Test
    void sendTextToANumberWithNoChatYetStartsOne() throws Exception {
        try (FakeBlueBubbles bb =
                new FakeBlueBubbles()
                        .on(
                                "POST /api/v1/message/text",
                                500,
                                "{\"status\":500,\"error\":{\"error\":\"Chat does not exist\"}}")
                        .on("POST /api/v1/chat/new", 200, "{\"status\":200,\"data\":{}}")) {
            // 500 is not retried as a new chat; only 4xx "no such chat" answers are.
            assertThatThrownBy(
                            () ->
                                    bb.client()
                                            .sendText(
                                                    bb.url(),
                                                    FakeBlueBubbles.PASSWORD,
                                                    "+15550100100",
                                                    "hi"))
                    .isInstanceOf(ChannelDeliveryException.class);
            bb.requests.clear();

            bb.on(
                    "POST /api/v1/message/text",
                    404,
                    "{\"status\":404,\"error\":{\"error\":\"Chat does not exist\"}}");
            bb.client().sendText(bb.url(), FakeBlueBubbles.PASSWORD, "+15550100100", "hi");

            assertThat(bb.requests)
                    .extracting(FakeBlueBubbles.Req::path)
                    .containsExactly("/api/v1/message/text", "/api/v1/chat/new");
            JsonNode body = JsonMappers.plain().readTree(bb.requests.get(1).bodyText());
            assertThat(body.path("addresses").get(0).asText()).isEqualTo("+15550100100");
            assertThat(body.path("message").asText()).isEqualTo("hi");
        }
    }

    @Test
    void aGroupGuidIsNeverTurnedIntoANewChat() throws Exception {
        try (FakeBlueBubbles bb = new FakeBlueBubbles()) {
            assertThatThrownBy(
                            () ->
                                    bb.client()
                                            .sendText(
                                                    bb.url(),
                                                    FakeBlueBubbles.PASSWORD,
                                                    "iMessage;+;chat123",
                                                    "hi"))
                    .isInstanceOf(ChannelDeliveryException.class);
            assertThat(bb.requests)
                    .extracting(FakeBlueBubbles.Req::path)
                    .containsExactly("/api/v1/message/text");
        }
    }

    @Test
    void sendAttachmentUploadsMultipartWithFileAndFollowsWithTheCaption() throws Exception {
        try (FakeBlueBubbles bb =
                new FakeBlueBubbles()
                        .on("POST /api/v1/message/attachment", 200, "{\"status\":200,\"data\":{}}")
                        .on("POST /api/v1/message/text", 200, "{\"status\":200,\"data\":{}}")) {
            byte[] bytes = "PNGDATA".getBytes(StandardCharsets.UTF_8);
            bb.client()
                    .sendAttachment(
                            bb.url(),
                            FakeBlueBubbles.PASSWORD,
                            "iMessage;-;+15550100100",
                            bytes,
                            "image/png",
                            "chart.png",
                            "Here is the chart");

            FakeBlueBubbles.Req upload = bb.requests.get(0);
            assertThat(upload.contentType()).startsWith("multipart/form-data; boundary=");
            String text = upload.bodyText();
            assertThat(text).contains("name=\"chatGuid\"").contains("iMessage;-;+15550100100");
            assertThat(text)
                    .contains("filename=\"chart.png\"")
                    .contains("Content-Type: image/png")
                    .contains("PNGDATA");
            JsonNode caption = JsonMappers.plain().readTree(bb.requests.get(1).bodyText());
            assertThat(caption.path("message").asText()).isEqualTo("Here is the chart");
        }
    }

    @Test
    void queryMessagesReturnsTheMessagesOldestFirstWithTheCursor() throws Exception {
        try (FakeBlueBubbles bb =
                new FakeBlueBubbles()
                        .on(
                                "POST /api/v1/message/query",
                                200,
                                "{\"status\":200,\"data\":[{\"guid\":\"a\",\"dateCreated\":5},{\"guid\":\"b\",\"dateCreated\":6}]}")) {
            List<JsonNode> messages =
                    bb.client().queryMessages(bb.url(), FakeBlueBubbles.PASSWORD, 4L, 100);

            assertThat(messages).extracting(m -> m.path("guid").asText()).containsExactly("a", "b");
            JsonNode body = JsonMappers.plain().readTree(bb.requests.get(0).bodyText());
            assertThat(body.path("after").asLong()).isEqualTo(4L);
            assertThat(body.path("sort").asText()).isEqualTo("ASC");
            assertThat(body.path("limit").asInt()).isEqualTo(100);
        }
    }

    @Test
    void aPrivateAddressIsRefusedUnlessTheOperatorAllowListsIt() throws Exception {
        try (FakeBlueBubbles bb = new FakeBlueBubbles().on("GET /api/v1/server/info", 200, INFO)) {
            ImessageApiClient strict =
                    new ImessageApiClient(JsonMappers.plain(), SsrfGuard::strict);

            assertThatThrownBy(() -> strict.fetchServerInfo(bb.url(), FakeBlueBubbles.PASSWORD))
                    .isInstanceOf(ChannelConfigurationException.class)
                    .hasMessageContaining("allowed-private-hosts");
            assertThat(bb.requests).as("nothing may reach the server").isEmpty();
        }
    }

    @Test
    void cloudMetadataAddressesAreRefusedEvenWhenAnotherHostIsAllowListed() throws Exception {
        try (FakeBlueBubbles bb = new FakeBlueBubbles()) {
            ImessageApiClient client = bb.client();
            assertThatThrownBy(() -> client.fetchServerInfo("http://169.254.169.254", "x"))
                    .isInstanceOf(ChannelConfigurationException.class)
                    .hasMessageContaining("not allowed");
        }
    }

    @Test
    void anUnreachableServerErrorNeverContainsTheUrlOrPassword() throws Exception {
        FakeBlueBubbles bb = new FakeBlueBubbles();
        String url = bb.url();
        ImessageApiClient client = bb.client();
        bb.close(); // port now refuses connections

        assertThatThrownBy(() -> client.fetchServerInfo(url, FakeBlueBubbles.PASSWORD))
                .isInstanceOf(ChannelConfigurationException.class)
                .hasMessageContaining("Could not reach the BlueBubbles server")
                .hasMessageNotContaining(FakeBlueBubbles.PASSWORD)
                .hasMessageNotContaining("password=");
    }

    @Test
    void aServerAddressWithAQueryOrFragmentIsRefusedBeforeAnyRequest() throws Exception {
        try (FakeBlueBubbles bb = new FakeBlueBubbles().on("GET /api/v1/server/info", 200, INFO)) {
            assertThatThrownBy(
                            () ->
                                    bb.client()
                                            .fetchServerInfo(
                                                    bb.url() + "/?x=1", FakeBlueBubbles.PASSWORD))
                    .isInstanceOf(ChannelConfigurationException.class)
                    .hasMessageContaining("base URL")
                    .hasMessageNotContaining(FakeBlueBubbles.PASSWORD);
            assertThatThrownBy(
                            () ->
                                    bb.client()
                                            .fetchServerInfo(
                                                    bb.url() + "/#frag", FakeBlueBubbles.PASSWORD))
                    .isInstanceOf(ChannelConfigurationException.class);
            assertThat(bb.requests).isEmpty();
        }
    }

    @Test
    void aLineBreakInTheRecipientCannotForgeMultipartPartsAndAHostileContentTypeIsNeutralised()
            throws Exception {
        try (FakeBlueBubbles bb =
                new FakeBlueBubbles()
                        .on(
                                "POST /api/v1/message/attachment",
                                200,
                                "{\"status\":200,\"data\":{}}")) {
            assertThatThrownBy(
                            () ->
                                    bb.client()
                                            .sendAttachment(
                                                    bb.url(),
                                                    FakeBlueBubbles.PASSWORD,
                                                    "iMessage;-;+1555\r\n--x\r\nContent-Disposition: form-data; name=\"method\"",
                                                    new byte[] {1},
                                                    "image/png",
                                                    "a.png",
                                                    null))
                    .isInstanceOf(ChannelDeliveryException.class);
            assertThat(bb.requests).isEmpty();

            bb.client()
                    .sendAttachment(
                            bb.url(),
                            FakeBlueBubbles.PASSWORD,
                            "iMessage;-;+15550100100",
                            new byte[] {1},
                            "image/png\r\nX-Evil: 1",
                            "a.png",
                            null);
            assertThat(bb.requests.get(0).bodyText())
                    .contains("Content-Type: application/octet-stream")
                    .doesNotContain("X-Evil");
        }
    }
}
