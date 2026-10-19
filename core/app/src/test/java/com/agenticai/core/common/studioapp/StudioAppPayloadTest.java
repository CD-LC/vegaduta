package com.agenticai.core.common.studioapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The builder-facing request/response mapping - the TS mirror
 * (web/app/build/studio/payload.ts) is covered by the studio vitest suite and
 * must agree with every case here.
 */
class StudioAppPayloadTest {

    @Test
    void rendersPlaceholdersAndLeavesUnknownOnes() {
        var user = new StudioAppPayload.AppUserView("u-1", "asha@x.io", "Asha");
        assertThat(StudioAppPayload.renderRequest("Summarise {{input}}", "hello", null, user))
            .isEqualTo("Summarise hello");
        assertThat(
            StudioAppPayload.renderRequest(
                "For {{user.name}} <{{user.email}}>: {{input}} / {{data.city}} / {{mystery}}",
                "hi", Map.of("city", "Pune"), user))
            .isEqualTo("For Asha <asha@x.io>: hi / Pune / {{mystery}}");
        assertThat(StudioAppPayload.renderRequest(null, "raw", null, null)).isEqualTo("raw");
        assertThat(StudioAppPayload.renderRequest("  ", "raw", null, null)).isEqualTo("raw");
        // Anonymous user -> user.* placeholders render empty, never throw.
        assertThat(StudioAppPayload.renderRequest("{{user.name}} said {{input}}", "hey", null, null))
            .isEqualTo(" said hey");
    }

    @Test
    void extractsDottedPathsFromJsonAndFallsBackToBody() {
        String body = "{\"reply\":\"hi\",\"data\":{\"items\":[{\"name\":\"first\"}]}}";
        assertThat(StudioAppPayload.extractResponse("reply", "reply", body)).isEqualTo("hi");
        assertThat(StudioAppPayload.extractResponse("data.items.0.name", "reply", body)).isEqualTo("first");
        // Missing path / non-JSON body -> the raw body, never eaten.
        assertThat(StudioAppPayload.extractResponse("missing.path", "reply", body)).isEqualTo(body);
        assertThat(StudioAppPayload.extractResponse("reply", "reply", "not json")).isEqualTo("not json");
        // Blank path -> the per-kind default field.
        assertThat(StudioAppPayload.extractResponse(null, "resultText", "{\"resultText\":\"done\"}")).isEqualTo("done");
    }
}
