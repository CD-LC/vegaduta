package com.agenticai.core.sdlc.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitUrlPolicyTest {

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private final GitUrlPolicy publicDns = new GitUrlPolicy(List.of(), host -> new InetAddress[] {ip("93.184.216.34")});

    @Test
    void acceptsAPublicHttpsRepository() {
        assertEquals("https://github.com/acme/widgets.git", publicDns.check("https://github.com/acme/widgets.git"));
        assertEquals("https://github.com/acme/widgets", publicDns.check("  https://GitHub.com/acme/widgets  "));
        assertEquals("https://example.org/a/b", publicDns.check("https://example.org:443/a/b"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://github.com/acme/widgets", "git://github.com/acme/widgets", "ssh://git@github.com/acme/widgets",
        "git@github.com:acme/widgets.git", "file:///etc/passwd", "ftp://github.com/a/b", "https://user:pw@github.com/a/b",
        "https://github.com/a/b?x=1", "https://github.com/a/b#frag", "https://github.com:8443/a/b",
        "https://127.0.0.1/a/b", "https://169.254.169.254/latest/meta-data", "https://[::1]/a/b", "https://localhost/a/b",
        "https://intranet/a/b", "https://github.com/../etc/passwd", "https://github.com/a b/c", "https://github.com/",
        "https://github.com", "", "not a url",
    })
    void rejectsAnythingThatIsNotAPublicHttpsRepository(String url) {
        assertThrows(IllegalArgumentException.class, () -> publicDns.check(url), url);
    }

    @Test
    void rejectsAHostThatResolvesInsideTheNetwork() {
        for (String internal : List.of("10.0.0.5", "192.168.1.1", "172.16.0.9", "127.0.0.1", "169.254.169.254", "100.64.0.1", "fd00::1", "0.0.0.0")) {
            GitUrlPolicy policy = new GitUrlPolicy(List.of(), host -> new InetAddress[] {ip(internal)});
            assertThrows(IllegalArgumentException.class, () -> policy.check("https://repo.example.com/a/b"), internal);
        }
    }

    @Test
    void oneInternalAnswerAmongPublicOnesIsEnough() {
        GitUrlPolicy mixed = new GitUrlPolicy(List.of(),
            host -> new InetAddress[] {ip("93.184.216.34"), ip("10.1.2.3")});
        assertThrows(IllegalArgumentException.class, () -> mixed.check("https://repo.example.com/a/b"));
    }

    @Test
    void unresolvableHostsAreRefused() {
        GitUrlPolicy none = new GitUrlPolicy(List.of(), host -> {
            throw new UnknownHostException(host);
        });
        assertThrows(IllegalArgumentException.class, () -> none.check("https://nope.example.com/a/b"));
    }

    @Test
    void allowListNarrowsTheHosts() {
        GitUrlPolicy narrow = new GitUrlPolicy(List.of("github.com", "*.gitlab.com"),
            host -> new InetAddress[] {ip("93.184.216.34")});
        narrow.check("https://github.com/a/b");
        narrow.check("https://eu.gitlab.com/a/b");
        assertThrows(IllegalArgumentException.class, () -> narrow.check("https://evil.example.com/a/b"));
        assertThrows(IllegalArgumentException.class, () -> narrow.check("https://gitlab.com/a/b"));
    }
}
