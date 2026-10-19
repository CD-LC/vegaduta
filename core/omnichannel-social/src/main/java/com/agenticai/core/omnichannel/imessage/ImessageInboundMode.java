package com.agenticai.core.omnichannel.imessage;

/** How inbound messages reach the platform: BlueBubbles pushes them to our webhook, or we poll its REST API. */
public enum ImessageInboundMode {
    WEBHOOK,
    POLLING
}
