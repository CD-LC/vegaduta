package com.agenticai.core.crypto.kms;

import java.nio.charset.StandardCharsets;

/** Shared validation for the cloud providers' configured key identifiers. */
final class KmsIds {

    /** The envelope format stores the provider id with a one-byte length. */
    private static final int MAX_PROVIDER_ID_BYTES = 255;

    private KmsIds() {}

    static String requireKey(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    property + " must be set when app.crypto.kms.provider selects this provider.");
        }
        return value.trim();
    }

    static void checkProviderIdLength(String providerId) {
        if (providerId.getBytes(StandardCharsets.UTF_8).length > MAX_PROVIDER_ID_BYTES) {
            throw new IllegalStateException(
                    "KMS key identifier is too long (provider id above "
                            + MAX_PROVIDER_ID_BYTES
                            + " bytes).");
        }
    }
}
