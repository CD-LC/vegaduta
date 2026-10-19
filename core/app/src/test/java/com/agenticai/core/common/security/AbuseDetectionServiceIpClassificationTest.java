package com.agenticai.core.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

/**
 * {@link AbuseDetectionService#classifyIp} must classify by address range, not by string prefix:
 * the old {@code startsWith("172.")} test counted every 172.x.x.x address (a public /8 except for
 * 172.16.0.0/12) as private, which let a public client dodge the VPN/hosting heuristics.
 */
class AbuseDetectionServiceIpClassificationTest {

    @Test
    void publicAddressesInThe172SlashEightAreNotPrivate() {
        assertEquals("residential", AbuseDetectionService.classifyIp("172.15.0.1"));
        assertEquals("residential", AbuseDetectionService.classifyIp("172.32.0.1"));
        assertEquals("residential", AbuseDetectionService.classifyIp("172.217.14.206"));
    }

    @Test
    void rfc1918RangesArePrivate() {
        assertEquals("private", AbuseDetectionService.classifyIp("172.16.0.1"));
        assertEquals("private", AbuseDetectionService.classifyIp("172.31.255.254"));
        assertEquals("private", AbuseDetectionService.classifyIp("10.0.0.1"));
        assertEquals("private", AbuseDetectionService.classifyIp("10.255.255.255"));
        assertEquals("private", AbuseDetectionService.classifyIp("192.168.1.1"));
    }

    @Test
    void publicAddressesAreResidential() {
        assertEquals("residential", AbuseDetectionService.classifyIp("8.8.8.8"));
        assertEquals("residential", AbuseDetectionService.classifyIp("192.169.0.1"));
        assertEquals("residential", AbuseDetectionService.classifyIp("2001:4860:4860::8888"));
    }

    @Test
    void loopbackLinkLocalAndUnspecifiedAreLoopback() {
        assertEquals("loopback", AbuseDetectionService.classifyIp("127.0.0.1"));
        assertEquals("loopback", AbuseDetectionService.classifyIp("127.255.0.9"));
        assertEquals("loopback", AbuseDetectionService.classifyIp("169.254.10.10"));
        assertEquals("loopback", AbuseDetectionService.classifyIp("0.0.0.0"));
        assertEquals("loopback", AbuseDetectionService.classifyIp("::1"));
    }

    @Test
    void ipv6UniqueLocalIsPrivate() {
        assertEquals("private", AbuseDetectionService.classifyIp("fd12:3456:789a::1"));
    }

    @Test
    void unparsableTextIsUnknownAndNeverResolved() {
        assertEquals("unknown", AbuseDetectionService.classifyIp("not-an-ip"));
        assertEquals("unknown", AbuseDetectionService.classifyIp("example.com"));
        assertEquals("unknown", AbuseDetectionService.classifyIp("999.1.1.1"));
        assertEquals("unknown", AbuseDetectionService.classifyIp("1:2:3:4:5:6:7:8:9"));
        assertEquals("unknown", AbuseDetectionService.classifyIp(""));
        assertEquals("unknown", AbuseDetectionService.classifyIp((String) null));
        assertEquals("unknown", AbuseDetectionService.classifyIp((InetAddress) null));
    }

    @Test
    void inetAddressOverloadAgreesWithTheStringOverload() throws Exception {
        assertEquals(
                "private", AbuseDetectionService.classifyIp(InetAddress.getByName("172.16.5.5")));
        assertEquals(
                "residential",
                AbuseDetectionService.classifyIp(InetAddress.getByName("172.15.5.5")));
    }
}
