package com.agenticai.core.devices;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** GET /api/devices and /api/devices/{id}/commands: additive limit/offset + headers (wave 2 A). */
class DeviceListPagingTest {

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
    void devicesAndCommands() throws Exception {
        var c =
                new DeviceController(
                        mock(DevicePairingService.class),
                        mock(DeviceCommandService.class),
                        mock(MobileDeviceRepository.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(c).build();
        assertPaged(mvc, "/api/devices");
        assertPaged(mvc, "/api/devices/" + UUID.randomUUID() + "/commands");
    }
}
