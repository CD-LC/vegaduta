package com.agenticai.core.agents.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Accept / ignore / reject policy of {@link OpenResponsesInputMapper}, including malformed input.
 */
class OpenResponsesInputMapperTest {

    private final ObjectMapper mapper = com.agenticai.core.json.JsonMappers.plain();

    private OpenAiChatRequest map(String json) throws Exception {
        return OpenResponsesInputMapper.toChatRequest(
                mapper.readValue(json, OpenResponsesRequest.class));
    }

    private OpenAiCompatException reject(String json) {
        return assertThrows(OpenAiCompatException.class, () -> map(json));
    }

    private void assertRejected(String json, String code, String messageFragment) {
        OpenAiCompatException ex = reject(json);
        assertEquals(400, ex.status());
        assertEquals("invalid_request_error", ex.type());
        assertEquals(code, ex.code());
        assertTrue(ex.getMessage().contains(messageFragment), ex.getMessage());
    }

    @Test
    void stringInputBecomesOneUserMessage() throws Exception {
        OpenAiChatRequest r = map("{\"model\":\"m\",\"input\":\"hello\"}");
        assertEquals("m", r.model());
        assertEquals(1, r.messages().size());
        assertEquals("user", r.messages().get(0).role());
        assertEquals("hello", r.toPrompt(false));
    }

    @Test
    void instructionsBecomeALeadingSystemMessageFoldedIntoTheStatelessPrompt() throws Exception {
        OpenAiChatRequest r =
                map("{\"model\":\"m\",\"instructions\":\"Be terse.\",\"input\":\"hi\"}");
        assertEquals("system", r.messages().get(0).role());
        assertTrue(r.toPrompt(false).contains("Be terse."));
        // with a server-held session only the last user message goes through, as on chat
        // completions
        assertEquals("hi", r.toPrompt(true));
    }

    @Test
    void messageItemsWithPartsAndEasyInputMessagesAreAccepted() throws Exception {
        OpenAiChatRequest r =
                map(
                        "{\"model\":\"m\",\"input\":["
                                + "{\"type\":\"message\",\"role\":\"developer\",\"content\":\"Rules.\"},"
                                + "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"a\"},"
                                + "{\"type\":\"input_text\",\"text\":\"b\"}]},"
                                + "{\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\",\"annotations\":[]}]},"
                                + "{\"role\":\"user\",\"content\":\"last\"}]}");
        assertEquals(4, r.messages().size());
        assertEquals("a\nb", r.messages().get(1).text());
        String prompt = r.toPrompt(false);
        assertTrue(prompt.contains("Rules."));
        assertTrue(prompt.contains("User: a\nb"));
        assertTrue(prompt.contains("Assistant: ok"));
        assertTrue(prompt.endsWith("last"));
    }

    @Test
    void samplingFieldsAreAcceptedAndUnknownFieldsIgnored() throws Exception {
        OpenAiChatRequest r =
                map(
                        "{\"model\":\"m\",\"input\":\"hi\",\"temperature\":1.5,\"max_output_tokens\":64,"
                                + "\"top_p\":0.9,\"user\":\"u\",\"reasoning\":{\"effort\":\"low\"},\"store\":true,\"truncation\":\"auto\","
                                + "\"tools\":[],\"tool_choice\":\"auto\",\"metadata\":{\"a\":\"b\"},\"background\":false,\"future_field\":1}");
        assertEquals("hi", r.toPrompt(false));
    }

    @Test
    void previousResponseIdIsRejectedWithGuidance() {
        assertRejected(
                "{\"model\":\"m\",\"input\":\"hi\",\"previous_response_id\":\"resp_1\"}",
                "unsupported_parameter",
                "previous_response_id");
        assertTrue(
                reject("{\"model\":\"m\",\"input\":\"hi\",\"previous_response_id\":\"resp_1\"}")
                        .getMessage()
                        .contains("X-Session-Id"));
    }

    @Test
    void conversationBackgroundAndRequestSideToolsAreRejected() {
        assertRejected(
                "{\"model\":\"m\",\"input\":\"hi\",\"conversation\":\"conv_1\"}",
                "unsupported_parameter",
                "conversation");
        assertRejected(
                "{\"model\":\"m\",\"input\":\"hi\",\"background\":true}",
                "unsupported_parameter",
                "background");
        assertRejected(
                "{\"model\":\"m\",\"input\":\"hi\",\"tools\":[{\"type\":\"function\",\"name\":\"f\","
                        + "\"parameters\":{}}]}",
                "unsupported_parameter",
                "tools");
        assertRejected(
                "{\"model\":\"m\",\"input\":\"hi\",\"tools\":\"nope\"}",
                "unsupported_parameter",
                "tools");
        assertRejected(
                "{\"model\":\"m\",\"input\":\"hi\",\"tool_choice\":\"required\"}",
                "unsupported_parameter",
                "tool_choice");
        assertRejected(
                "{\"model\":\"m\",\"input\":\"hi\",\"tool_choice\":{\"type\":\"function\",\"name\":\"f\"}}",
                "unsupported_parameter",
                "tool_choice");
    }

