package com.agenticai.core.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.agenticai.core.providers.dto.ProviderActivationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.server.ResponseStatusException;

class ProviderControllerUnknownNameTest {

    @Test
    void anUnknownPathProviderIsA400ListingTheAllowedValues() {
        assertThatThrownBy(() -> ProviderController.parseProvider("NOPE"))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getReason()).contains("ANTHROPIC").contains("PLATFORM_HOSTED");
            });
    }

    @Test
    void aKnownPathProviderIsCaseInsensitive() {
        assertThat(ProviderController.parseProvider("openai")).isEqualTo(ProviderName.OPENAI);
    }

    @Test
    void anUnknownBodyProviderNameListsTheAllowedValues() throws Exception {
        HttpMessageNotReadableException ex;
        try {
            new ObjectMapper().readValue("{\"providerName\":\"NOPE\",\"apiKey\":\"k\"}", ProviderActivationRequest.class);
            throw new AssertionError("expected a mapping failure");
        } catch (com.fasterxml.jackson.databind.JsonMappingException jme) {
            HttpInputMessage in = new MockHttpInputMessage(new byte[0]);
            ex = new HttpMessageNotReadableException("bad", jme, in);
        }
        ProviderController controller = new ProviderController(null, null, null, null);

        ResponseEntity<ProblemDetail> response = controller.handleUnreadableBody(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).contains("ANTHROPIC").contains("PLATFORM_HOSTED");
    }
}
