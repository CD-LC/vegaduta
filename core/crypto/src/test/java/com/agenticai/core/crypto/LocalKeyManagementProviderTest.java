package com.agenticai.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class LocalKeyManagementProviderTest {

    private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private LocalKeyManagementProvider newProvider(String key) {
        LocalKeyManagementProvider provider = new LocalKeyManagementProvider(key);
        provider.init();
        return provider;
    }

    @Test
    void generateDataKeyThenUnwrapReturnsTheSamePlaintextKey() {
        LocalKeyManagementProvider provider = newProvider(TEST_KEY);

        WrappedDataKey dataKey = provider.generateDataKey();
        byte[] unwrapped = provider.unwrapDataKey(dataKey.wrappedKey());

        assertArrayEquals(dataKey.plaintextKey(), unwrapped);
        assertEquals(LocalKeyManagementProvider.PROVIDER_ID, dataKey.providerId());
    }

    @Test
    void eachGeneratedDataKeyIsUnique() {
        LocalKeyManagementProvider provider = newProvider(TEST_KEY);

        WrappedDataKey first = provider.generateDataKey();
        WrappedDataKey second = provider.generateDataKey();

        assertFalse(Arrays.equals(first.plaintextKey(), second.plaintextKey()));
        assertFalse(Arrays.equals(first.wrappedKey(), second.wrappedKey()));
    }

    @Test
    void missingKeyFailsFastAtStartup() {
        LocalKeyManagementProvider provider = new LocalKeyManagementProvider("");
        assertThrows(IllegalStateException.class, provider::init);
    }

    @Test
    void wrongLengthKeyFailsFastAtStartup() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]); // AES-128, not AES-256
        LocalKeyManagementProvider provider = new LocalKeyManagementProvider(shortKey);
        assertThrows(IllegalStateException.class, provider::init);
    }
}
