package com.agenticai.core.crypto.kms;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EnvelopeEncryptionService;
import com.agenticai.core.crypto.WrappedDataKey;
import com.azure.core.exception.HttpResponseException;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.UnwrapResult;
import com.azure.security.keyvault.keys.cryptography.models.WrapResult;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AzureKeyVaultKeyManagementProviderTest {

    private static final String KEY = "https://vault-a.vault.azure.net/keys/envelope";
    private static final String VERSION_1 = KEY + "/0123456789abcdef";
    private static final String VERSION_2 = KEY + "/fedcba9876543210";

    private static byte[] mask(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = (byte) (in[i] ^ 0x5A);
        }
        return out;
    }

    /** A "vault" that masks bytes and reports {@code version} as the key version used to wrap. */
    private static CryptographyClient fakeVault(String version) {
        CryptographyClient c = mock(CryptographyClient.class);
        when(c.wrapKey(any(KeyWrapAlgorithm.class), any(byte[].class)))
                .thenAnswer(
                        inv ->
                                new WrapResult(
                                        mask(inv.getArgument(1)), inv.getArgument(0), version));
        when(c.unwrapKey(any(KeyWrapAlgorithm.class), any(byte[].class)))
                .thenAnswer(
                        inv ->
                                new UnwrapResult(
                                        mask(inv.getArgument(1)), inv.getArgument(0), version));
        return c;
    }

    @Test
    void roundTripUsesTheVersionThatWrappedTheKey() {
        CryptographyClient latest = fakeVault(VERSION_1);
        CryptographyClient v1 = fakeVault(VERSION_1);
        @SuppressWarnings("unchecked")
        Function<String, CryptographyClient> clients = mock(Function.class);
        when(clients.apply(KEY)).thenReturn(latest);
        when(clients.apply(VERSION_1)).thenReturn(v1);
        AzureKeyVaultKeyManagementProvider provider =
                new AzureKeyVaultKeyManagementProvider(KEY + "/", "RSA-OAEP-256", clients);
        EnvelopeEncryptionService service = new EnvelopeEncryptionService(provider);

        String envelope = service.encrypt("db-password");
        assertEquals("db-password", service.decrypt(envelope));
        assertEquals("azure:" + KEY, provider.providerId());
        verify(latest).wrapKey(eq(KeyWrapAlgorithm.RSA_OAEP_256), any(byte[].class));
        verify(latest, never()).unwrapKey(any(), any(byte[].class));
        verify(v1).unwrapKey(eq(KeyWrapAlgorithm.RSA_OAEP_256), any(byte[].class));
    }

    @Test
    void unwrapUsesTheOldVersionAfterTheVaultKeyRotated() {
        // Wrap while version 1 is current; later the unversioned key resolves to version 2.
        AzureKeyVaultKeyManagementProvider before =
                new AzureKeyVaultKeyManagementProvider(
                        KEY, "RSA-OAEP-256", id -> fakeVault(VERSION_1));
        WrappedDataKey wrapped = before.generateDataKey();

        CryptographyClient v1 = fakeVault(VERSION_1);
        AzureKeyVaultKeyManagementProvider after =
                new AzureKeyVaultKeyManagementProvider(
                        KEY,
                        "RSA-OAEP-256",
                        id -> id.equals(VERSION_1) ? v1 : fakeVault(VERSION_2));
        assertArrayEquals(wrapped.plaintextKey(), after.unwrapDataKey(wrapped.wrappedKey()));
    }

    @Test
    void keyVersionOutsideTheConfiguredKeyIsRefused() {
        AzureKeyVaultKeyManagementProvider foreign =
                new AzureKeyVaultKeyManagementProvider(
                        "https://vault-evil.vault.azure.net/keys/envelope",
                        "RSA-OAEP-256",
                        id -> fakeVault("https://vault-evil.vault.azure.net/keys/envelope/aaaa"));
        byte[] wrapped = foreign.generateDataKey().wrappedKey();
        AzureKeyVaultKeyManagementProvider provider =
                new AzureKeyVaultKeyManagementProvider(
                        KEY, "RSA-OAEP-256", id -> fakeVault(VERSION_1));

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> provider.unwrapDataKey(wrapped));
        assertTrue(e.getMessage().contains("outside the configured key"), e.getMessage());
    }

    @Test
    void envelopeFromAnotherVaultIsRefusedByProviderId() {
        String envelope =
                new EnvelopeEncryptionService(
                                new AzureKeyVaultKeyManagementProvider(
                                        KEY, "RSA-OAEP-256", id -> fakeVault(VERSION_1)))
                        .encrypt("v");
        EnvelopeEncryptionService other =
                new EnvelopeEncryptionService(
                        new AzureKeyVaultKeyManagementProvider(
                                "https://vault-b.vault.azure.net/keys/envelope",
                                "RSA-OAEP-256",
                                id -> fakeVault(VERSION_1)));
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> other.decrypt(envelope));
        assertTrue(e.getMessage().contains("azure:" + KEY), e.getMessage());
    }

    @Test
    void vaultFailuresSurfaceAsIllegalState() {
        // A well-formed wrapped key, so unwrap really reaches the (failing) vault.
        byte[] wellFormed =
                new AzureKeyVaultKeyManagementProvider(
                                KEY, "RSA-OAEP-256", id -> fakeVault(VERSION_1))
                        .generateDataKey()
                        .wrappedKey();
        CryptographyClient broken = mock(CryptographyClient.class);
        when(broken.wrapKey(any(KeyWrapAlgorithm.class), any(byte[].class)))
                .thenThrow(new HttpResponseException("Forbidden", null));
        when(broken.unwrapKey(any(KeyWrapAlgorithm.class), any(byte[].class)))
                .thenThrow(new HttpResponseException("Forbidden", null));
        AzureKeyVaultKeyManagementProvider provider =
                new AzureKeyVaultKeyManagementProvider(KEY, "RSA-OAEP-256", id -> broken);

        IllegalStateException wrap =
                assertThrows(IllegalStateException.class, provider::generateDataKey);
        assertTrue(wrap.getMessage().contains("wrapKey failed"), wrap.getMessage());

        IllegalStateException unwrap =
                assertThrows(IllegalStateException.class, () -> provider.unwrapDataKey(wellFormed));
        assertTrue(unwrap.getMessage().contains("unwrapKey failed"), unwrap.getMessage());
        verify(broken).unwrapKey(eq(KeyWrapAlgorithm.RSA_OAEP_256), any(byte[].class));
    }

    @Test
    void malformedWrappedKeyIsRejectedBeforeAnyVaultCall() {
        AzureKeyVaultKeyManagementProvider provider =
                new AzureKeyVaultKeyManagementProvider(
                        KEY,
                        "RSA-OAEP-256",
                        id -> {
                            throw new AssertionError("no client expected");
                        });
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class, () -> provider.unwrapDataKey(new byte[] {0}));
        assertTrue(e.getMessage().contains("Malformed"), e.getMessage());
    }

    @Test
    void clientsAreBuiltOncePerKeyAndReused() {
        AtomicInteger built = new AtomicInteger();
        CryptographyClient vault = fakeVault(VERSION_1);
        AzureKeyVaultKeyManagementProvider provider =
                new AzureKeyVaultKeyManagementProvider(
                        KEY,
                        "RSA-OAEP-256",
                        id -> {
                            built.incrementAndGet();
                            return vault;
                        });
        for (int i = 0; i < 5; i++) {
            WrappedDataKey k = provider.generateDataKey();
            provider.unwrapDataKey(k.wrappedKey());
        }
        // one client for the configured key, one for the version it reported
        assertEquals(2, built.get());
    }

    @Test
    void wrapAlgorithmIsRecordedSoChangingTheSettingDoesNotStrandData() {
        WrappedDataKey wrapped =
                new AzureKeyVaultKeyManagementProvider(KEY, "RSA-OAEP", id -> fakeVault(VERSION_1))
                        .generateDataKey();
        CryptographyClient vault = fakeVault(VERSION_1);
        AzureKeyVaultKeyManagementProvider later =
                new AzureKeyVaultKeyManagementProvider(KEY, "RSA-OAEP-256", id -> vault);

        assertArrayEquals(wrapped.plaintextKey(), later.unwrapDataKey(wrapped.wrappedKey()));
        verify(vault).unwrapKey(eq(KeyWrapAlgorithm.RSA_OAEP), any(byte[].class));
        verify(vault, never()).unwrapKey(eq(KeyWrapAlgorithm.RSA_OAEP_256), any(byte[].class));
    }

    @Test
    void unknownAlgorithmSettingFailsStartup() {
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new AzureKeyVaultKeyManagementProvider(
                                        KEY, "RSA-OEAP-256", id -> null));
        assertTrue(e.getMessage().contains("app.crypto.kms.azure.algorithm"), e.getMessage());
    }

    @Test
    void pinnedVersionKeepsTheProviderIdAndAcceptsOtherVersionsOfTheSameKey() {
        AzureKeyVaultKeyManagementProvider pinned =
                new AzureKeyVaultKeyManagementProvider(
                        VERSION_1, "RSA-OAEP-256", id -> fakeVault(VERSION_1));
        AzureKeyVaultKeyManagementProvider unpinned =
                new AzureKeyVaultKeyManagementProvider(
                        KEY, "RSA-OAEP-256", id -> fakeVault(VERSION_2));
        assertEquals("azure:" + KEY, pinned.providerId());
        assertEquals(pinned.providerId(), unpinned.providerId());

        WrappedDataKey wrapped = pinned.generateDataKey();
        assertArrayEquals(wrapped.plaintextKey(), unpinned.unwrapDataKey(wrapped.wrappedKey()));
    }

    @Test
    void keyUrlIsRequired() {
        assertThrows(
                IllegalStateException.class,
                () -> new AzureKeyVaultKeyManagementProvider("", "RSA-OAEP-256", id -> null));
    }
}
