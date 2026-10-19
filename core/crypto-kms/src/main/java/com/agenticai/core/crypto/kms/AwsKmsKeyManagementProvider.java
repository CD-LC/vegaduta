package com.agenticai.core.crypto.kms;

import com.agenticai.core.crypto.KeyManagementProvider;
import com.agenticai.core.crypto.WrappedDataKey;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/**
 * AWS KMS {@link KeyManagementProvider} ({@code app.crypto.kms.provider=aws}). One {@code
 * GenerateDataKey} call per new secret (AES-256 data key, returned in plaintext and wrapped under
 * the KMS key) and one {@code Decrypt} call per read. Credentials come from the AWS SDK default
 * chain (EKS IRSA / Pod Identity, ECS or EC2 role, {@code AWS_*} environment): no key is stored.
 *
 * <p>The provider id is {@code aws:<configured key id>}; use the key ARN for a stable id. {@code
 * Decrypt} is called with the same key id and an encryption context, so a wrapped key can only be
 * opened by the key it was wrapped with. IAM needs {@code kms:GenerateDataKey} and {@code
 * kms:Decrypt} on the key.
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.crypto.kms.provider", havingValue = "aws")
public class AwsKmsKeyManagementProvider implements KeyManagementProvider {

    static final String ID_PREFIX = "aws:";
    static final Map<String, String> ENCRYPTION_CONTEXT = Map.of("purpose", "vegaduta-envelope");

    private final String keyId;
    private final KmsClient kms;

    @Autowired
    public AwsKmsKeyManagementProvider(
            @Value("${app.crypto.kms.aws.key-id:}") String keyId,
            @Value("${app.crypto.kms.aws.region:}") String region) {
        this(keyId, buildClient(region));
    }

    AwsKmsKeyManagementProvider(String keyId, KmsClient kms) {
        this.keyId = KmsIds.requireKey("app.crypto.kms.aws.key-id", keyId);
        KmsIds.checkProviderIdLength(ID_PREFIX + this.keyId);
        this.kms = kms;
    }

    private static KmsClient buildClient(String region) {
        KmsClientBuilder builder = KmsClient.builder();
        if (region != null && !region.isBlank()) {
            builder.region(Region.of(region.trim()));
        }
        return builder.build();
    }

    @Override
    public WrappedDataKey generateDataKey() {
        try {
            GenerateDataKeyResponse r =
                    kms.generateDataKey(
                            GenerateDataKeyRequest.builder()
                                    .keyId(keyId)
                                    .keySpec(DataKeySpec.AES_256)
                                    .encryptionContext(ENCRYPTION_CONTEXT)
                                    .build());
            return new WrappedDataKey(
                    r.plaintext().asByteArray(), r.ciphertextBlob().asByteArray(), providerId());
        } catch (SdkException e) {
            throw new IllegalStateException(
                    "AWS KMS GenerateDataKey failed for key '" + keyId + "': " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] unwrapDataKey(byte[] wrappedKey) {
        try {
            return kms.decrypt(
                            DecryptRequest.builder()
                                    .keyId(keyId)
                                    .ciphertextBlob(SdkBytes.fromByteArray(wrappedKey))
                                    .encryptionContext(ENCRYPTION_CONTEXT)
                                    .build())
                    .plaintext()
                    .asByteArray();
        } catch (SdkException e) {
            throw new IllegalStateException(
                    "AWS KMS Decrypt failed for key '" + keyId + "': " + e.getMessage(), e);
        }
    }

    @Override
    public String providerId() {
        return ID_PREFIX + keyId;
    }
}
