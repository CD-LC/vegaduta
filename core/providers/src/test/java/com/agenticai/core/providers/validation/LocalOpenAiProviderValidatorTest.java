package com.agenticai.core.providers.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.providers.LocalOpenAiCredential;
import com.agenticai.core.providers.LocalOpenAiServerClient;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.net.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link LocalOpenAiProviderValidator} against a real loopback HTTP server standing in for
 * vLLM / llama.cpp / LM Studio. The server is reached the way an operator would allow it: by
 * listing {@code host:port} in {@code app.security.outbound.allowed-private-hosts}.
 */
class LocalOpenAiProviderValidatorTest {

    private static final String HOST = "llm.example.test";

    private HttpServer server;
    private int port;
    private int status = 200;
    private String body = "{\"object\":\"list\",\"data\":[{\"id\":\"qwen3:8b\"}]}";
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/models", exchange -> {
            hits.incrementAndGet();
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static SsrfGuard guard(Set<String> allowList) {
        return SsrfGuard.of(allowList, host -> HOST.equals(host)
            ? new InetAddress[] {InetAddress.getLoopbackAddress()}
            : InetAddress.getAllByName(host));
    }

    private LocalOpenAiProviderValidator validator(Set<String> allowList) {
        return new LocalOpenAiProviderValidator(new LocalOpenAiServerClient(RestClient.builder(), guard(allowList)));
    }

    private String credential(String key) {
        return new LocalOpenAiCredential("http://" + HOST + ":" + port + "/v1", key).encode();
    }

    @Test
    void supportsTheLocalProvider() {
        assertThat(validator(Set.of()).supportedProvider()).isEqualTo(ProviderName.LOCAL_OPENAI);
    }

    @Test
    void acceptsAnAllowListedServerThatListsModelsAndSendsNoAuthWithoutAKey() {
        assertThatCode(() -> validator(Set.of(HOST + ":" + port)).validate(credential(null))).doesNotThrowAnyException();

        assertThat(authorizations).containsExactly("null");
    }

    @Test
    void sendsTheOptionalKeyAsBearer() {
        assertThatCode(() -> validator(Set.of(HOST + ":" + port)).validate(credential("sk-local"))).doesNotThrowAnyException();

        assertThat(authorizations).containsExactly("Bearer sk-local");
    }

    @Test
    void anEmptyModelListIsStillAServer() {
        body = "{\"object\":\"list\",\"data\":[]}";

        assertThatCode(() -> validator(Set.of(HOST + ":" + port)).validate(credential(null))).doesNotThrowAnyException();
    }

    @Test
    void refusesAServerThatIsNotAllowListedAndSaysWhichEntryToAdd() {
        assertThatThrownBy(() -> validator(Set.of()).validate(credential(null)))
            .isInstanceOf(ProviderValidationException.class)
            .hasMessageContaining("not allowed")
            .hasMessageContaining("app.security.outbound.allowed-private-hosts")
            .hasMessageContaining(HOST + ":" + port);
        assertThat(hits.get()).isZero();
    }

    @Test
    void anAllowListEntryForAnotherPortDoesNotOpenThisOne() {
        assertThatThrownBy(() -> validator(Set.of(HOST + ":" + (port + 1))).validate(credential(null)))
            .isInstanceOf(ProviderValidationException.class)
            .hasMessageContaining("not allowed");
        assertThat(hits.get()).isZero();
    }

    @Test
    void localhostRefusalMentionsHostDockerInternal() {
        String cred = new LocalOpenAiCredential("http://localhost:8000/v1", null).encode();

        assertThatThrownBy(() -> validator(Set.of()).validate(cred))
            .isInstanceOf(ProviderValidationException.class)
            .hasMessageContaining("host.docker.internal")
            .hasMessageContaining("localhost:8000");
    }

    @Test
    void hostDockerInternalWorksOnceTheOperatorAllowListsIt() {
        // Name rule refuses it by default (.internal / host.docker.internal) ...
        String cred = new LocalOpenAiCredential("http://host.docker.internal:8000/v1", null).encode();
        assertThatThrownBy(() -> validator(Set.of()).validate(cred))
            .isInstanceOf(ProviderValidationException.class)
            .hasMessageContaining("host.docker.internal:8000");
        // ... and the exact entry it names is accepted by the guard (the connection then fails on the
        // unresolvable name, which is a reachability error, not an SSRF refusal).
        assertThatThrownBy(() -> validator(Set.of("host.docker.internal:8000")).validate(cred))
            .isInstanceOf(ProviderValidationException.class)
            .hasMessageNotContaining("not allowed");
    }

