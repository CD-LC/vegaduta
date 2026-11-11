package com.agenticai.core.omnichannel.twilio;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.SetDefaultAgentRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link SmsChannelController}: the SMS channel's one config write, the default agent. */
class SmsChannelControllerTest {

    @Test
    void setDefaultAgentBindsTheSmsChannel() {
        ChannelSessionService channelSessionService = mock(ChannelSessionService.class);
        SmsChannelController controller = new SmsChannelController(channelSessionService);
        UUID agentId = UUID.randomUUID();

        controller.setDefaultAgent(new SetDefaultAgentRequest(agentId));

        verify(channelSessionService).setDefaultAgent(ChannelType.SMS, agentId);
    }
}
