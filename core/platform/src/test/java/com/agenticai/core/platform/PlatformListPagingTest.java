package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.platform.abuse.AbuseAnomalyEntry;
import com.agenticai.core.platform.abuse.AbuseMonitorController;
import com.agenticai.core.platform.abuse.AbuseSignalService;
import com.agenticai.core.platform.abuse.AbuseThrottleService;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** limit/offset + X-Total-Count / X-Has-More for the platform in-memory lists; no params = whole list as before. */
class PlatformListPagingTest {

    private static List<Integer> numbers(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    @Test
    void noParamsReturnsTheWholeListWithTotalHeaders() {
        ResponseEntity<List<Integer>> r = PlatformListPaging.window(numbers(120), null, null);
        assertEquals(120, r.getBody().size());
        assertEquals("120", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void anyParamUsesDefault50AndWindowsTheList() {
        ResponseEntity<List<Integer>> r = PlatformListPaging.window(numbers(120), null, 100L);
        assertEquals(numbers(120).subList(100, 120), r.getBody());
        assertEquals("false", r.getHeaders().getFirst("X-Has-More"));

        ResponseEntity<List<Integer>> first = PlatformListPaging.window(numbers(120), null, 0L);
        assertEquals(50, first.getBody().size());
        assertEquals("120", first.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", first.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void limitIsClampedAndNegativeOrHugeOffsetsAreSafe() {
        assertEquals(1, PlatformListPaging.window(numbers(10), 0, null).getBody().size());
        assertEquals(500, PlatformListPaging.window(numbers(1000), 5000, -3L).getBody().size());
        assertEquals(0, PlatformListPaging.window(numbers(10), 5, Long.MAX_VALUE).getBody().size());
        assertEquals("10", PlatformListPaging.window(numbers(10), 5, Long.MAX_VALUE)
            .getHeaders().getFirst("X-Total-Count"));
    }

    @Test
    void abuseMonitorListsArePagedOverHttp() throws Exception {
        AbuseSignalService service = mock(AbuseSignalService.class);
        AbuseAnomalyEntry a = new AbuseAnomalyEntry(null, "t", null, "t:u", null, null, "x", 1, null, null);
        when(service.recentAnomalies()).thenReturn(List.of(a, a, a));
        when(service.currentThrottled()).thenReturn(List.<AbuseThrottleService.ThrottleEntry>of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AbuseMonitorController(service)).build();

        mvc.perform(get("/api/platform/abuse-monitor/anomalies").param("limit", "2").param("offset", "1")
                .accept(org.springframework.http.MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "3"))
            .andExpect(header().string("X-Has-More", "false"))
            .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/platform/abuse-monitor/throttled").accept(org.springframework.http.MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "0"))
            .andExpect(jsonPath("$.length()").value(0));
    }
}
