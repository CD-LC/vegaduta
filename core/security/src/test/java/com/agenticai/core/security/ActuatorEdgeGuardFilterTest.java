package com.agenticai.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Production served /actuator/prometheus to the internet because the only thing keeping it private
 * was a Caddy rule that did not exist on the new host. These tests pin the in-app replacement: a
 * request that came through ANY proxy cannot read a non-public actuator path; the in-network scrape
 * and the health probes are untouched.
 */
class ActuatorEdgeGuardFilterTest {

    private final ActuatorEdgeGuardFilter filter = new ActuatorEdgeGuardFilter("", false);

    private static MockFilterChain run(
            ActuatorEdgeGuardFilter f,
            MockHttpServletRequest request,
            MockHttpServletResponse response)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(request, response, chain);
        return chain;
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    @Test
    void aScrapeThatCameThroughCloudflareOrTheIngressGetsA404() throws Exception {
        for (String header :
                new String[] {
                    "X-Forwarded-For", "CF-Connecting-IP", "CF-Ray", "X-Envoy-External-Address"
                }) {
            MockHttpServletRequest request = get("/actuator/prometheus");
            request.addHeader(header, "203.0.113.9");
            MockHttpServletResponse response = new MockHttpServletResponse();

            MockFilterChain chain = run(filter, request, response);

            assertEquals(404, response.getStatus(), header);
            assertNull(chain.getRequest(), header + " must not reach the endpoint");
        }
    }

    @Test
    void theInNetworkPrometheusScrapeStillWorks() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run(filter, get("/actuator/prometheus"), response);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void healthProbesAreNeverBlockedEvenThroughTheEdge() throws Exception {
        for (String path :
                new String[] {"/actuator/health", "/actuator/health/readiness", "/actuator/info"}) {
            MockHttpServletRequest request = get(path);
            request.addHeader("X-Forwarded-For", "203.0.113.9");

            assertNotNull(run(filter, request, new MockHttpServletResponse()).getRequest(), path);
        }
    }

    @Test
    void everyOtherActuatorPathIsCoveredNotJustPrometheus() throws Exception {
        for (String path :
                new String[] {
                    "/actuator", "/actuator/env", "/actuator/heapdump", "/actuator/healthz"
                }) {
            MockHttpServletRequest request = get(path);
            request.addHeader("X-Forwarded-For", "203.0.113.9");
            MockHttpServletResponse response = new MockHttpServletResponse();

            run(filter, request, response);

            assertEquals(404, response.getStatus(), path);
        }
    }

    @Test
    void ordinaryApiTrafficIsNotTouched() throws Exception {
        MockHttpServletRequest request = get("/api/agents");
        request.addHeader("X-Forwarded-For", "203.0.113.9");

        assertNotNull(run(filter, request, new MockHttpServletResponse()).getRequest());
    }

    @Test
    void encodedCaseFoldedAndNonNormalisedActuatorPathsAreStillGuarded() throws Exception {
        // SEC-042: the guard used to compare the raw request URI, so every one of these skipped it.
        for (String path :
                new String[] {
                    "/%61ctuator/prometheus",
                    "/%41CTUATOR/env",
                    "//actuator/prometheus",
                    "/./actuator/prometheus",
                    "/actuator;jsessionid=x/prometheus",
                    "/actuator/health/..;/prometheus",
                    "/actuator/health/../env",
                    "/ACTUATOR/prometheus",
                    "/Actuator/heapdump",
                    "/x/../actuator/prometheus",
                    "\\actuator\\prometheus",
                }) {
            MockHttpServletRequest request = get(path);
            request.addHeader("X-Forwarded-For", "203.0.113.9");
            MockHttpServletResponse response = new MockHttpServletResponse();

            MockFilterChain chain = run(filter, request, response);

            assertEquals(404, response.getStatus(), path);
            assertNull(chain.getRequest(), path + " must not reach the endpoint");
        }
    }

