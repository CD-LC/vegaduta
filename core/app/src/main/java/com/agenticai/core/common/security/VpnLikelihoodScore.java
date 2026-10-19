package com.agenticai.core.common.security;

/**
 * VPN/Proxy/Bot likelihood score: a composite score (0.0-1.0) indicating how likely
 * an IP address belongs to a VPN, proxy, or bot farm.
 *
 * Scores are calculated from multiple heuristics:
 * - Datacenter IP detection (ASN reputation)
 * - TLS/User-Agent fingerprinting mismatches
 * - High velocity from the same subnet
 * - Geographic impossibilities (same IP, different countries within seconds)
 */
public class VpnLikelihoodScore {
    public final double score; // 0.0 - 1.0
    public final String ipType; // residential, datacenter, corporate, vpn, proxy, hosting, etc.
    public final String asn; // Autonomous System Number
    public final String country; // ISO 3166-1 alpha-2
    public final String provider; // ISP/Hosting provider name

    public VpnLikelihoodScore(double score, String ipType, String asn, String country, String provider) {
        this.score = Math.max(0.0, Math.min(1.0, score)); // Clamp to 0.0-1.0
        this.ipType = ipType;
        this.asn = asn;
        this.country = country;
        this.provider = provider;
    }

    public static VpnLikelihoodScore of(double score, String ipType, String asn, String country, String provider) {
        return new VpnLikelihoodScore(score, ipType, asn, country, provider);
    }

    public static VpnLikelihoodScore of(double score, String ipType, String asn, String country) {
        return new VpnLikelihoodScore(score, ipType, asn, country, null);
    }

    public boolean isHighRisk() {
        return score > 0.7; // Threshold for challenging sensitive endpoints
    }

    public boolean isMediumRisk() {
        return score > 0.4 && score <= 0.7;
    }

    public boolean isLowRisk() {
        return score <= 0.4;
    }

    public String getSeverity() {
        if (isHighRisk()) return "HIGH";
        if (isMediumRisk()) return "MEDIUM";
        return "LOW";
    }

    @Override
    public String toString() {
        return "VpnLikelihoodScore{" +
                "score=" + String.format("%.2f", score) +
                ", ipType='" + ipType + '\'' +
                ", severity='" + getSeverity() + '\'' +
                ", asn='" + asn + '\'' +
                ", country='" + country + '\'' +
                '}';
    }
}