    @Test
    void refusesCloudMetadataAndInternalComposeNamesEvenWhenAnotherHostIsAllowListed() {
        for (String target : new String[] {"http://169.254.169.254/v1", "http://keycloak:8080/v1", "http://core:8080/v1"}) {
            String cred = new LocalOpenAiCredential(target, null).encode();
            assertThatThrownBy(() -> validator(Set.of(HOST + ":" + port)).validate(cred))
                .as(target)
                .isInstanceOf(ProviderValidationException.class)
                .hasMessageContaining("not allowed");
        }
    }

    @Test
    void rejectedKeyIsReportedAsAuthProblemWithoutEchoingTheUpstreamBody() {
        status = 401;
        body = "{\"error\":\"secret upstream detail\"}";

        assertThatThrownBy(() -> validator(Set.of(HOST + ":" + port)).validate(credential(null)))
            .isInstanceOf(ProviderValidationException.class)
            .hasMessageContaining("401")
            .hasMessageContaining("requires an API key")
            .hasMessageNotContaining("secret upstream detail");
    }

    @Test
    void aWrongKeyIsDistinguishedFromAMissingOne() {
        status = 403;

        assertThatThrownBy(() -> validator(Set.of(HOST + ":" + port)).validate(credential("bad")))
            .isInstanceOf(ProviderValidationException.class)
            .hasMessageContaining("403")
            .hasMessageContaining("Check the API key");
    }

    @Test
    void aWebPageAtThatAddressIsNotAnOpenAiServer() {
        body = "<html><body>Welcome</body></html>";

        assertThatThrownBy(() -> validator(Set.of(HOST + ":" + port)).validate(credential(null)))
            .isInstanceOf(ProviderValidationException.class)
            .hasMessageContaining("not like an OpenAI-compatible server");
    }

    @Test
    void aServerErrorIsReportedWithItsStatusOnly() {
        status = 500;
        body = "{\"error\":\"stack trace here\"}";

        assertThatThrownBy(() -> validator(Set.of(HOST + ":" + port)).validate(credential(null)))
            .isInstanceOf(ProviderValidationException.class)
            .hasMessageContaining("HTTP 500")
            .hasMessageNotContaining("stack trace");
    }

    @Test
    void unreachableServerErrorCarriesNoTransportDetail() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        String cred = new LocalOpenAiCredential("http://" + HOST + ":" + closedPort + "/v1", null).encode();

        assertThatThrownBy(() -> validator(Set.of(HOST + ":" + closedPort)).validate(cred))
            .isInstanceOf(ProviderValidationException.class)
            .hasMessageContaining("Could not reach")
            .hasMessageNotContaining("refused")
            .hasMessageNotContaining("Connect")
            .hasMessageNotContaining("timed out");
    }

    @Test
    void doesNotFollowARedirectOutOfTheAllowedHost() {
        AtomicInteger elsewhere = new AtomicInteger();
        server.removeContext("/v1/models");
        server.createContext("/v1/models", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/secret", exchange -> {
            elsewhere.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> validator(Set.of(HOST + ":" + port)).validate(credential("sk-local")))
            .isInstanceOf(ProviderValidationException.class);
        assertThat(elsewhere.get()).isZero();
    }

    @Test
    void aMalformedCredentialBlobIsReportedAsAFormProblem() {
        assertThatThrownBy(() -> validator(Set.of()).validate("not json"))
            .isInstanceOf(ProviderValidationException.class)
            .hasMessageContaining("activation form");
    }

    @Test
    void aBlankBaseUrlIsRequired() {
        String cred = new LocalOpenAiCredential("  ", null).encode();

        assertThatThrownBy(() -> validator(Set.of()).validate(cred))
            .isInstanceOf(ProviderValidationException.class)
            .hasMessageContaining("base URL is required");
    }

    @Test
    void documentedPresetUrlsAreAllNormalisedToTheModelsEndpoint() {
        Map<String, String> presets = Map.of(
            "http://localhost:8000/v1", "http://localhost:8000/v1/models",
            "http://localhost:8080/v1", "http://localhost:8080/v1/models",
            "http://localhost:1234/v1", "http://localhost:1234/v1/models",
            "http://localhost:1337/v1", "http://localhost:1337/v1/models",
            "http://localhost:8080", "http://localhost:8080/v1/models");
        presets.forEach((in, out) -> assertThat(new LocalOpenAiCredential(in, null).modelsUrl()).isEqualTo(out));
    }
}
