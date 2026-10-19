package com.agenticai.core.platform.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.agenticai.core.platform.ops.LangfuseApiClientTest.MutableClock;
import com.agenticai.core.platform.ops.PlatformVersionService.HttpResult;
import com.agenticai.core.platform.ops.PlatformVersionService.ServiceVersion;
import com.agenticai.core.platform.ops.PlatformVersionService.Target;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class PlatformVersionServiceTest {

    @Test
    void coreReportsBuildInfoAndEnvStamp() {
        Properties p = new Properties();
        p.setProperty("version", "0.0.1-SNAPSHOT");
        p.setProperty("time", "2026-10-09T08:00:00Z");
        ServiceVersion core = PlatformVersionService.coreVersion(new BuildProperties(p), "abc123", "");
        assertEquals("0.0.1-SNAPSHOT", core.version());
        assertEquals("abc123", core.gitSha());
        assertEquals("2026-10-09T08:00:00Z", core.buildTime());
        assertEquals("ok", core.status());

        ServiceVersion unstamped = PlatformVersionService.coreVersion(null, " ", null);
        assertNull(unstamped.gitSha());
        assertNull(unstamped.version());
    }

    @Test
    void probesServicesAndCaches() {
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock();
        PlatformVersionService svc = new PlatformVersionService(
            new ServiceVersion("core", "1", "sha", null, "ok"),
            List.of(
                new Target("sidecar", "http://sidecar:8001/health"),
                new Target("code-intel", null),
                new Target("academy", "http://academy:4100/api/health"),
                new Target("web", "http://web:3000/")),
            uri -> {
                calls.incrementAndGet();
                return switch (uri.getHost()) {
                    case "sidecar" -> new HttpResult(200, "{\"status\":\"ok\",\"version\":\"0.2.0\",\"git_sha\":\"def\"}");
                    case "academy" -> new HttpResult(502, "");
                    default -> throw new ConnectException("refused");
                };
            },
            clock);

        List<ServiceVersion> services = svc.versions().services();

        assertEquals(List.of("core", "sidecar", "code-intel", "academy", "web"),
            services.stream().map(ServiceVersion::name).toList());
        assertEquals("ok", services.get(1).status());
        assertEquals("0.2.0", services.get(1).version());
        assertEquals("def", services.get(1).gitSha());
        assertEquals("unknown", services.get(2).status());
        assertEquals("unreachable", services.get(3).status());
        assertEquals("unreachable", services.get(4).status());
        assertEquals(3, calls.get());

        svc.versions();
        assertEquals(3, calls.get());
        clock.advance(Duration.ofSeconds(31));
        svc.versions();
        assertEquals(6, calls.get());
    }

    @Test
    void joinRejectsNonHttp() {
        assertNull(PlatformVersionService.join("", "/health"));
        assertNull(PlatformVersionService.join("ftp://x", "/health"));
        assertEquals("http://x:1/health", PlatformVersionService.join("http://x:1/", "/health"));
    }
}
