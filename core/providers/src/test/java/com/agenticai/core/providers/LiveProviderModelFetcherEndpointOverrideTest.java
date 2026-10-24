package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The live model list is fetched from the vendor address {@code app.llm.endpoints.<provider>} names. */
class LiveProviderModelFetcherEndpointOverrideTest {

    @Test
    void anOverriddenBaseUrlIsWhereTheModelListIsFetched() {
        ProviderEndpoints endpoints = new ProviderEndpoints();
        endpoints.getOpenai().setBaseUrl("https://proxy.example/openai");
        endpoints.getGrok().setBaseUrl("https://proxy.example/xai");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LiveProviderModelFetcher fetcher = new LiveProviderModelFetcher(builder.build(), endpoints);

        server.expect(requestTo("https://proxy.example/openai/v1/models"))
            .andRespond(withSuccess("{\"data\":[{\"id\":\"gpt-5.1\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://proxy.example/xai/v1/language-models"))
            .andRespond(withSuccess("{\"models\":[{\"id\":\"grok-4.5\"}]}", MediaType.APPLICATION_JSON));

        assertEquals("gpt-5.1", fetcher.fetch(ProviderName.OPENAI, "k").get(0).modelId());
        assertEquals("grok-4.5", fetcher.fetch(ProviderName.GROK, "k").get(0).modelId());

        server.verify();
    }
}