    @Test
    void doubleEncodedActuatorPathsAreRefusedNotDecodedTwiceAndRouted() throws Exception {
        for (String path :
                new String[] {
                    "/%2561ctuator/prometheus",
                    "/%252561ctuator/prometheus",
                    "/actuator%252Fprometheus",
                    "/api/..%252Factuator/prometheus",
                    "/%zzactuator/prometheus",
                }) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            // No forwarding header at all: a malformed actuator path is refused even in-network.
            MockFilterChain chain = run(filter, get(path), response);

            assertEquals(400, response.getStatus(), path);
            assertNull(chain.getRequest(), path);
        }
    }

    @Test
    void publicProbesStillMatchAfterNormalisationAndOrdinaryEncodedPathsAreUntouched()
            throws Exception {
        assertEquals(
                ActuatorEdgeGuardFilter.PathClass.PUBLIC_ACTUATOR,
                ActuatorEdgeGuardFilter.classify("//actuator/health/readiness"));
        assertEquals(
                ActuatorEdgeGuardFilter.PathClass.GUARDED,
                ActuatorEdgeGuardFilter.classify("/actuator/health/../prometheus"));
        assertEquals(
                ActuatorEdgeGuardFilter.PathClass.NOT_ACTUATOR,
                ActuatorEdgeGuardFilter.classify("/api/files/report%20Q3.pdf"));
        assertEquals(
                ActuatorEdgeGuardFilter.PathClass.NOT_ACTUATOR,
                ActuatorEdgeGuardFilter.classify("/api/files/a%252Fb"));
    }

    @Test
    void aConfiguredScrapeTokenLetsAScraperThroughTheEdgeAndAWrongOneDoesNot() throws Exception {
        ActuatorEdgeGuardFilter withToken = new ActuatorEdgeGuardFilter("s3cret-scrape", false);

        MockHttpServletRequest good = get("/actuator/prometheus");
        good.addHeader("X-Forwarded-For", "203.0.113.9");
        good.addHeader("Authorization", "Bearer s3cret-scrape");
        assertNotNull(run(withToken, good, new MockHttpServletResponse()).getRequest());

        MockHttpServletRequest bad = get("/actuator/prometheus");
        bad.addHeader("X-Forwarded-For", "203.0.113.9");
        bad.addHeader("Authorization", "Bearer nope");
        MockHttpServletResponse response = new MockHttpServletResponse();
        run(withToken, bad, response);
        assertEquals(404, response.getStatus());
    }

    @Test
    void whenTheTokenIsRequiredADirectRequestWithoutItGetsA404ButProbesStayOpen() throws Exception {
        ActuatorEdgeGuardFilter strict = new ActuatorEdgeGuardFilter("s3cret-scrape", true);

        // A core published straight onto a LAN (Lite / Personal with BIND_ADDR=0.0.0.0): no proxy,
        // so
        // no forwarding header - the edge test alone would let this through.
        MockHttpServletResponse denied = new MockHttpServletResponse();
        MockFilterChain deniedChain = run(strict, get("/actuator/prometheus"), denied);
        assertEquals(404, denied.getStatus());
        assertNull(deniedChain.getRequest());

        MockHttpServletRequest wrong = get("/actuator/prometheus");
        wrong.addHeader("Authorization", "Bearer nope");
        MockHttpServletResponse wrongResponse = new MockHttpServletResponse();
        run(strict, wrong, wrongResponse);
        assertEquals(404, wrongResponse.getStatus());

        for (String probe :
                new String[] {"/actuator/health", "/actuator/health/readiness", "/actuator/info"}) {
            assertNotNull(
                    run(strict, get(probe), new MockHttpServletResponse()).getRequest(), probe);
        }
    }

    @Test
    void whenTheTokenIsRequiredAScraperWithTheTokenGetsInAndTheTokenIsHiddenFromTheJwtDecoder()
            throws Exception {
        ActuatorEdgeGuardFilter strict = new ActuatorEdgeGuardFilter("s3cret-scrape", true);
        MockHttpServletRequest request = get("/actuator/prometheus");
        request.addHeader("Authorization", "Bearer s3cret-scrape");
        request.addHeader("Accept", "text/plain");

        MockFilterChain chain = run(strict, request, new MockHttpServletResponse());

        jakarta.servlet.http.HttpServletRequest seen =
                (jakarta.servlet.http.HttpServletRequest) chain.getRequest();
        assertNotNull(seen);
        assertNull(
                seen.getHeader("Authorization"),
                "a shared secret must not reach the bearer-token filter");
        assertEquals("text/plain", seen.getHeader("Accept"));
        assertEquals(
                java.util.List.of("Accept"), java.util.Collections.list(seen.getHeaderNames()));
    }

    @Test
    void whenTheTokenIsRequiredButNoneIsConfiguredNoGuardedPathIsReachable() throws Exception {
        ActuatorEdgeGuardFilter strict = new ActuatorEdgeGuardFilter("", true);
        MockHttpServletRequest request = get("/actuator/prometheus");
        request.addHeader("Authorization", "Bearer ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertNull(run(strict, request, response).getRequest());
        assertEquals(404, response.getStatus());
    }

    @Test
    void byDefaultADirectScrapeStillWorksSoTheFullStacksPrometheusKeepsScraping() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertNotNull(run(filter, get("/actuator/prometheus"), response).getRequest());
        assertEquals(200, response.getStatus());
    }
}
