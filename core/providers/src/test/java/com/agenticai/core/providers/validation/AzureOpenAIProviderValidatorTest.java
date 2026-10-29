package com.agenticai.core.providers.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.providers.AzureOpenAiCredential;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link AzureOpenAIProviderValidator} pins its own SSRF-guarded request
 * factory (see {@code OllamaProviderValidatorTest}'s javadoc for why a bound
 * {@code MockRestServiceServer} cannot intercept it), so a loopback {@link
 * HttpServer} plays the Azure data plane here: the probe is {@code GET
 * {endpoint}/openai/deployments?api-version=...} authenticated with the
 * non-standard {@code api-key} header, and the deployment list is then
 * scanned for the configured deployment name.
 */
class AzureOpenAIProviderValidatorTest {

    private HttpServer server;
    private volatile String apiKeyHeaderSeen;
    private volatile int status = 200;
    private volatile String responseJson = "{\"data\":[{\"id\":\"gpt-4o-deploy\"}]}";

    private AzureOpenAIProviderValidator startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
            "/openai/deployments",
            exchange -> {
                apiKeyHeaderSeen = exchange.getRequestHeaders().getFirst("api-key");
                byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
        server.start();
        return new AzureOpenAIProviderValidator(RestClient.builder(), allowingGuard(port()));
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private static SsrfGuard allowingGuard(int port) {
        return SsrfGuard.of(
            Set.of("127.0.0.1:" + port),
            host -> new InetAddress[] {InetAddress.getLoopbackAddress()});
    }

    private String endpoint() {
        return "http://127.0.0.1:" + port();
    }

    private String credential(String endpoint, String key, String deployment) {
        return new AzureOpenAiCredential(endpoint, key, deployment, null).encode();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void supportedProviderIsAzureOpenAi() {
        assertEquals(
            ProviderName.AZURE_OPENAI,
            new AzureOpenAIProviderValidator(RestClient.builder()).supportedProvider());
    }

    @Test
    void aHandPastedBlobIsRejectedBeforeAnyNetworkCall() {
        AzureOpenAIProviderValidator validator = new AzureOpenAIProviderValidator(RestClient.builder());
        ProviderValidationException e =
            assertThrows(ProviderValidationException.class, () -> validator.validate("not-json"));
        assertTrue(e.getMessage().contains("Could not read the Azure OpenAI connection details"), e.getMessage());
    }

    @Test
    void eachBlankFieldIsRejectedWithItsOwnMessage() {
        AzureOpenAIProviderValidator validator = new AzureOpenAIProviderValidator(RestClient.builder());
        ProviderValidationException noEndpoint = assertThrows(
            ProviderValidationException.class,
            () -> validator.validate(credential(null, "k", "d")));
        assertTrue(noEndpoint.getMessage().contains("endpoint is required"), noEndpoint.getMessage());
        ProviderValidationException noKey = assertThrows(
            ProviderValidationException.class,
            () -> validator.validate(credential("https://res.openai.azure.com", " ", "d")));
        assertTrue(noKey.getMessage().contains("API key is required"), noKey.getMessage());
        ProviderValidationException noDeployment = assertThrows(
            ProviderValidationException.class,
            () -> validator.validate(credential("https://res.openai.azure.com", "k", "")));
        assertTrue(noDeployment.getMessage().contains("deployment name is required"), noDeployment.getMessage());
    }

    @Test
    void aSuccessfulProbeSendsTheApiKeyHeaderAndFindsTheDeployment() throws Exception {
        AzureOpenAIProviderValidator validator = startServer();
        assertDoesNotThrow(() -> validator.validate(credential(endpoint(), "azure-key", "gpt-4o-deploy")));
        assertEquals("azure-key", apiKeyHeaderSeen);
    }

    @Test
    void aValidKeyForAnUnknownDeploymentIsItsOwnFailure() throws Exception {
        AzureOpenAIProviderValidator validator = startServer();
        responseJson = "{\"data\":[{\"id\":\"some-other-deployment\"}]}";
        ProviderValidationException e = assertThrows(
            ProviderValidationException.class,
            () -> validator.validate(credential(endpoint(), "azure-key", "gpt-4o-deploy")));
        assertTrue(e.getMessage().contains("no deployment named \"gpt-4o-deploy\""), e.getMessage());
    }

    @Test
    void anUnrecognizedResponseShapeFailsOpenAfterAuthSucceeded() throws Exception {
        AzureOpenAIProviderValidator validator = startServer();
        responseJson = "{\"unexpected\":true}";
        assertDoesNotThrow(() -> validator.validate(credential(endpoint(), "azure-key", "gpt-4o-deploy")));
    }

    @Test
    void a401IsARejectedKeyWithoutTheUpstreamBodyEchoed() throws Exception {
        AzureOpenAIProviderValidator validator = startServer();
        status = 401;
        responseJson = "{\"error\":\"SECRET-LOOKING-UPSTREAM-DETAIL\"}";
        ProviderValidationException e = assertThrows(
            ProviderValidationException.class,
            () -> validator.validate(credential(endpoint(), "bad", "gpt-4o-deploy")));
        assertTrue(e.getMessage().contains("Azure rejected this API key"), e.getMessage());
        assertTrue(!e.getMessage().contains("SECRET-LOOKING-UPSTREAM-DETAIL"),
            "the upstream body must not be echoed to the tenant: " + e.getMessage());
    }

    @Test
    void a404PointsAtTheEndpointAndApiVersion() throws Exception {
        AzureOpenAIProviderValidator validator = startServer();
        status = 404;
        ProviderValidationException e = assertThrows(
            ProviderValidationException.class,
            () -> validator.validate(credential(endpoint(), "k", "gpt-4o-deploy")));
        assertTrue(e.getMessage().contains("HTTP 404"), e.getMessage());
        assertTrue(e.getMessage().contains(AzureOpenAiCredential.DEFAULT_API_VERSION), e.getMessage());
    }

    @Test
    void anUnreachableEndpointIsWordedAsAReachabilityProblem() throws Exception {
        HttpServer gone = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int deadPort = gone.getAddress().getPort();
        gone.stop(0);
        AzureOpenAIProviderValidator validator =
            new AzureOpenAIProviderValidator(RestClient.builder(), allowingGuard(deadPort));
        ProviderValidationException e = assertThrows(
            ProviderValidationException.class,
            () -> validator.validate(credential("http://127.0.0.1:" + deadPort, "k", "gpt-4o-deploy")));
        assertTrue(e.getMessage().contains("Could not reach Azure OpenAI"), e.getMessage());
    }
}
