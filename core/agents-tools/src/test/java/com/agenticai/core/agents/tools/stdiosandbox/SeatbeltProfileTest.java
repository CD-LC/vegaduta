package com.agenticai.core.agents.tools.stdiosandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeatbeltProfileTest {

    private static final Path DATA = Path.of("/Users/test/.vegaduta/mcp-package-cache/npm-cache/abc/sandbox");
    private static final Path HOME = Path.of("/Users/test");

    private static String golden(String name) throws IOException {
        try (var in = SeatbeltProfileTest.class.getResourceAsStream("/stdiosandbox/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void goldenProfileWithoutNetwork() throws Exception {
        assertThat(SeatbeltProfile.render(DATA, HOME, List.of(Path.of("/opt/secret store")), false))
                .isEqualTo(golden("seatbelt-no-network.sb"));
    }

    @Test
    void goldenProfileWithNetwork() throws Exception {
        assertThat(SeatbeltProfile.render(DATA, HOME, List.of(), true))
                .isEqualTo(golden("seatbelt-network.sb"));
    }

    @Test
    void deniesByDefaultAndNeverGrantsNetworkUnlessAsked() {
        String p = SeatbeltProfile.render(DATA, HOME, List.of(), false);
        assertThat(p).contains("(deny default)");
        assertThat(p).doesNotContain("network");
        assertThat(p).doesNotContain("SecurityServer");
    }

    @Test
    void credentialLocationsAreMaskedAndTheDataDirReadmittedAfterwards() {
        String p = SeatbeltProfile.render(DATA, HOME, List.of(), false);
        for (String s : List.of(".ssh", ".aws", "Library/Keychains", ".gnupg", "Library/Application Support/Google")) {
            assertThat(p).contains("(deny file-read* (subpath \"/Users/test/" + s + "\"))");
        }
        // SBPL: the last matching rule wins, so the allow must come after the .vegaduta deny.
        assertThat(p.indexOf("(deny file-read* (subpath \"/Users/test/.vegaduta\"))"))
                .isLessThan(p.indexOf("(allow file-read* (subpath \"" + DATA + "\"))"));
    }

    @Test
    void writesAreLimitedToTheDataDirAndDevNull() {
        String p = SeatbeltProfile.render(DATA, HOME, List.of(), false);
        long writeRules = p.lines().filter(l -> l.startsWith("(allow file-write*")).count();
        assertThat(writeRules).isEqualTo(2);
        assertThat(p).contains("(allow file-write* (subpath \"" + DATA + "\"))");
    }

    @Test
    void quotesAndBackslashesInPathsAreEscaped() {
        String p = SeatbeltProfile.render(Path.of("/data/a\"b\\c"), HOME, List.of(), false);
        assertThat(p).contains("(subpath \"/data/a\\\"b\\\\c\")");
        // one literal quote pair per string: the escaped quote cannot close the string early
        assertThat(p).doesNotContain("(subpath \"/data/a\"b");
    }

    @Test
    void controlCharactersAreRefusedNotEscaped() {
        assertThatThrownBy(() -> SeatbeltProfile.render(Path.of("/data/x\n(allow default)"), HOME, List.of(), false))
                .isInstanceOf(StdioSandboxException.class);
        assertThatThrownBy(() -> SeatbeltProfile.render(DATA, HOME, List.of(Path.of("/x\ty")), false))
                .isInstanceOf(StdioSandboxException.class);
    }

    @Test
    void relativeDataDirIsRefused() {
        assertThatThrownBy(() -> SeatbeltProfile.render(Path.of("relative/dir"), HOME, List.of(), false))
                .isInstanceOf(StdioSandboxException.class);
    }

    @Test
    void injectionInServerNameCannotReachTheProfile() {
        // The profile has no server-name input at all; this pins that the renderer's signature never grows one.
        assertThat(SeatbeltProfile.class.getDeclaredMethods())
                .filteredOn(m -> m.getName().equals("render"))
                .allSatisfy(m -> assertThat(m.getParameterTypes()).doesNotContain(String.class));
    }

    @Test
    void networkMeansOutboundTcpOnlyAndNeverTheBroadGrant() {
        String p = SeatbeltProfile.render(DATA, HOME, List.of(), true);
        assertThat(p).doesNotContain("(allow network*)");
        assertThat(p).contains("(allow network-outbound (remote tcp))");
        assertThat(p).doesNotContain("network-bind").doesNotContain("network-inbound");
        // only the resolver's unix socket is reachable
        assertThat(p.lines().filter(l -> l.contains("(literal") && l.contains("network-outbound")))
                .containsExactly("(allow network-outbound (literal \"/private/var/run/mDNSResponder\"))");
    }

    @Test
    void loopbackIsRefusedAfterTheAllowUnlessOptedIn() {
        String closed = SeatbeltProfile.render(DATA, HOME, List.of(), true, false);
        String deny = "(deny network-outbound (remote ip \"localhost:*\"))";
        assertThat(closed).contains(deny);
        // SBPL: the last matching rule wins, so the deny must follow the allow.
        assertThat(closed.indexOf(deny)).isGreaterThan(closed.indexOf("(allow network-outbound (remote tcp))"));
        assertThat(SeatbeltProfile.render(DATA, HOME, List.of(), true, true)).doesNotContain(deny);
    }

    @Test
    void appTokenStoresAndShellHistoryAreMasked() {
        String p = SeatbeltProfile.render(DATA, HOME, List.of(), false);
        for (String s :
                List.of(
                        "Library/Application Support",
                        "Library/Group Containers",
                        ".config/op",
                        ".claude",
                        ".zsh_history")) {
            assertThat(p).contains("(deny file-read* (subpath \"/Users/test/" + s + "\"))");
        }
    }
}
