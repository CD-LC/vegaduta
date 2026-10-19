package com.agenticai.core.knowledge;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.knowledge.connector.KnowledgeConnectorController;
import com.agenticai.core.knowledge.connector.KnowledgeConnectorService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Collections and connectors lists: additive limit/offset + headers (wave 2 A). */
class KnowledgeListPagingWave2Test {

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
    void collections() throws Exception {
        var c =
                new KnowledgeCollectionController(
                        mock(KnowledgeCollectionService.class), mock(KnowledgeDocumentService.class));
        assertPaged(MockMvcBuilders.standaloneSetup(c).build(), "/api/knowledge/collections");
    }

    @Test
    void connectors() throws Exception {
        var c = new KnowledgeConnectorController(mock(KnowledgeConnectorService.class));
        var mvc = MockMvcBuilders.standaloneSetup(c).build();
        mvc.perform(get("/api/knowledge/connectors").param("collectionId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Total-Count", "0"));
        mvc.perform(get("/api/knowledge/connectors")
                        .param("collectionId", UUID.randomUUID().toString())
                        .param("limit", "3")
                        .param("offset", "1"))
                .andExpect(status().isOk());
    }
}
