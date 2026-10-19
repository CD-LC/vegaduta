package com.agenticai.core.providers.validation;

import com.agenticai.core.providers.AwsBedrockCredential;
import com.agenticai.core.providers.ProviderName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.BedrockException;

/**
 * Validates an AWS Bedrock connection by decoding the {@link
 * AwsBedrockCredential} JSON blob and calling Bedrock's control-plane {@code
 * ListFoundationModels} - a free, non-billed, read-only call (unlike a
 * Converse/InvokeModel call, which is metered) that confirms the access
 * key/secret/region combination actually authenticates against Bedrock and
 * has at least list-level IAM permission, the same "lightweight, non-billed
 * probe" contract {@link ProviderValidator}'s javadoc calls for.
 *
 * <p>Uses the {@code software.amazon.awssdk:bedrock} control-plane client
 * (not {@code bedrockruntime}, which {@code AwsBedrockAgentChatClientFactory}
 * uses for the actual per-call {@code Converse} inference) - Bedrock splits
 * its SDK into a management surface and a runtime/inference surface, and
 * {@code ListFoundationModels} only exists on the former.
 */
@Component
public class AwsBedrockProviderValidator implements ProviderValidator {

    private static final Logger log = LoggerFactory.getLogger(AwsBedrockProviderValidator.class);

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.AWS_BEDROCK;
    }

    @Override
    public void validate(String apiKey) {
        AwsBedrockCredential credential;
        try {
            credential = AwsBedrockCredential.decode(apiKey);
        } catch (IllegalStateException e) {
            throw new ProviderValidationException(
                "Could not read the AWS Bedrock connection details - this field should be filled in via the "
                    + "activation form, not pasted by hand.", e);
        }

        if (credential.accessKeyId() == null || credential.accessKeyId().isBlank()
            || credential.secretAccessKey() == null || credential.secretAccessKey().isBlank()) {
            throw new ProviderValidationException("An AWS access key ID and secret access key are both required.");
        }
        if (credential.region() == null || credential.region().isBlank()) {
            throw new ProviderValidationException("An AWS region is required (e.g. us-east-1).");
        }

        try (BedrockClient client = BedrockClient.builder()
            .region(Region.of(credential.region()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(credential.accessKeyId(), credential.secretAccessKey())))
            .build()) {
            client.listFoundationModels(b -> {});
        } catch (BedrockException e) {
            int status = e.statusCode();
            String message = e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage();
            if (status == 401 || status == 403) {
                throw new ProviderValidationException(
                    "AWS rejected these Bedrock credentials (HTTP " + status + "): " + message
                        + " - check the access key/secret and that the IAM identity has Bedrock permissions "
                        + "(e.g. the AmazonBedrockReadOnly or AmazonBedrockFullAccess policy).");
            }
            log.warn("AWS Bedrock validation call returned unexpected status {}", status);
            throw new ProviderValidationException(
                "Could not validate these credentials against AWS Bedrock right now (HTTP " + status + "): "
                    + message + ". Try again in a moment.");
        } catch (SdkClientException e) {
            log.warn("AWS Bedrock validation call failed for region {}", credential.region(), e);
            throw new ProviderValidationException(
                "Could not reach AWS Bedrock in region \"" + credential.region() + "\": " + e.getMessage()
                    + " - check the region is a valid AWS region where Bedrock is available.", e);
        }
    }
}
