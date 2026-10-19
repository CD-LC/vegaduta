package com.agenticai.core.security.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * The address ranges {@link InetAddress}'s own predicates do NOT cover but that
 * an outbound-fetch (SSRF) guard must still refuse (security audit 2026-09-18).
 *
 * <p>{@code isLoopbackAddress / isSiteLocalAddress / isLinkLocalAddress /
 * isAnyLocalAddress / isMulticastAddress} - which every guard in core already
 * checks - miss several ranges that are routable INSIDE a cloud network and
 * nowhere else. The MCP guard had grown its own list; the three agent-side
 * guards (OpenAPI tools, web page fetch, the demo's public fetcher) had not, so
 * a tenant could point a tool at them. This is the one shared list.
 */
public final class ReservedAddressRanges {

    private ReservedAddressRanges() {}

    /** True for a reserved/internal-only address the JDK predicates let through. */
    public static boolean contains(InetAddress address) {
        if (address instanceof Inet4Address) {
            byte[] ip = address.getAddress();
            int b0 = ip[0] & 0xff;
            int b1 = ip[1] & 0xff;
            int b2 = ip[2] & 0xff;
            int b3 = ip[3] & 0xff;
            return b0 == 0                                   // 0.0.0.0/8 "this network" - reaches localhost on Linux
                || (b0 == 100 && b1 >= 64 && b1 <= 127)      // 100.64.0.0/10 CGNAT - Azure Container Apps / k8s overlays
                || (b0 == 192 && b1 == 0 && b2 == 0)         // 192.0.0.0/24 IETF protocol assignments
                || (b0 == 198 && (b1 == 18 || b1 == 19))     // 198.18.0.0/15 benchmarking
                || b0 >= 240                                 // 240.0.0.0/4 reserved + broadcast
                || (b0 == 168 && b1 == 63 && b2 == 129 && b3 == 16); // Azure WireServer / instance metadata companion
        }
        if (address instanceof Inet6Address) {
            byte[] ip = address.getAddress();
            int b0 = ip[0] & 0xff;
            return (b0 & 0xfe) == 0xfc                       // fc00::/7 unique-local
                || (b0 == 0x00 && (ip[1] & 0xff) == 0x64     // 64:ff9b::/96 NAT64 - maps onto ANY v4, incl. private
                    && (ip[2] & 0xff) == 0xff && (ip[3] & 0xff) == 0x9b);
        }
        return false;
    }
}
