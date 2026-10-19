package com.agenticai.core.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnvelopeProviderRoutingTest {

    private static final String LOCAL_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    /** A second provider with its own id that XOR-masks the data key. */
    private static final class MaskProvider implements KeyManagementProvider {
        private final String id;
        private final byte mask;

        MaskProvider(String id, int mask) {
            this.id = id;
            this.mask = (byte) mask;
        }

        @Override
        public WrappedDataKey generateDataKey() {
            byte[] dek = new byte[32];
            Arrays.fill(dek, (byte) 7);
            return new WrappedDataKey(dek, unwrapDataKey(dek), id);
        }

        @Override
        public byte[] unwrapDataKey(byte[] wrapped) {
            byte[] out = new byte[wrapped.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) (wrapped[i] ^ mask);
            }
            return out;
        }

        @Override
        public String providerId() {
            return id;
        }
    }

    private static LocalKeyManagementProvider local() {
        LocalKeyManagementProvider p = new LocalKeyManagementProvider(LOCAL_KEY);
        p.init();
        return p;
    }

    @Test
    void newDataUsesPrimaryAndOldLocalEnvelopesStillDecrypt() {
        LocalKeyManagementProvider local = local();
        MaskProvider cloud = new MaskProvider("fake:key-1", 0x21);
        String oldEnvelope = new EnvelopeEncryptionService(local).encrypt("before-the-switch");

        EnvelopeEncryptionService switched =
                new EnvelopeEncryptionService(cloud, List.of(local, cloud));

        assertEquals("before-the-switch", switched.decrypt(oldEnvelope));
        String fresh = switched.encrypt("after-the-switch");
        assertEquals("after-the-switch", switched.decrypt(fresh));
        // The fresh envelope records the cloud provider id: local alone cannot open it.
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> new EnvelopeEncryptionService(local).decrypt(fresh));
        assertTrue(e.getMessage().contains("fake:key-1"), e.getMessage());
        assertTrue(e.getMessage().contains("[local]"), e.getMessage());
    }

    @Test
    void twoBeansWithTheSameIdAreRejected() {
        MaskProvider a = new MaskProvider("fake:key-1", 1);
        MaskProvider b = new MaskProvider("fake:key-1", 2);
        assertThrows(
                IllegalStateException.class, () -> new EnvelopeEncryptionService(a, List.of(a, b)));
    }

    @Test
    void selectionCheckAcceptsMatchingProviderOnly() {
        KmsProviderSelectionCheck.verify("local", "local");
        KmsProviderSelectionCheck.verify("aws", "aws:arn:aws:kms:r:1:key/k");
        assertThrows(
                IllegalStateException.class,
                () -> KmsProviderSelectionCheck.verify("aws", "local"));
        assertThrows(
                IllegalStateException.class,
                () -> KmsProviderSelectionCheck.verify("local", "aws:k"));
        assertThrows(
                IllegalStateException.class,
                () -> KmsProviderSelectionCheck.verify("gpc", "local"));
        KmsProviderSelectionCheck.verify("AWS", "aws:k");
        KmsProviderSelectionCheck.verify("", "local");
        assertThrows(
                IllegalStateException.class,
                () -> KmsProviderSelectionCheck.verify("AWS", "local"));
    }
}
