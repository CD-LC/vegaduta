package com.agenticai.core.agents.tools.browseruse;

import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.AGENT;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.CHAT_SESSION;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.TENANT;
import static com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.agents.tools.browseruse.BrowserTakeoverFixtures.Rig;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The HTTP surface: 404 everywhere with the flag off, 401 without an identity, owner-only,
 * header-borne token, no secrets in bodies.
 */
class BrowserTakeoverControllerTest {

    private static final String BASE = "/api/browser/takeover";
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static MockMvc mvc(Rig rig) {
        return MockMvcBuilders.standaloneSetup(new BrowserTakeoverController(rig.service, JSON))
                .build();
    }

    private static void signedInAs(String tenant, String user) {
        TenantContext.set(TenantContext.of(tenant, user, Set.of()));
    }

    @Test
    void withTheFlagOffEverySurfaceAnswers404ExceptTheCapabilityProbe() throws Exception {
        Rig rig = new Rig(BrowserTakeoverFixtures.settings(false, true, "return", false));
        MockMvc mvc = mvc(rig);
        signedInAs(TENANT, USER);
        String id = java.util.UUID.randomUUID().toString();
        String body = "{\"agentId\":\"" + AGENT + "\",\"sessionId\":\"" + CHAT_SESSION + "\"}";
        mvc.perform(get(BASE + "/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(
                        get(BASE + "/status")
                                .param("agentId", AGENT.toString())
                                .param("sessionId", CHAT_SESSION))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/pause").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/" + id + "/begin")).andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/" + id + "/token")).andExpect(status().isNotFound());
        mvc.perform(
                        post(BASE + "/" + id + "/input")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"type\":\"key\",\"key\":\"Tab\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get(BASE + "/" + id + "/stream")
                                .header(BrowserTakeoverController.TOKEN_HEADER, "x"))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/" + id + "/hand-back")).andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/" + id + "/cancel")).andExpect(status().isNotFound());
    }

    @Test
    void takeoverWithoutBrowserV2StaysOff() throws Exception {
        Rig rig = new Rig(BrowserTakeoverFixtures.settings(true, false, "return", false));
        signedInAs(TENANT, USER);
        mvc(rig).perform(get(BASE + "/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void nobodySignedInIsUnauthorised() throws Exception {
        Rig rig = new Rig(BrowserTakeoverFixtures.on());
        mvc(rig).perform(
                        get(BASE + "/status")
                                .param("agentId", AGENT.toString())
                                .param("sessionId", CHAT_SESSION))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theWholeFlowOverHttpAndAStrangerSeesNothing() throws Exception {
        Rig rig = new Rig(BrowserTakeoverFixtures.on());
        MockMvc mvc = mvc(rig);
        signedInAs(TENANT, USER);
        mvc.perform(get(BASE + "/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(
                        get(BASE + "/status")
                                .param("agentId", AGENT.toString())
                                .param("sessionId", CHAT_SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.takeover").doesNotExist());

        rig.service.agentRequest(rig.key, "two_factor", "Enter the code");
        String statusBody =
                mvc.perform(
                                get(BASE + "/status")
                                        .param("agentId", AGENT.toString())
                                        .param("sessionId", CHAT_SESSION))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.takeover.state").value("PAUSED_FOR_USER"))
                        .andExpect(jsonPath("$.takeover.host").value("shop.example"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String id = JSON.readTree(statusBody).path("takeover").path("takeoverId").asText();
        assertThat(statusBody).doesNotContain("token=abc").doesNotContain("login");

        // a stranger gets 404 on every call about this takeover
        signedInAs(TENANT, "user-2");
        mvc.perform(post(BASE + "/" + id + "/begin")).andExpect(status().isNotFound());
        mvc.perform(
                        get(BASE + "/status")
                                .param("agentId", AGENT.toString())
                                .param("sessionId", CHAT_SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.takeover").doesNotExist());

        signedInAs(TENANT, USER);
        String begin =
                mvc.perform(post(BASE + "/" + id + "/begin"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.view.state").value("USER_CONTROL"))
                        .andExpect(jsonPath("$.fps").value(2.0))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String token = JSON.readTree(begin).path("token").asText();
        assertThat(token).isNotBlank();

        // input needs the token header
        String click =
                "{\"type\":\"click\",\"x\":512,\"y\":320,\"frameWidth\":1024,\"frameHeight\":640,\"someFutureField\":1}";
        mvc.perform(
                        post(BASE + "/" + id + "/input")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(click))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(BASE + "/" + id + "/input")
                                .header(BrowserTakeoverController.TOKEN_HEADER, "bogus")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(click))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(BASE + "/" + id + "/input")
                                .header(BrowserTakeoverController.TOKEN_HEADER, token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(click))
                .andExpect(status().isNoContent());
        assertThat(rig.driver.calls).containsExactly("click 640,400");

        // the stream refuses a missing / forged token before opening anything
        mvc.perform(get(BASE + "/" + id + "/stream")).andExpect(status().isForbidden());
        mvc.perform(
                        get(BASE + "/" + id + "/stream")
                                .header(BrowserTakeoverController.TOKEN_HEADER, "bogus"))
                .andExpect(status().isForbidden());

        // rate limit surfaces as 429
        int tooMany = 0;
        for (int i = 0; i < 80; i++) {
            int code =
                    mvc.perform(
                                    post(BASE + "/" + id + "/input")
                                            .header(BrowserTakeoverController.TOKEN_HEADER, token)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"type\":\"key\",\"key\":\"Tab\"}"))
                            .andReturn()
                            .getResponse()
                            .getStatus();
            if (code == 429) {
                tooMany++;
            }
        }
        assertThat(tooMany).isGreaterThan(0);

        mvc.perform(post(BASE + "/" + id + "/token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(post(BASE + "/" + id + "/hand-back"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AGENT_CONTROL"));
        mvc.perform(
                        post(BASE + "/pause")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"agentId\":\""
                                                + AGENT
                                                + "\",\"sessionId\":\""
                                                + CHAT_SESSION
                                                + "\",\"kind\":\"login\",\"reason\":\"look\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedBy").value("user"));
        mvc.perform(
                        post(BASE + "/pause")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"agentId\":null}"))
                .andExpect(status().isBadRequest());
    }
}
