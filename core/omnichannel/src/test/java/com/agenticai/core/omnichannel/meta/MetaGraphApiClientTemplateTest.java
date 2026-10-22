package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

/**
 * The wire shape of a WhatsApp template send and the parsing of Meta's template listing - the two
 * things a real Graph API call depends on - plus the listing endpoint's flag and authority gates.
 */
class MetaGraphApiClientTemplateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- send payload ---

    @Test
    void templatePayloadMatchesTheCloudApiContract() throws Exception {
        Map<String, Object> payload =
                MetaGraphApiClient.templatePayload(
                        "919999900000", "invoice_reminder", "en_US", List.of("Asha", "INV-42"));

        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(payload));
        assertEquals("whatsapp", json.path("messaging_product").asText());
        assertEquals("919999900000", json.path("to").asText());
        assertEquals("template", json.path("type").asText());
        assertEquals("invoice_reminder", json.path("template").path("name").asText());
        assertEquals("en_US", json.path("template").path("language").path("code").asText());
        JsonNode components = json.path("template").path("components");
        assertEquals(1, components.size());
        assertEquals("body", components.get(0).path("type").asText());
        JsonNode parameters = components.get(0).path("parameters");
        assertEquals(2, parameters.size());
        assertEquals("text", parameters.get(0).path("type").asText());
        assertEquals("Asha", parameters.get(0).path("text").asText());
        assertEquals("INV-42", parameters.get(1).path("text").asText());
        assertFalse(json.has("text"), "a template send carries no free-text body");
    }

    @Test
    void aTemplateWithoutVariablesSendsNoComponentsAndBlankValuesStayFilled() throws Exception {
        JsonNode none =
                MAPPER.readTree(
                        MAPPER.writeValueAsString(
                                MetaGraphApiClient.templatePayload(
                                        "1555", "hello_world", "en", List.of())));
        assertFalse(none.path("template").has("components"));
        JsonNode nullParams =
                MAPPER.readTree(
                        MAPPER.writeValueAsString(
                                MetaGraphApiClient.templatePayload(
                                        "1555", "hello_world", "en", null)));
        assertFalse(nullParams.path("template").has("components"));

        JsonNode blanks =
                MAPPER.readTree(
                        MAPPER.writeValueAsString(
                                MetaGraphApiClient.templatePayload(
                                        "1555", "t", "en", Arrays.asList("", null))));
        JsonNode parameters = blanks.path("template").path("components").get(0).path("parameters");
        assertEquals(
                " ",
                parameters.get(0).path("text").asText(),
                "Meta rejects an empty text parameter");
        assertEquals(" ", parameters.get(1).path("text").asText());
    }

    // --- listing ---

    @Test
    void onlyApprovedTemplatesAreListedWithTheirBodyAndVariableCount() throws Exception {
        JsonNode root =
                MAPPER.readTree(
                        """
            {"data": [
              {"name": "invoice_reminder", "language": "en_US", "status": "APPROVED", "category": "UTILITY",
               "components": [
                 {"type": "HEADER", "format": "TEXT", "text": "Reminder"},
                 {"type": "BODY", "text": "Hi {{1}}, invoice {{2}} is due. Thanks {{1}}!"},
                 {"type": "FOOTER", "text": "Acme"}]},
              {"name": "promo", "language": "en", "status": "REJECTED", "category": "MARKETING",
               "components": [{"type": "BODY", "text": "Sale"}]},
              {"name": "pending_one", "language": "en", "status": "PENDING", "components": []},
              {"name": "hello_world", "language": "en", "status": "approved",
               "components": [{"type": "BODY", "text": "Hello"}]},
              {"name": "", "language": "en", "status": "APPROVED", "components": []}
            ], "paging": {}}
            """);

        List<MetaGraphApiClient.MessageTemplate> templates =
                MetaGraphApiClient.parseApprovedTemplates(root);

        assertEquals(2, templates.size());
        MetaGraphApiClient.MessageTemplate reminder = templates.get(0);
        assertEquals("invoice_reminder", reminder.name());
        assertEquals("en_US", reminder.language());
        assertEquals("UTILITY", reminder.category());
        assertEquals("Hi {{1}}, invoice {{2}} is due. Thanks {{1}}!", reminder.bodyText());
        assertEquals(2, reminder.bodyParamCount(), "a repeated placeholder is one variable");
        assertEquals("hello_world", templates.get(1).name());
        assertEquals(0, templates.get(1).bodyParamCount());

        assertTrue(MetaGraphApiClient.parseApprovedTemplates(MAPPER.readTree("{}")).isEmpty());
        assertTrue(MetaGraphApiClient.parseApprovedTemplates(null).isEmpty());
    }

    @Test
    void templatesThatNeedMoreThanBodyTextParametersAreNotOffered() throws Exception {
        JsonNode root =
                MAPPER.readTree(
                        """
            {"data": [
              {"name": "plain", "language": "en", "status": "APPROVED",
               "components": [{"type": "BODY", "text": "Hi {{1}}"},
                 {"type": "BUTTONS", "buttons": [{"type": "URL", "url": "https://example.com/pay"}, {"type": "QUICK_REPLY", "text": "Ok"}]}]},
              {"name": "image_header", "language": "en", "status": "APPROVED",
               "components": [{"type": "HEADER", "format": "IMAGE"}, {"type": "BODY", "text": "Hi"}]},
              {"name": "variable_header", "language": "en", "status": "APPROVED",
               "components": [{"type": "HEADER", "format": "TEXT", "text": "Order {{1}}"}, {"type": "BODY", "text": "Hi"}]},
              {"name": "dynamic_url", "language": "en", "status": "APPROVED",
               "components": [{"type": "BODY", "text": "Hi"},
                 {"type": "BUTTONS", "buttons": [{"type": "URL", "url": "https://example.com/{{1}}"}]}]},
              {"name": "otp", "language": "en", "status": "APPROVED",
               "components": [{"type": "BODY", "text": "{{1}} is your code"}, {"type": "BUTTONS", "buttons": [{"type": "OTP"}]}]},
              {"name": "named", "language": "en", "status": "APPROVED", "parameter_format": "NAMED",
               "components": [{"type": "BODY", "text": "Hi {{first_name}}"}]},
              {"name": "named_without_flag", "language": "en", "status": "APPROVED",
               "components": [{"type": "BODY", "text": "Hi {{first_name}}"}]}
            ]}
            """);

        List<MetaGraphApiClient.MessageTemplate> templates =
                MetaGraphApiClient.parseApprovedTemplates(root);

        assertEquals(
                List.of("plain"),
                templates.stream().map(MetaGraphApiClient.MessageTemplate::name).toList());
    }

    @Test
    void pagingFollowsTheCursorOnlyWhileThereIsANextPage() throws Exception {
        assertEquals(
                "abc",
                MetaGraphApiClient.nextTemplateCursor(
                        MAPPER.readTree(
                                "{\"data\": [], \"paging\": {\"cursors\": {\"before\": \"x\", \"after\": \"abc\"}, \"next\": \"https://graph.facebook.com/next\"}}")));
        assertNull(
                MetaGraphApiClient.nextTemplateCursor(
                        MAPPER.readTree(
                                "{\"data\": [], \"paging\": {\"cursors\": {\"before\": \"x\", \"after\": \"abc\"}}}")),
                "no next link = last page");
        assertNull(MetaGraphApiClient.nextTemplateCursor(MAPPER.readTree("{\"data\": []}")));
        assertNull(MetaGraphApiClient.nextTemplateCursor(null));
    }

    @Test
    void templateParametersAreNormalisedToWhatMetaAccepts() throws Exception {
        assertEquals(
                "12 Main St Springfield",
                MetaGraphApiClient.normalizeTemplateParam("12 Main St\r\nSpringfield"));
        assertEquals("a b c", MetaGraphApiClient.normalizeTemplateParam("a\tb      c"));
        assertEquals("", MetaGraphApiClient.normalizeTemplateParam(null));

        JsonNode json =
                MAPPER.readTree(
                        MAPPER.writeValueAsString(
                                MetaGraphApiClient.templatePayload(
                                        "1555", "t", "en", List.of("line one\nline two", "\n"))));
        JsonNode parameters = json.path("template").path("components").get(0).path("parameters");
        assertEquals("line one line two", parameters.get(0).path("text").asText());
        assertEquals(
                " ",
                parameters.get(1).path("text").asText(),
                "a value that is only whitespace still fills the slot");
    }

    // --- endpoint ---

    private static Authentication withAuthorities(String... authorities) {
        return new TestingAuthenticationToken("ann", "n/a", authorities);
    }

    @Test
    void theListingEndpointIs404WhenTheFlagIsOffAnd403WithoutManageChannels() {
        WhatsAppDispatchService dispatch = mock(WhatsAppDispatchService.class);

        ResponseStatusException off =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                new WhatsAppTemplateController(dispatch, false)
                                        .list(withAuthorities("PERM_MANAGE_CHANNELS"), null, null));
        assertEquals(HttpStatus.NOT_FOUND, off.getStatusCode());

        WhatsAppTemplateController on = new WhatsAppTemplateController(dispatch, true);
        assertEquals(
                HttpStatus.FORBIDDEN,
                assertThrows(
                                ResponseStatusException.class,
                                () -> on.list(withAuthorities("ROLE_tenant-member"), null, null))
                        .getStatusCode());
        assertEquals(
                HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> on.list(null, null, null))
                        .getStatusCode());
        verifyNoInteractions(dispatch);
    }

    @Test
    void theListingEndpointMapsTemplatesAndIsEmptyWhenMetaIsNotConnected() {
        WhatsAppDispatchService dispatch = mock(WhatsAppDispatchService.class);
        WhatsAppTemplateController controller = new WhatsAppTemplateController(dispatch, true);
        Authentication admin = withAuthorities("PERM_MANAGE_CHANNELS");

        when(dispatch.listApprovedTemplates()).thenReturn(List.of());
        assertTrue(controller.list(admin, null, null).getBody().isEmpty());

        String longBody = "x".repeat(WhatsAppTemplateController.PREVIEW_CHARS + 50);
        when(dispatch.listApprovedTemplates())
                .thenReturn(
                        List.of(
                                new WhatsAppTemplate(
                                        "invoice_reminder", "en_US", "UTILITY", "Hi {{1}}", 1),
                                new WhatsAppTemplate("long_one", "en", null, longBody, 0)));
        List<WhatsAppTemplateController.WhatsAppTemplateResponse> listed =
                controller.list(admin, null, null).getBody();
        assertEquals(
                new WhatsAppTemplateController.WhatsAppTemplateResponse(
                        "invoice_reminder", "en_US", 1, "Hi {{1}}", "UTILITY"),
                listed.get(0));
        assertEquals(
                WhatsAppTemplateController.PREVIEW_CHARS + 3, listed.get(1).preview().length());
    }
}
