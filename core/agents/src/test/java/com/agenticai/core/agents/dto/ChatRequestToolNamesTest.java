package com.agenticai.core.agents.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatRequest#toolNames()}: the run-scoped tool selection. Absent must stay absent (a request without the
 * field is byte-identical to one from before it existed), an empty list is a real, different value ("no tools for
 * this run"), and the list is bounded like the other attacker-controlled input of a turn.
 */
class ChatRequestToolNamesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void open() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private static ChatRequest read(String json) throws Exception {
        return JSON.readValue(json, ChatRequest.class);
    }

    @Test
    void aBodyWithoutTheFieldHasNoSelection() throws Exception {
        assertThat(read("{\"message\":\"hi\"}").toolNames()).isNull();
    }

    @Test
    void anExplicitNullIsNoSelectionToo() throws Exception {
        assertThat(read("{\"message\":\"hi\",\"toolNames\":null}").toolNames()).isNull();
    }

    @Test
    void anEmptyListIsAnEmptySelectionNotAbsent() throws Exception {
        assertThat(read("{\"message\":\"hi\",\"toolNames\":[]}").toolNames()).isNotNull().isEmpty();
    }

    @Test
    void theNamesArriveInOrder() throws Exception {
        assertThat(read("{\"message\":\"hi\",\"toolNames\":[\"web_search\",\"fetch_web_page\"]}").toolNames())
                .containsExactly("web_search", "fetch_web_page");
    }

    @Test
    void theLegacyNineArgumentConstructorSelectsNothing() {
        ChatRequest legacy = new ChatRequest("s", "hi", null, null, null, null, null, false, false);
        assertThat(legacy.toolNames()).isNull();
    }

    private static ChatRequest with(List<String> names) {
        return new ChatRequest(null, "hi", null, null, null, null, null, false, false, names);
    }

    @Test
    void aReasonableSelectionIsValid() {
        assertThat(validator.validate(with(List.of("web_search", "mcp__crm__lookup")))).isEmpty();
        assertThat(validator.validate(with(List.of()))).isEmpty();
        assertThat(validator.validate(with(null))).isEmpty();
    }

    @Test
    void aBlankNameIsRefused() {
        assertThat(validator.validate(with(List.of("web_search", " ")))).isNotEmpty();
    }

    @Test
    void anOverlongNameIsRefused() {
        assertThat(validator.validate(with(List.of("x".repeat(ChatRequest.MAX_TOOL_NAME_LENGTH + 1))))).isNotEmpty();
        assertThat(validator.validate(with(List.of("x".repeat(ChatRequest.MAX_TOOL_NAME_LENGTH))))).isEmpty();
    }

    @Test
    void tooManyNamesAreRefused() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i <= ChatRequest.MAX_TOOL_NAMES; i++) {
            names.add("tool_" + i);
        }
        assertThat(validator.validate(with(names))).isNotEmpty();
        assertThat(validator.validate(with(names.subList(0, ChatRequest.MAX_TOOL_NAMES)))).isEmpty();
    }
}
