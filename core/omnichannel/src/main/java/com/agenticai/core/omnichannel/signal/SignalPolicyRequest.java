package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;


/** Who the agent answers; a null field keeps the stored value. */
public record SignalPolicyRequest(UnknownSenderPolicy unknownSenders, SignalGroupMode groupMode) {
}
