package com.agenticai.core.omnichannel.whatsappqr;

/**
 * WhatsApp addressing for the QR channel. A chat's external id - what {@code channel_session
 * .external_id} and the approval payload's {@code to} hold - is the sender's international digits
 * ({@code 4915...}) for an ordinary account, or {@code <digits>@lid} when the sender's phone number
 * is hidden behind a WhatsApp LID (privacy proxy id - the same convention the Meta webhook path
 * already froze into existing sessions, migration V51). Groups are deliberately not addressed: v1
 * of this channel serves direct chats only.
 */
public final class WhatsAppQrAddress {

    private WhatsAppQrAddress() {}

    /** The session/send identifier derived from a bridge chat JID; null when it is not a person. */
    public static String externalId(String chatJid) {
        if (chatJid == null) {
            return null;
        }
        int at = chatJid.lastIndexOf('@');
        if (at <= 0) {
            return null;
        }
        String user = chatJid.substring(0, at);
        String server = chatJid.substring(at + 1);
        // A multi-device JID is "<number>:<device>@s.whatsapp.net" - drop the device part.
        int colon = user.indexOf(':');
        if (colon >= 0) {
            user = user.substring(0, colon);
        }
        String digits = user.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 30) {
            return null;
        }
        return switch (server) {
            case "s.whatsapp.net" -> digits;
            case "lid" -> digits + "@lid";
            default -> null;
        };
    }

    /** Whether {@code to} in a send request is something the bridge can address. */
    public static boolean isAddressable(String to) {
        if (to == null) {
            return false;
        }
        String v = to.trim();
        return v.matches("[0-9]{5,20}") || v.matches("[0-9]{5,30}@lid");
    }

    /** Mask for logs - last four characters only, like the other channel senders. */
    public static String mask(String externalId) {
        if (externalId == null || externalId.isEmpty()) {
            return "***";
        }
        return externalId.length() <= 4 ? "***" : "***" + externalId.substring(externalId.length() - 4);
    }

    /** {@code digits@lid} stays opaque; plain digits become a masked phone-like display. */
    public static String display(String externalId) {
        return externalId == null ? "" : externalId;
    }
}
