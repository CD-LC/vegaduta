package com.agenticai.core.security.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReservedAddressRangesTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "0.0.0.0", "0.1.2.3", "100.64.0.1", "100.100.0.5", "100.127.255.255", "192.0.0.8",
        "198.18.0.1", "198.19.255.255", "240.0.0.1", "255.255.255.255", "168.63.129.16",
        "fc00::1", "fd12:3456::1", "64:ff9b::a9fe:a9fe"
    })
    void internalOnlyRangesTheJdkPredicatesMissAreRefused(String literal) throws Exception {
        assertTrue(ReservedAddressRanges.contains(InetAddress.getByName(literal)), literal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.0", "168.63.129.17", "198.17.255.255",
        "198.20.0.0", "192.0.1.1", "239.255.255.255", "2606:4700:4700::1111"
    })
    void ordinaryPublicAddressesAreNotCaught(String literal) throws Exception {
        assertFalse(ReservedAddressRanges.contains(InetAddress.getByName(literal)), literal);
    }
}
