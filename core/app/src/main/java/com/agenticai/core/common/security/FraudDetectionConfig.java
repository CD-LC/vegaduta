package com.agenticai.core.common.security;

import com.agenticai.core.resilience.OutboundHttpTimeouts;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring configuration for fraud detection and abuse prevention.
 *
 * <p><b>Off by default</b> ({@value #ENABLED_PROPERTY}{@code =false}, JAVA-005).
 * The subsystem was registered on {@code /**} but never took effect: it read a
 * {@code tenantId} request attribute nothing sets (so every request logged a
 * WARN and was allowed), its writes ran inside a read-only transaction, and
 * several detectors were placeholders. Those defects are fixed (tenant from
 * {@code TenantContext}, writes in {@link AbuseEventRecorder} with their own
 * transaction), but its rate buckets are still per-pod memory and it has not
 * been proven in production, so an operator must opt in. With the property
 * unset none of the fraud beans - interceptor, {@link FraudAnalysisController},
 * {@link AbuseDetectionService}, hCaptcha client - exist.
 *
 * <p>Registers (when enabled):
 * <ul>
 *   <li>{@link FraudDetectionInterceptor} on {@code /**} (minus actuator/static)
 *   <li>{@link HCaptchaClient} with its own timeout-bounded {@link RestClient}
 *       (not exposed as a bean: a generically named {@code RestClient} bean
 *       would be picked up by any unqualified injection - JAVA-034)
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = FraudDetectionConfig.ENABLED_PROPERTY, havingValue = "true")
@EnableConfigurationProperties(HCaptchaClient.HCaptchaConfig.class)
public class FraudDetectionConfig implements WebMvcConfigurer {

    public static final String ENABLED_PROPERTY = "app.fraud-detection.enabled";

    static final Duration HCAPTCHA_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration HCAPTCHA_READ_TIMEOUT = Duration.ofSeconds(5);

    // ObjectProvider (lazy): the interceptor -> AbuseDetectionService -> HCaptchaClient,
    // which is a @Bean on THIS class - constructor injection is a hard cycle.
    private final ObjectProvider<FraudDetectionInterceptor> fraudDetectionInterceptor;

    public FraudDetectionConfig(ObjectProvider<FraudDetectionInterceptor> fraudDetectionInterceptor) {
        this.fraudDetectionInterceptor = fraudDetectionInterceptor;
    }

    /**
     * Register the fraud detection interceptor to run on all requests.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(fraudDetectionInterceptor.getObject())
                .addPathPatterns("/**")
                .excludePathPatterns(
                    "/actuator/**",
                    "/health/**",
                    "/static/**",
                    "/assets/**",
                    "/error",
                    // Exclude non-API endpoints that don't need fraud checks
                    "/v3/api-docs/**",
                    "/swagger-ui/**"
                )
                .order(1); // Run early in the interceptor chain
    }

    /**
     * The hCaptcha client, with a private RestClient built from Boot's
     * builder (so global customizers apply) plus explicit connect/read
     * timeouts - {@code RestClient.create()} had none, so a hung hCaptcha
     * endpoint pinned the request thread indefinitely.
     */
    @Bean
    public HCaptchaClient hCaptchaClient(
        HCaptchaClient.HCaptchaConfig config,
        ObjectProvider<RestClient.Builder> restClientBuilder,
        Environment env
    ) {
        // Tunable: app.http.hcaptcha.connect-timeout / app.http.hcaptcha.read-timeout; a missing,
        // zero or negative value falls back to the constants above, so the bound can't be disabled.
        OutboundHttpTimeouts timeouts = OutboundHttpTimeouts.resolve(
            env, "hcaptcha", HCAPTCHA_CONNECT_TIMEOUT, HCAPTCHA_READ_TIMEOUT);
        return new HCaptchaClient(config,
            hcaptchaRestClient(restClientBuilder.getIfAvailable(RestClient::builder), timeouts));
    }

    static RestClient hcaptchaRestClient(RestClient.Builder builder, OutboundHttpTimeouts timeouts) {
        return builder.requestFactory(timeouts.requestFactory()).build();
    }
}
