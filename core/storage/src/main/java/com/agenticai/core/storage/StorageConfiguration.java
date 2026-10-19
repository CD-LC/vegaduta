package com.agenticai.core.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link StorageProperties} unconditionally.
 *
 * <p>Before {@code app.storage.type} existed, the {@code
 * @EnableConfigurationProperties} on {@link S3FileStorageService} was the
 * only registration - fine while that bean always existed. Now that it is
 * conditional ({@code type=s3}, the default), callers that read the S3
 * settings directly (e.g. {@code LiveKitEgressService}) still need the
 * properties bean when {@code type=local}. Registering the same properties
 * class twice is a no-op, so the default path is unchanged.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {
}
