package com.agenticai.core.omnichannel.status;

import com.agenticai.core.omnichannel.status.ChannelStatusService.ChannelStatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Channels screen's one aggregate request; the logic is in {@link ChannelStatusService}. */
@RestController
@RequestMapping("/api/channels/status")
public class ChannelStatusController {

    private final ChannelStatusService channelStatusService;

    public ChannelStatusController(ChannelStatusService channelStatusService) {
        this.channelStatusService = channelStatusService;
    }

    @GetMapping
    public ChannelStatusResponse status() {
        return channelStatusService.status();
    }
}
