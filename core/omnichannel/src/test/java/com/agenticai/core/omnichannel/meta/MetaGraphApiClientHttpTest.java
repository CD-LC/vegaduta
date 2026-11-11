package com.agenticai.core.omnichannel.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.WhatsAppDeliveryRejectedException;
import com.agenticai.core.omnichannel.meta.MetaGraphApiClient.MessageTemplate;
import com.agenticai.core.omnichannel.meta.MetaGraphApiClient.PageInfo;
import com.agenticai.core.omnichannel.meta.MetaGraphApiClient.PhoneNumberHealth;
import com.agenticai.core.omnichannel.meta.MetaGraphApiClient.PhoneNumberInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** Request shapes and error mapping of {@link MetaGraphApiClient}, against a stubbed transport. */
class MetaGraphApiClientHttpTest {

    private HttpClient transport;
    private MetaGraphApiClient client;

    @BeforeEach
    void setUp() {
        transport = mock(HttpClient.class);
        client = new MetaGraphApiClient(new ObjectMapper());
        ReflectionTestUtils.setField(client, "httpClient", transport);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void reply(int status, String... bodies) throws Exception {
        HttpResponse[] responses = new HttpResponse[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(status);
            when(response.body()).thenReturn(bodies[i]);
            responses[i] = response;
        }
        org.mockito.Mockito.reset(transport);
        org.mockito.stubbing.OngoingStubbing stub =
                when(transport.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)));
        for (HttpResponse response : responses) {
            stub = stub.thenReturn(response);
        }
    }

    @SuppressWarnings("unchecked")
    private void unreachable() throws Exception {
        when(transport.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection reset"));
    }

    @SuppressWarnings("unchecked")
    private List<HttpRequest> sent(int times) throws Exception {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(times)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return captor.getAllValues();
    }

    @Test
    void phoneNumberInfoIsReadWithABearerToken() throws Exception {
        reply(
                200,
                "{\"display_phone_number\":\"+91 98123 45678\",\"verified_name\":\"Priya Bakery\"}");

        PhoneNumberInfo info = client.fetchPhoneNumberInfo("1234", "tok");

        assertThat(info).isEqualTo(new PhoneNumberInfo("+91 98123 45678", "Priya Bakery"));
        HttpRequest request = sent(1).get(0);
        assertThat(request.uri().toString())
                .contains("/v21.0/1234?fields=display_phone_number,verified_name");
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer tok");
    }

    @Test
    void phoneNumberInfoErrorsBecomeConfigurationErrors() throws Exception {
        reply(401, "bad token");
        assertThatThrownBy(() -> client.fetchPhoneNumberInfo("1234", "tok"))
                .isInstanceOf(ChannelConfigurationException.class)
                .hasMessageContaining("(401): bad token");

        unreachable();
        assertThatThrownBy(() -> client.fetchPhoneNumberInfo("1234", "tok"))
                .isInstanceOf(ChannelConfigurationException.class)
                .hasMessageContaining("Could not reach the Meta Graph API");
    }

    @Test
    void phoneNumberHealthReturnsQualityAndStatus() throws Exception {
        reply(200, "{\"quality_rating\":\"GREEN\",\"status\":\"CONNECTED\"}");
        assertThat(client.fetchPhoneNumberHealth("12 34", "tok"))
                .isEqualTo(new PhoneNumberHealth("GREEN", "CONNECTED"));
        assertThat(sent(1).get(0).uri().toString()).contains("/12+34?fields=quality_rating,status");

        reply(500, "oops");
        assertThatThrownBy(() -> client.fetchPhoneNumberHealth("1", "t"))
                .hasMessageContaining("(500)");
        unreachable();
        assertThatThrownBy(() -> client.fetchPhoneNumberHealth("1", "t"))
                .hasMessageContaining("Could not reach");
    }

    @Test
    void aTextSendNormalisesTheRecipientAndReportsTheMessageId() throws Exception {
        reply(200, "{\"messages\":[{\"id\":\"wamid.1\"}]}");

        String result = client.sendTextMessage("1234", "tok", "+91 98123-45678", "hello");

        assertThat(result).contains("(id wamid.1)").contains("Sent \"hello\"");
        HttpRequest request = sent(1).get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).endsWith("/1234/messages");
        assertThat(request.bodyPublisher()).isPresent();
    }

    @Test
    void aTextSendWithAnUnreadableReplyStillSucceedsAndLongBodiesAreTruncated() throws Exception {
        reply(200, "not json");
        assertThat(client.sendTextMessage("1", "t", "9198", "x".repeat(200)))
                .contains("(id unknown)")
                .contains("...");

        reply(200, "{\"messages\":[]}");
        assertThat(client.sendTextMessage("1", "t", null, "")).contains("(id unknown)");
    }

    @Test
    void aFailedTextSendIsADeliveryError() throws Exception {
        reply(400, "bad number");
        assertThatThrownBy(() -> client.sendTextMessage("1", "t", "9198", "hi"))
                .isInstanceOf(ChannelDeliveryException.class)
                .hasMessageContaining("(400): bad number");
        unreachable();
        assertThatThrownBy(() -> client.sendTextMessage("1", "t", "9198", "hi"))
                .isInstanceOf(ChannelDeliveryException.class)
                .hasMessageContaining("Could not reach");
    }

    @Test
    void aTemplateSendReportsTheIdAndCarriesMetaErrorCodesOnRejection() throws Exception {
        reply(200, "{\"messages\":[{\"id\":\"wamid.2\"}]}");
        assertThat(
                        client.sendTemplateMessage(
                                "1", "t", "+9198", "order_update", "en", List.of("Asha", "#12")))
                .contains("template \"order_update\"")
                .contains("(id wamid.2)");

        reply(400, "{\"error\":{\"code\":131047}}");
        assertThatThrownBy(() -> client.sendTemplateMessage("1", "t", "9198", "n", "en", null))
                .isInstanceOf(WhatsAppDeliveryRejectedException.class)
                .hasMessageContaining("template send failed");

        reply(200, "garbage");
        assertThat(client.sendTemplateMessage("1", "t", "9198", "n", "en", List.of()))
                .contains("(id unknown)");

        unreachable();
        assertThatThrownBy(() -> client.sendTemplateMessage("1", "t", "9198", "n", "en", null))
                .isInstanceOf(ChannelDeliveryException.class);
    }

    @Test
    void templatesAreListedAcrossPagesAndOnlySendableOnesSurvive() throws Exception {
        String page1 =
                "{\"data\":[{\"name\":\"hello\",\"language\":\"en\",\"status\":\"APPROVED\",\"category\":\"UTILITY\","
                        + "\"components\":[{\"type\":\"BODY\",\"text\":\"Hi {{1}}, order {{2}}\"}]}],"
                        + "\"paging\":{\"next\":\"https://next\",\"cursors\":{\"after\":\"CUR1\"}}}";
        String page2 =
                "{\"data\":[{\"name\":\"carousel\",\"language\":\"en\",\"status\":\"APPROVED\","
                        + "\"components\":[{\"type\":\"CAROUSEL\"}]}]}";
        reply(200, page1, page2);

        List<MessageTemplate> templates = client.listMessageTemplates("waba 1", "tok");

        assertThat(templates).extracting(MessageTemplate::name).containsExactly("hello");
        assertThat(templates.get(0).bodyParamCount()).isEqualTo(2);
        List<HttpRequest> requests = sent(2);
        assertThat(requests.get(0).uri().toString())
                .contains("/waba+1/message_templates")
                .doesNotContain("after=");
        assertThat(requests.get(1).uri().toString()).contains("after=CUR1");
    }

    @Test
    void templateListingErrorsAreConfigurationErrors() throws Exception {
        reply(403, "no access");
        assertThatThrownBy(() -> client.listMessageTemplates("w", "t"))
                .isInstanceOf(ChannelConfigurationException.class)
                .hasMessageContaining("(403)");
        unreachable();
        assertThatThrownBy(() -> client.listMessageTemplates("w", "t"))
                .hasMessageContaining("Could not reach");
    }

    @Test
    void theEmbeddedSignupCodeIsExchangedForAToken() throws Exception {
        reply(200, "{\"access_token\":\"EAAB\"}");
        assertThat(client.exchangeCodeForToken("code", "app", "secret", "https://x/cb"))
                .isEqualTo("EAAB");
        assertThat(sent(1).get(0).uri().toString())
                .contains("/oauth/access_token?client_id=app&client_secret=secret")
                .contains("redirect_uri=https%3A%2F%2Fx%2Fcb&code=code");

        reply(200, "{}");
        assertThatThrownBy(() -> client.exchangeCodeForToken("c", "a", "s", "r"))
                .hasMessageContaining("no access_token");
        reply(400, "bad code");
        assertThatThrownBy(() -> client.exchangeCodeForToken("c", "a", "s", "r"))
                .hasMessageContaining("(400)");
        unreachable();
        assertThatThrownBy(() -> client.exchangeCodeForToken("c", "a", "s", "r"))
                .hasMessageContaining("OAuth token endpoint");
    }

    @Test
    void subscribingTheAppToAWabaPostsToSubscribedApps() throws Exception {
        reply(200, "{\"success\":true}");
        client.subscribeAppToWaba("waba", "tok");
        assertThat(sent(1).get(0).uri().toString()).endsWith("/waba/subscribed_apps");

        reply(400, "denied");
        assertThatThrownBy(() -> client.subscribeAppToWaba("waba", "tok"))
                .hasMessageContaining("(400)");
        unreachable();
        assertThatThrownBy(() -> client.subscribeAppToWaba("waba", "tok"))
                .hasMessageContaining("Could not reach");
    }

    @Test
    void pageAndInstagramLookups() throws Exception {
        reply(200, "{\"name\":\"My Page\"}");
        assertThat(client.fetchPageInfo("p1", "tok")).isEqualTo(new PageInfo("My Page"));
        reply(404, "missing");
        assertThatThrownBy(() -> client.fetchPageInfo("p1", "tok"))
                .isInstanceOf(ChannelConfigurationException.class)
                .hasMessageContaining("Page ID");
        unreachable();
        assertThatThrownBy(() -> client.fetchPageInfo("p1", "tok"))
                .hasMessageContaining("Could not reach");

        reply(200, "{\"username\":\"priya_bakes\"}");
        assertThat(client.fetchInstagramUsername("ig1", "tok")).isEqualTo("priya_bakes");
        reply(400, "x");
        assertThat(client.fetchInstagramUsername("ig1", "tok")).isNull();
        unreachable();
        assertThat(client.fetchInstagramUsername("ig1", "tok")).isNull();
    }

    @Test
    void messengerAndInstagramSendsShareOneEndpoint() throws Exception {
        reply(200, "{}");
        assertThat(client.sendMessengerMessage("tok", "psid", "hi"))
                .isEqualTo("Sent Messenger message to psid.");
        assertThat(client.sendInstagramMessage("tok", "igsid", "hi"))
                .isEqualTo("Sent Instagram DM to igsid.");
        assertThat(sent(2).get(0).uri().toString()).endsWith("/me/messages");

        reply(400, "blocked");
        assertThatThrownBy(() -> client.sendMessengerMessage("tok", "psid", "hi"))
                .isInstanceOf(ChannelDeliveryException.class)
                .hasMessageContaining("(400): blocked");
        unreachable();
        assertThatThrownBy(() -> client.sendInstagramMessage("tok", "igsid", "hi"))
                .isInstanceOf(ChannelDeliveryException.class)
                .hasMessageContaining("Could not reach");
    }
}
