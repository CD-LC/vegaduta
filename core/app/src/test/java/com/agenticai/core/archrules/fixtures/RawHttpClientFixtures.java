package com.agenticai.core.archrules.fixtures;

import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfGuard;
import java.net.http.HttpClient;
import java.time.Duration;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.web.client.RestTemplate;

/**
 * One method per raw HTTP client form {@code RawHttpClientBanRulesTest} must reject, plus one that
 * goes through the factory and must pass. Never executed; only read as bytecode.
 */
@SuppressWarnings("all")
public class RawHttpClientFixtures {

    HttpClient jdkDefault() {
        return HttpClient.newHttpClient();
    }

    HttpClient jdkBuilder() {
        return HttpClient.newBuilder().build();
    }

    RestTemplate restTemplate() {
        return new RestTemplate();
    }

    CloseableHttpClient apacheDefault() {
        return HttpClients.createDefault();
    }

    HttpClientBuilder apacheBuilder() {
        return HttpClientBuilder.create();
    }

    CloseableHttpClient allowed(SsrfGuard guard) {
        return SafeHttpClients.create(guard, Duration.ofSeconds(5), Duration.ofSeconds(30), false);
    }
}
