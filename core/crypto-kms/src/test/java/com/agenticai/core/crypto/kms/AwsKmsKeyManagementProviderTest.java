package com.agenticai.core.crypto.kms;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EnvelopeEncryptionService;
import com.agenticai.core.crypto.WrappedDataKey;
import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;
import software.amazon.awssdk.services.kms.model.KmsException;

class AwsKmsKeyManagementProviderTest {

    private static final String KEY = "arn:aws:kms:eu-west-1:111122223333:key/test-key-a";

    /** A KmsClient fake whose "KMS" masks the data key with one byte per key id. */
    private static KmsClient fakeKms() {
        KmsClient kms = mock(KmsClient.class);
        when(kms.generateDataKey(any(GenerateDataKeyRequest.class)))
                .thenAnswer(
                        inv -> {
                            GenerateDataKeyRequest req = inv.getArgument(0);
                            byte[] dek = new byte[32];
                            new SecureRandom().nextBytes(dek);
                            return GenerateDataKeyResponse.builder()
                                    .plaintext(SdkBytes.fromByteArray(dek))
                                    .ciphertextBlob(SdkBytes.fromByteArray(mask(dek, req.keyId())))
                                    .build();
                        });
        when(kms.decrypt(any(DecryptRequest.class)))
                .thenAnswer(
                        inv -> {
                            DecryptRequest req = inv.getArgument(0);
                            return DecryptResponse.builder()
                                    .plaintext(
                                            SdkBytes.fromByteArray(
                                                    mask(
                                                            req.ciphertextBlob().asByteArray(),
                                                            req.keyId())))
                                    .build();
                        });
        return kms;
    }

    private static byte[] mask(byte[] in, String keyId) {
        byte m = (byte) keyId.charAt(keyId.length() - 1);
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = (byte) (in[i] ^ m);
        }
        return out;
    }

    @Test
    void roundTripThroughEnvelopeServiceAndRequestShape() {
        KmsClient kms = fakeKms();
        AwsKmsKeyManagementProvider provider = new AwsKmsKeyManagementProvider(KEY, kms);
        EnvelopeEncryptionService service = new EnvelopeEncryptionService(provider);

        String envelope = service.encrypt("sk-test-secret");
        assertEquals("sk-test-secret", service.decrypt(envelope));
        assertEquals("aws:" + KEY, provider.providerId());

        ArgumentCaptor<GenerateDataKeyRequest> gen =
                ArgumentCaptor.forClass(GenerateDataKeyRequest.class);
        verify(kms).generateDataKey(gen.capture());
        assertEquals(KEY, gen.getValue().keyId());
        assertEquals(DataKeySpec.AES_256, gen.getValue().keySpec());
        assertEquals(
                AwsKmsKeyManagementProvider.ENCRYPTION_CONTEXT, gen.getValue().encryptionContext());
        ArgumentCaptor<DecryptRequest> dec = ArgumentCaptor.forClass(DecryptRequest.class);
        verify(kms).decrypt(dec.capture());
        assertEquals(KEY, dec.getValue().keyId());
        assertEquals(
                AwsKmsKeyManagementProvider.ENCRYPTION_CONTEXT, dec.getValue().encryptionContext());
    }

    @Test
    void wrappedKeyIsNotThePlaintextKey() {
        AwsKmsKeyManagementProvider provider = new AwsKmsKeyManagementProvider(KEY, fakeKms());
        WrappedDataKey k = provider.generateDataKey();
        assertEquals(32, k.plaintextKey().length);
        assertFalse(Arrays.equals(k.plaintextKey(), k.wrappedKey()));
        assertArrayEquals(k.plaintextKey(), provider.unwrapDataKey(k.wrappedKey()));
    }

    @Test
    void envelopeWrappedUnderAnotherKeyIsRefusedByKeyId() {
        KmsClient kms = fakeKms();
        String envelope =
                new EnvelopeEncryptionService(new AwsKmsKeyManagementProvider(KEY, kms))
                        .encrypt("v");
        EnvelopeEncryptionService other =
                new EnvelopeEncryptionService(
                        new AwsKmsKeyManagementProvider(
                                "arn:aws:kms:eu-west-1:111122223333:key/test-key-b", kms));

        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> other.decrypt(envelope));
        assertTrue(e.getMessage().contains("aws:" + KEY), e.getMessage());
        assertTrue(e.getMessage().contains("test-key-b"), e.getMessage());
    }

    @Test
    void serviceFailuresSurfaceAsIllegalStateWithoutKeyMaterial() {
        KmsClient kms = mock(KmsClient.class);
        when(kms.generateDataKey(any(GenerateDataKeyRequest.class)))
                .thenThrow(KmsException.builder().message("AccessDeniedException").build());
        when(kms.decrypt(any(DecryptRequest.class)))
                .thenThrow(SdkClientException.create("Unable to load credentials"));
        AwsKmsKeyManagementProvider provider = new AwsKmsKeyManagementProvider(KEY, kms);

        IllegalStateException gen =
                assertThrows(IllegalStateException.class, provider::generateDataKey);
        assertTrue(gen.getMessage().contains("GenerateDataKey failed"));
        assertTrue(gen.getMessage().contains("AccessDeniedException"));
        IllegalStateException dec =
                assertThrows(
                        IllegalStateException.class,
                        () -> provider.unwrapDataKey(new byte[] {1, 2}));
        assertTrue(dec.getMessage().contains("Decrypt failed"));
    }

    @Test
    void keyIdIsRequired() {
        assertThrows(
                IllegalStateException.class, () -> new AwsKmsKeyManagementProvider(" ", fakeKms()));
    }
}
