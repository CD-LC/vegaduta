package com.agenticai.core.omnichannel.status;

/**
 * "This channel has no ACTIVE credential at all" - a different answer than "the probe failed",
 * which the connection-test card renders differently. Thrown by {@link ChannelConnection#probe()}
 * and {@code whatsapp.WhatsAppProvider#probeIdentity()}.
 */
public class ChannelNotConnectedException extends RuntimeException {}
