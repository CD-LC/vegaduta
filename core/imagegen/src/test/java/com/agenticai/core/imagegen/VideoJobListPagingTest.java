package com.agenticai.core.imagegen;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.storage.FileStorageService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Paging of GET /api/videogen/jobs: no params keep the complete list, limit/offset window it with headers. */
class VideoJobListPagingTest {

    private final VideoGenerationJobService service = mock(VideoGenerationJobService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new VideoGenerationController(service, mock(FileStorageService.class))).build();

    @Test
    void noParamsReadsTheCompleteListFromTheStart() throws Exception {
        when(service.listPage(0L, Integer.MAX_VALUE)).thenReturn(List.of());
        mvc.perform(get("/api/videogen/jobs"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Total-Count", "0"))
                .andExpect(header().string("X-Has-More", "false"));
        verify(service, never()).countAll();
    }

    @Test
    void pagedPastTheEndReportsTheRealTotal() throws Exception {
        when(service.listPage(40L, 20)).thenReturn(List.of());
        when(service.countAll()).thenReturn(33L);
        mvc.perform(get("/api/videogen/jobs").param("limit", "20").param("offset", "40"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "33"))
                .andExpect(header().string("X-Has-More", "false"));
    }

    @Test
    void limitIsClampedAndNegativeOffsetIsZero() throws Exception {
        when(service.listPage(anyLong(), anyInt())).thenReturn(List.of());
        when(service.countAll()).thenReturn(0L);
        mvc.perform(get("/api/videogen/jobs").param("limit", "99999").param("offset", "-5"))
                .andExpect(status().isOk());
        verify(service).listPage(0L, 200);
    }
}
