package com.agenticai.core.agents.tools.browseruse;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Wave 2 (A) list paging for the browser profile and recipe lists (moved here from core/agents'
 * {@code Wave2ListPagingTest} with the controllers): the plain-array body plus limit/offset +
 * X-Total-Count + X-Has-More. No params = the whole list, as before.
 */
class BrowserListPagingTest {

    private static void assertPaged(MockMvc mvc, String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Total-Count", "0"))
                .andExpect(header().string("X-Has-More", "false"));
        mvc.perform(get(path).param("limit", "5").param("offset", "2"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mvc.perform(get(path).param("limit", "5").param("offset", "999999999999"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Has-More", "false"));
    }

    @Test
    void browserProfiles() throws Exception {
        var c = new BrowserProfileController(mock(BrowserProfileService.class));
        assertPaged(MockMvcBuilders.standaloneSetup(c).build(), "/api/browser/profiles");
    }

    @Test
    void browserRecipes() throws Exception {
        var c = new BrowserRecipeController(mock(BrowserRecipeService.class));
        assertPaged(MockMvcBuilders.standaloneSetup(c).build(), "/api/browser/recipes");
    }
}
