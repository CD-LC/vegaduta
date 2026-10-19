package com.agenticai.core.omnichannel.status;

import com.agenticai.core.agentspi.SetupStatusProvider;
import com.agenticai.core.omnichannel.status.ChannelStatusService.ChannelStatusEntry;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Answers the Assistant's setup guide for the channel topics (telegram, whatsapp, slack, email, sms)
 * from the same aggregate the Channels screen paints, so "is Slack connected" is the page's answer.
 */
@Component
public class ChannelSetupStatusProvider implements SetupStatusProvider {

    private static final Set<String> TOPICS = Set.of("telegram", "whatsapp", "slack", "email", "sms");

    private final ChannelStatusService channels;

    public ChannelSetupStatusProvider(ChannelStatusService channels) {
        this.channels = channels;
    }

    @Override
    public Map<String, Status> statuses() {
        Map<String, Status> out = new HashMap<>();
        for (ChannelStatusEntry c : channels.status().channels()) {
            if (!TOPICS.contains(c.key())) {
                continue;
            }
            boolean connected = "CONNECTED".equals(c.state());
            String detail = c.detail() == null || c.detail().isBlank() ? c.label() + (connected ? " is connected." : " is not connected.") : c.detail();
            out.put(c.key(), new Status(connected ? State.READY : State.NOT_SET_UP, detail));
        }
        return out;
    }
}
