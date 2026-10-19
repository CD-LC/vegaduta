package com.agenticai.core.omnichannel.matrix;

import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/** Matrix text sends for the kernel's {@link ChannelTextSend}: {@link MatrixChannelAdapter}'s action type and payload. */
@Component
public class MatrixTextSend implements ChannelTextSend {

    @Override
    public ChannelType channel() {
        return ChannelType.MATRIX;
    }

    @Override
    public String actionType() {
        return MatrixChannelAdapter.ACTION_TYPE;
    }

    @Override
    public String summary(String to) {
        return "Send Matrix message to " + to;
    }

    @Override
    public Object payload(String to, String subject, String body) {
        return new MatrixChannelAdapter.SendPayload(to, body, null, null);
    }
}
