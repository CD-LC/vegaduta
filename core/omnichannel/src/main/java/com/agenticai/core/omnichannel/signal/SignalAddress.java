package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How a Signal conversation is named inside the platform and on the wire.
 *
 * <ul>
 *   <li>A person is their phone number in E.164 ({@code +4915112345678}), or their ACI UUID when
 *       they hide their number. {@code channel_session.external_id} holds that, so memory follows
 *       the person.
 *   <li>A group is {@code group:<internal id>} (the id signal-cli reports on a received message).
 *       signal-cli-rest-api wants {@code group.<base64 of that internal id>} as the recipient of
 *       a send, which {@link #toRecipient} produces.
 * </ul>
 */
public final class SignalAddress {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    private static final Pattern UUID_FORM =
            Pattern.compile(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    // Base64 of signal-cli's 32-byte internal group id is 44 chars; the column is VARCHAR(64).
    private static final Pattern GROUP_INTERNAL_ID = Pattern.compile("^[A-Za-z0-9+/=_-]{8,56}$");
    static final String GROUP_PREFIX = "group:";

    private SignalAddress() {}

    /** Strips spaces, dashes and parentheses and checks the E.164 shape. */
    public static String normalizeNumber(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[\\s\\-().]", "");
        if (!E164.matcher(cleaned).matches()) {
            throw new ChannelConfigurationException(
                    "signal",
                    "The Signal number must be in international format, for example +4915112345678.");
        }
        return cleaned;
    }

    public static boolean isGroup(String externalId) {
        return externalId != null && externalId.startsWith(GROUP_PREFIX);
    }

    public static String groupExternalId(String internalGroupId) {
        return GROUP_PREFIX + internalGroupId;
    }

    /** True when {@code to} names a person (number or UUID) or a group the platform can address. */
    public static boolean isAddressable(String to) {
        if (to == null) {
            return false;
        }
        if (isGroup(to)) {
            return GROUP_INTERNAL_ID.matcher(to.substring(GROUP_PREFIX.length())).matches();
        }
        return E164.matcher(to).matches() || UUID_FORM.matcher(to.toLowerCase(Locale.ROOT)).matches();
    }

    /** The {@code recipients} entry signal-cli-rest-api expects for {@code to}. */
    public static String toRecipient(String to) {
        if (!isAddressable(to)) {
            throw new ChannelConfigurationException(
                    "signal",
                    "Not a Signal recipient: use a number like +4915112345678, a UUID or group:<id>.");
        }
        if (isGroup(to)) {
            String internal = to.substring(GROUP_PREFIX.length());
            return "group."
                    + Base64.getEncoder()
                            .encodeToString(internal.getBytes(StandardCharsets.UTF_8));
        }
        return to;
    }

    /** Whether a group id from the wire is short and clean enough to be stored as an external id. */
    static boolean validGroupInternalId(String id) {
        return id != null && GROUP_INTERNAL_ID.matcher(id).matches();
    }

    /** Last four characters, for logs: never a whole number or id. */
    public static String mask(String value) {
        if (value == null || value.length() < 4) {
            return "***";
        }
        return "***" + value.substring(value.length() - 4);
    }
}
