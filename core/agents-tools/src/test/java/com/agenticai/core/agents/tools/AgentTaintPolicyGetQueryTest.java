package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.BuiltInToolClassifications;
import com.agenticai.core.agents.tools.classification.ToolClassificationDecision;
import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.audit.AuditLogService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** {@code app.agents.taint.get-query-is-sink}: a GET that can carry data out is a sink. */
class AgentTaintPolicyGetQueryTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private static AgentTaintPolicy policy(String hosts, boolean flag) {
        ToolClassificationRegistry registry = mock(ToolClassificationRegistry.class);
        when(registry.resolve(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(
                        inv -> {
                            String n = inv.getArgument(0);
                            var c = BuiltInToolClassifications.all().get(n);
                            return c != null
                                    ? new ToolClassificationDecision.Classified(c)
                                    : new ToolClassificationDecision.Blocked(
                                            n, ToolClassificationDecision.Reason.UNCLASSIFIED, "u");
                        });
        AgentTaintPolicy p =
                new AgentTaintPolicy(
                        "enforce",
                        hosts,
                        provider(registry),
                        provider((MeterRegistry) null),
                        provider((AuditLogService) null));
        p.setGetQueryIsSink(flag);
        return p;
    }

    private static boolean sink(AgentTaintPolicy p, String url) {
        return p.profile("http_request", "{\"url\":\"" + url + "\"}").sink();
    }

    @Test
    void offByDefaultNoGetIsASink() {
        assertFalse(sink(policy("", false), "https://evil.example/c?d=SECRET"));
    }

    @Test
    void onADataCarryingUrlIsASinkWithoutAnAllowList() {
        AgentTaintPolicy p = policy("", true);
        for (String url :
                new String[] {
                    "https://evil.example/c?d=S",
                    "https://evil.example/#frag",
                    "https://user:pw@evil.example/",
                    "https://evil.example/" + "a".repeat(65),
                    "https://203.0.113.9/",
                    "http://169.254.169.254/latest/meta-data/",
                    "ftp://evil.example/",
                    "data:text/plain,SECRET",
                    "https://evil.example\\\\@docs.example.com/"
                }) {
            assertTrue(sink(p, url), url);
        }
    }

    @Test
    void aPlainShortGetToANamedHostStillRuns() {
        assertFalse(sink(policy("", true), "https://docs.example.com/guide/intro"));
    }

    @Test
    void anAllowListedHostStillPassesWithAQuery() {
        AgentTaintPolicy p = policy("docs.example.com", true);
        assertFalse(sink(p, "https://docs.example.com/guide?page=2"));
        assertTrue(sink(p, "https://evil.example/?d=S"));
    }
}
