package com.agenticai.core.providers.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.providers.AwsBedrockCredential;
import com.agenticai.core.providers.ProviderName;
import org.junit.jupiter.api.Test;

/**
 * {@link AwsBedrockProviderValidator} builds its AWS SDK client internally,
 * so only the pre-network branches are unit-testable without touching real
 * AWS endpoints: blob decoding and the required-field checks all run before a
 * {@code BedrockClient} is constructed, and each failure must be worded for
 * the tenant rather than leaking an SDK stack trace.
 */
class AwsBedrockProviderValidatorTest {

    private final AwsBedrockProviderValidator validator = new AwsBedrockProviderValidator();

    @Test
    void supportedProviderIsAwsBedrock() {
        assertEquals(ProviderName.AWS_BEDROCK, validator.supportedProvider());
    }

    @Test
    void aHandPastedBlobIsRejectedBeforeAnyNetworkCall() {
        ProviderValidationException e =
            assertThrows(ProviderValidationException.class, () -> validator.validate("not-json"));
        assertTrue(e.getMessage().contains("Could not read the AWS Bedrock connection details"), e.getMessage());
    }

    @Test
    void aMissingAccessKeyOrSecretIsRejected() {
        String noAccessKey = new AwsBedrockCredential(" ", "secret", "us-east-1").encode();
        ProviderValidationException e1 =
            assertThrows(ProviderValidationException.class, () -> validator.validate(noAccessKey));
        assertTrue(e1.getMessage().contains("access key ID and secret access key are both required"),
            e1.getMessage());

        String noSecret = new AwsBedrockCredential("AKIAEXAMPLE", null, "us-east-1").encode();
        ProviderValidationException e2 =
            assertThrows(ProviderValidationException.class, () -> validator.validate(noSecret));
        assertTrue(e2.getMessage().contains("access key ID and secret access key are both required"),
            e2.getMessage());
    }

    @Test
    void aMissingRegionIsRejected() {
        String cred = new AwsBedrockCredential("AKIAEXAMPLE", "secret", "").encode();
        ProviderValidationException e =
            assertThrows(ProviderValidationException.class, () -> validator.validate(cred));
        assertTrue(e.getMessage().contains("region is required"), e.getMessage());
    }
}
