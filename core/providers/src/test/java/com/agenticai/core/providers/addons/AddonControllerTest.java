package com.agenticai.core.providers.addons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The HTTP contract of {@code /api/addons}: shapes, status codes and error bodies. */
class AddonControllerTest {

    private static final Principal OWNER = new TestingAuthenticationToken("owner-1", "n/a", "ROLE_tenant-admin");
    private static final String BEARER = "Bearer " + AddonKit.TOKEN;

    private AddonKit kit;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        LocalRateLimitFallback.reset();
        kit = new AddonKit();
        mvc = MockMvcBuilders.standaloneSetup(new AddonController(kit.service, kit.hostAuth)).build();
    }

    // ------------------------------------------------------------------- reads

    @Test
    void theListHasExactlyTheContractShape() throws Exception {
        mvc.perform(get("/api/addons").principal(OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogSchema").value(1))
                .andExpect(jsonPath("$.hardware.ramGb").value(16.0))
                .andExpect(jsonPath("$.hardware.cpuCores").value(8))
                .andExpect(jsonPath("$.hostBridge.configured").value(true))
                .andExpect(jsonPath("$.ollama.reachable").value(true))
                .andExpect(jsonPath("$.apply.command").value("./scripts/vegaduta personal --profile workspace"))
                .andExpect(jsonPath("$.apply.profiles[0]").value("workspace"))
                .andExpect(jsonPath("$.apply.pending[0]").value("work"))
                .andExpect(jsonPath("$.addons.length()").value(7))
                .andExpect(jsonPath("$.addons[0].id").value("search"))
                .andExpect(jsonPath("$.addons[0].kind").value("compose-profile"))
                .andExpect(jsonPath("$.addons[0].category").value("search"))
                .andExpect(jsonPath("$.addons[0].availability").value("available"))
                .andExpect(jsonPath("$.addons[0].unlocks[0]").value("web_search"))
                .andExpect(jsonPath("$.addons[0].status").value("off"))
                .andExpect(jsonPath("$.addons[0].desired").value(false))
                .andExpect(jsonPath("$.addons[0].canEnable").value(true))
                .andExpect(jsonPath("$.addons[0].blockedReason").doesNotExist())
                .andExpect(jsonPath("$.addons[0].fit").value("ok"))
                .andExpect(jsonPath("$.addons[1].requires[0]").value("search"))
                .andExpect(jsonPath("$.addons[4].kind").value("model-pull"))
                .andExpect(jsonPath("$.addons[4].model").value("qwen3.5:2b"))
                .andExpect(jsonPath("$.addons[6].status").value("unavailable"))
                .andExpect(jsonPath("$.addons[6].blockedReason").value("planned"))
                .andExpect(jsonPath("$.addons[6].canEnable").value(false));
    }

    @Test
    void oneAddonAndAnUnknownOne() throws Exception {
        mvc.perform(get("/api/addons/docs").principal(OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("docs"))
                .andExpect(jsonPath("$.sizeMb").value(4000))
                .andExpect(jsonPath("$.requiredBy.length()").value(0));
        mvc.perform(get("/api/addons/ghost").principal(OWNER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_addon"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ----------------------------------------------------------------- changes

    @Test
    void enableAnswersWithWhatChangedAndWhatTheHostMustDo() throws Exception {
        mvc.perform(post("/api/addons/docs/enable").principal(OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addon.id").value("docs"))
                .andExpect(jsonPath("$.addon.status").value("enable_requested"))
                .andExpect(jsonPath("$.changed[0]").value("search"))
                .andExpect(jsonPath("$.changed[1]").value("docs"))
                .andExpect(jsonPath("$.needsHost").value(true))
                .andExpect(jsonPath("$.apply.command").value("./scripts/vegaduta personal --profile rag --profile workspace --search"))
                .andExpect(jsonPath("$.apply.flags[0]").value("--search"));
    }

    @Test
    void disableRefusesWithTheDependentsAndCascadeLiftsIt() throws Exception {
        mvc.perform(post("/api/addons/docs/enable").principal(OWNER)).andExpect(status().isOk());
        mvc.perform(post("/api/addons/search/disable").principal(OWNER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("required_by"))
                .andExpect(jsonPath("$.details.dependents[0]").value("docs"));
        mvc.perform(post("/api/addons/search/disable?cascade=true").principal(OWNER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed[0]").value("docs"))
                .andExpect(jsonPath("$.changed[1]").value("search"));
    }

    @Test
    void plannedAndUnknownAddonsCannotBeSwitched() throws Exception {
        mvc.perform(post("/api/addons/later/enable").principal(OWNER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("not_available"));
        mvc.perform(post("/api/addons/ghost/enable").principal(OWNER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_addon"));
        mvc.perform(post("/api/addons/host/enable").principal(OWNER)).andExpect(status().isNotFound());
    }

    @Test
    void aModelWithoutTheModelServerIs503() throws Exception {
        kit.ollamaUp = false;
        mvc.perform(post("/api/addons/m-small/enable").principal(OWNER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ollama_unreachable"));
    }

    @Test
    void changesAreRateLimitedPerCaller() throws Exception {
        for (int i = 0; i < AddonController.CHANGES_PER_MINUTE; i++) {
            mvc.perform(post("/api/addons/search/enable").principal(OWNER)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/addons/search/enable").principal(OWNER))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("rate_limited"));
        Principal other = new TestingAuthenticationToken("owner-2", "n/a", "ROLE_tenant-admin");
        mvc.perform(post("/api/addons/search/enable").principal(other)).andExpect(status().isOk());
    }

    @Test
    void aRacedWriteIsA409ConflictNotA500() throws Exception {
        AddonService broken = mock(AddonService.class);
        when(broken.enable("search", "owner-1")).thenThrow(new OptimisticLockingFailureException("raced"));
        MockMvc racing = MockMvcBuilders.standaloneSetup(new AddonController(broken, kit.hostAuth)).build();
        racing.perform(post("/api/addons/search/enable").principal(OWNER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));
    }

    // -------------------------------------------------------------- host bridge

    @Test
    void hostWantedNeedsTheTokenAndFailsClosed() throws Exception {
        mvc.perform(get("/api/addons/host/wanted")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
        mvc.perform(get("/api/addons/host/wanted").header(HttpHeaders.AUTHORIZATION, "Bearer wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/addons/host/wanted").header(HttpHeaders.AUTHORIZATION, "Basic " + AddonKit.TOKEN))
                .andExpect(status().isUnauthorized());
        // a signed-in person's identity is not the installer's token
        mvc.perform(get("/api/addons/host/wanted").principal(OWNER)).andExpect(status().isUnauthorized());
    }

    @Test
    void hostWantedHasExactlyTheContractShape() throws Exception {
        kit.service.enable("browser", "owner-1");
        mvc.perform(get("/api/addons/host/wanted").header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").isNotEmpty())
                .andExpect(jsonPath("$.fingerprint").value(org.hamcrest.Matchers.hasLength(64)))
                .andExpect(jsonPath("$.apply.command").value("./scripts/vegaduta personal --profile workspace"))
                .andExpect(jsonPath("$.apply.envSet.CORE_IMAGE_VARIANT").value("-full"))
                .andExpect(jsonPath("$.apply.envUnset.length()").value(0))
                .andExpect(jsonPath("$.addons.length()").value(4))
                .andExpect(jsonPath("$.addons[2].id").value("browser"))
                .andExpect(jsonPath("$.addons[2].wanted").value(true))
                .andExpect(jsonPath("$.addons[2].rev").value(1))
                .andExpect(jsonPath("$.addons[2].status").value("enable_requested"))
                .andExpect(jsonPath("$.addons[2].compose.env.CORE_MEM_LIMIT").value("2560m"))
                .andExpect(jsonPath("$.addons[0].compose.flags[0]").value("--search"));
    }

    @Test
    void theHostBridgeIsOffUntilATokenIsConfigured() throws Exception {
        for (String token : new String[] {"", "short-token"}) {
            MockMvc off = MockMvcBuilders.standaloneSetup(new AddonController(kit.service, new AddonHostAuth(token))).build();
            off.perform(get("/api/addons/host/wanted").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("host_bridge_disabled"));
            off.perform(post("/api/addons/host/report").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isServiceUnavailable());
        }
    }

    @Test
    void hostReportParsesAndAnswersWithAcceptedAndIgnored() throws Exception {
        kit.service.enable("search", "owner-1");
        String body = """
            {"reports":[
              {"id":"search","rev":1,"status":"running","message":"healthy"},
              {"id":"ghost","rev":0,"status":"running"}
            ]}""";
        mvc.perform(post("/api/addons/host/report")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.ignored[0].id").value("ghost"))
                .andExpect(jsonPath("$.ignored[0].reason").value("unknown_addon"));
        mvc.perform(get("/api/addons/search").principal(OWNER))
                .andExpect(jsonPath("$.status").value("on"))
                .andExpect(jsonPath("$.reportedAt").isNotEmpty());
    }

    @Test
    void hostReportRejectsBadBodiesAndMissingTokens() throws Exception {
        mvc.perform(post("/api/addons/host/report").contentType(MediaType.APPLICATION_JSON).content("{\"reports\":[]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/addons/host/report")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reports\":[{\"id\":\"search\",\"rev\":0,\"status\":\"exploded\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(post("/api/addons/host/report")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reports\":[]}"))
                .andExpect(status().isBadRequest());
        List<Integer> unused = List.of();
        assertTrue(unused.isEmpty());
        assertEquals(0, kit.rows.size());
    }
}
