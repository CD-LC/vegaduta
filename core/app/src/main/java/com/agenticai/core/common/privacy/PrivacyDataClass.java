package com.agenticai.core.common.privacy;

/** What a retention period applies to. Channels each class can be set for are in {@link #allows}. */
public enum PrivacyDataClass {
    /** Inbound and outbound message text: {@code channel_message_log} and the session preview. */
    TRANSCRIPT,
    /** Voice call recordings: {@code voice_call_recording} rows and their stored audio objects. */
    RECORDING,
    /** Facts the agents extracted: agent memory and tenant (shared) memory. Tenant default only. */
    EXTRACTED_FIELDS;

    /** The tenant-default marker in {@code privacy_retention_policy.channel}. */
    public static final String ALL_CHANNELS = "*";

    /** Channel value that call recordings are filed under. */
    public static final String VOICE = "VOICE";

    /**
     * Whether a rule for {@code channel} is meaningful for this class: transcripts per channel,
     * recordings for voice, extracted fields (not tied to a channel in storage) tenant-wide only.
     */
    public boolean allows(String channel) {
        if (ALL_CHANNELS.equals(channel)) {
            return true;
        }
        return switch (this) {
            case TRANSCRIPT -> isChannelType(channel);
            case RECORDING -> VOICE.equals(channel);
            case EXTRACTED_FIELDS -> false;
        };
    }

    private static boolean isChannelType(String channel) {
        try {
            com.agenticai.core.omnichannel.session.ChannelType.valueOf(channel);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }
}