    @Test
    void nonTextInputItemsAndPartsAreRejectedNotDropped() {
        assertRejected(
                "{\"model\":\"m\",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_image\","
                        + "\"image_url\":\"https://x/y.png\"}]}]}",
                "unsupported_parameter",
                "input_image");
        assertRejected(
                "{\"model\":\"m\",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_file\","
                        + "\"file_id\":\"f\"}]}]}",
                "unsupported_parameter",
                "input_file");
        assertRejected(
                "{\"model\":\"m\",\"input\":[{\"type\":\"function_call_output\",\"call_id\":\"c\","
                        + "\"output\":\"x\"}]}",
                "unsupported_parameter",
                "function_call_output");
        assertRejected(
                "{\"model\":\"m\",\"input\":[{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[]}]}",
                "unsupported_parameter",
                "reasoning");
        assertRejected(
                "{\"model\":\"m\",\"input\":[{\"type\":\"item_reference\",\"id\":\"msg_1\"}]}",
                "unsupported_parameter",
                "item_reference");
    }

    @Test
    void unsupportedTypeNamesAreSanitisedInTheMessage() {
        OpenAiCompatException ex =
                reject(
                        "{\"model\":\"m\",\"input\":[{\"type\":\"x\\n<script>\",\"role\":\"user\"}]}");
        assertFalse(ex.getMessage().contains("<"));
        assertFalse(ex.getMessage().contains("\n"));
    }

    @Test
    void malformedInputIsA400WithAClientSafeMessage() {
        assertRejected("{\"model\":\"m\"}", "invalid_request", "'input' is required");
        assertRejected(
                "{\"model\":\"m\",\"input\":null}", "invalid_request", "'input' is required");
        assertRejected("{\"model\":\"m\",\"input\":42}", "invalid_request", "'input' must be");
        assertRejected(
                "{\"model\":\"m\",\"input\":{\"role\":\"user\"}}",
                "invalid_request",
                "'input' must be");
        assertRejected(
                "{\"model\":\"m\",\"input\":[\"just a string\"]}", "invalid_request", "input[0]");
        assertRejected(
                "{\"model\":\"m\",\"input\":[{\"content\":\"no role\"}]}",
                "invalid_request",
                "role");
        assertRejected(
                "{\"model\":\"m\",\"input\":[{\"role\":\"tool\",\"content\":\"x\"}]}",
                "invalid_request",
                "role");
        assertRejected(
                "{\"model\":\"m\",\"input\":[{\"role\":\"user\",\"content\":7}]}",
                "invalid_request",
                "content");
        assertRejected(
                "{\"model\":\"m\",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\"}]}]}",
                "invalid_request",
                "'text'");
        assertRejected(
                "{\"model\":\"m\",\"input\":[{\"role\":\"user\",\"content\":[{\"text\":\"no type\"}]}]}",
                "unsupported_parameter",
                "content part");
        assertRejected(
                "{\"model\":\"m\",\"input\":\"   \"}",
                "invalid_request",
                "at least one user message");
        assertRejected(
                "{\"model\":\"m\",\"input\":[{\"role\":\"assistant\",\"content\":\"only me\"}]}",
                "invalid_request",
                "at least one user message");
        assertRejected(
                "{\"model\":\"m\",\"input\":[]}", "invalid_request", "at least one user message");
        assertRejected(
                "{\"model\":\"m\",\"input\":\"hi\",\"instructions\":{\"a\":1}}",
                "invalid_request",
                "'instructions'");
    }

    @Test
    void outOfRangeSamplingAndBadMetadataAreRejected() {
        assertRejected(
                "{\"model\":\"m\",\"input\":\"hi\",\"temperature\":2.5}",
                "invalid_request",
                "temperature");
        assertRejected(
                "{\"model\":\"m\",\"input\":\"hi\",\"temperature\":-0.1}",
                "invalid_request",
                "temperature");
        assertRejected(
                "{\"model\":\"m\",\"input\":\"hi\",\"max_output_tokens\":0}",
                "invalid_request",
                "max_output_tokens");
        assertRejected(
                "{\"model\":\"m\",\"input\":\"hi\",\"metadata\":[1]}",
                "invalid_request",
                "metadata");
        assertRejected(
                "{\"model\":\"m\",\"input\":\"hi\",\"metadata\":{\"k\":1}}",
                "invalid_request",
                "metadata");
        StringBuilder many = new StringBuilder("{\"model\":\"m\",\"input\":\"hi\",\"metadata\":{");
        for (int i = 0; i < 17; i++) {
            many.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":\"v\"");
        }
        assertRejected(many.append("}}").toString(), "invalid_request", "metadata");
    }
}
