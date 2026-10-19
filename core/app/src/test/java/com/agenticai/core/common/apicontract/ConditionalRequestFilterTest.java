package com.agenticai.core.common.apicontract;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.common.ErrorResponseFactory;
import com.agenticai.core.security.http.ResourceVersionSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConditionalRequestFilterTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Instant V1 = Instant.parse("2026-10-16T10:00:00.123456Z");

    private final AtomicInteger handled = new AtomicInteger();
    private final ConditionalRequestFilter filter;

    ConditionalRequestFilterTest() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean(
                "agents",
                new ResourceVersionSource() {
                    @Override
                    public String collectionPath() {
                        return "/api/agents";
                    }

                    @Override
                    public Optional<Instant> lastModified(UUID id) {
                        return AGENT.equals(id) ? Optional.of(V1) : Optional.empty();
                    }
                });
        filter =
                new ConditionalRequestFilter(
                        beans.getBeanProvider(ResourceVersionSource.class),
                        new ErrorResponseFactory(true, null));
    }

    private MockHttpServletResponse send(String method, String uri, String ifMatch) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        if (ifMatch != null) {
            req.addHeader("If-Match", ifMatch);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> handled.incrementAndGet());
        return res;
    }

    @Test
    void getCarriesAStrongEtagThatTracksTheVersion() throws Exception {
        String etag = send("GET", "/api/agents/" + AGENT, null).getHeader("ETag");
        assertThat(etag).isEqualTo(ConditionalRequestFilter.etag(V1)).startsWith("\"").endsWith("\"");
        assertThat(ConditionalRequestFilter.etag(V1.plusNanos(1_000))).isNotEqualTo(etag);
    }

    @Test
    void staleIfMatchIs412AndTheHandlerDoesNotRun() throws Exception {
        MockHttpServletResponse res = send("PATCH", "/api/agents/" + AGENT + "/prompt", "\"stale\"");
        assertThat(res.getStatus()).isEqualTo(412);
        assertThat(res.getContentAsString()).contains("precondition-failed");
        assertThat(res.getHeader("ETag")).isEqualTo(ConditionalRequestFilter.etag(V1));
        assertThat(handled).hasValue(0);
    }

    @Test
    void currentOrWildcardIfMatchPasses() throws Exception {
        String current = ConditionalRequestFilter.etag(V1);
        assertThat(send("PATCH", "/api/agents/" + AGENT + "/model", "\"x\", " + current).getStatus()).isEqualTo(200);
        assertThat(send("DELETE", "/api/agents/" + AGENT, "*").getStatus()).isEqualTo(200);
        assertThat(send("PATCH", "/api/agents/" + AGENT + "/model", "W/" + current).getStatus())
                .as("weak tags never match If-Match")
                .isEqualTo(412);
        assertThat(handled).hasValue(2);
    }

    @Test
    void writesWithoutIfMatchUnknownIdsAndSubResourcesPassThrough() throws Exception {
        assertThat(send("PATCH", "/api/agents/" + AGENT + "/prompt", null).getStatus()).isEqualTo(200);
        assertThat(send("PATCH", "/api/agents/" + UUID.randomUUID(), "\"x\"").getStatus()).isEqualTo(200);
        assertThat(send("PUT", "/api/agents/" + AGENT + "/specialties/x", "\"x\"").getStatus()).isEqualTo(200);
        assertThat(send("POST", "/api/agents/" + AGENT + "/chat", "\"x\"").getStatus()).isEqualTo(200);
        assertThat(send("GET", "/api/agents/directory", null).getHeader("ETag")).isNull();
    }

    @Test
    void generatorHelpersAgreeWithTheFilter() {
        List<String> c = List.of("/api/agents", "/api/workflows");
        assertThat(ConditionalRequestFilter.sendsEtag("get", "/api/agents/{agentId}", c)).isTrue();
        assertThat(ConditionalRequestFilter.sendsEtag("get", "/api/agents/{agentId}/versions", c)).isFalse();
        assertThat(ConditionalRequestFilter.honoursIfMatch("patch", "/api/agents/{agentId}/prompt", c)).isTrue();
        assertThat(ConditionalRequestFilter.honoursIfMatch("put", "/api/workflows/{workflowId}", c)).isTrue();
        assertThat(ConditionalRequestFilter.honoursIfMatch("post", "/api/workflows/{workflowId}/run", c)).isFalse();
        assertThat(ConditionalRequestFilter.honoursIfMatch("patch", "/api/agents", c)).isFalse();
    }
}
