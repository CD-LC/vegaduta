package com.agenticai.core.omnichannel.session;

import java.util.UUID;

/** No {@link ChannelSession} with the given id - {@code ChannelPairingController}'s approve/reject actions. */
public class ChannelSessionNotFoundException extends RuntimeException {

    public ChannelSessionNotFoundException(UUID sessionId) {
        super("No channel session found with id " + sessionId);
    }
}
