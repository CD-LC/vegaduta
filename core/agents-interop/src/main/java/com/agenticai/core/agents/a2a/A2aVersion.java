package com.agenticai.core.agents.a2a;

import org.springframework.util.StringUtils;

/**
 * Which A2A wire dialect a request speaks, selected by the {@code A2A-Version}
 * HTTP header. The two differ in more than method names: 1.0 wraps
 * {@code SendMessage}/stream results in a oneof envelope ({@code {"task": ...}},
 * {@code {"statusUpdate": ...}}), spells states as {@code TASK_STATE_*} and roles
 * as {@code ROLE_*}, and drops the {@code kind} discriminators 0.3 puts on every
 * object. {@link A2aWire} renders both; this enum is just the switch.
 *
 * <p>An ABSENT header means 0.3 - that is what the 1.0 spec itself says a
 * server must assume, since 0.3 clients predate the header. Method-name
 * aliasing is deliberately NOT tied to this: {@link A2aJsonRpcController}
 * accepts both name sets regardless of header, because the popular client SDKs
 * were observed mixing them during the 0.3-to-1.0 transition, and rejecting a
 * well-formed request over a naming-generation mismatch helps nobody.
 */
public enum A2aVersion {
    V0_3("0.3"),
    V1_0("1.0");

    public static final String HEADER = "A2A-Version";

    private final String wire;

    A2aVersion(String wire) {
        this.wire = wire;
    }

    /** The value echoed back in the response's {@code A2A-Version} header. */
    public String wire() {
        return wire;
    }

    /**
     * @throws A2aError {@code -32009 VersionNotSupported} for any value that is
     *     neither a 0.3.x nor a 1.0.x version string - the one error a client
     *     can act on by retrying with a different header.
     */
    public static A2aVersion fromHeader(String header) {
        if (!StringUtils.hasText(header)) {
            return V0_3;
        }
        String trimmed = header.trim();
        if (trimmed.equals("0.3") || trimmed.startsWith("0.3.")) {
            return V0_3;
        }
        if (trimmed.equals("1.0") || trimmed.startsWith("1.0.")) {
            return V1_0;
        }
        throw A2aError.versionNotSupported(trimmed);
    }
}
