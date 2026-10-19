package com.agenticai.core.agents.skills;

import static com.agenticai.core.agents.skills.SkillScanRules.*;
import static com.agenticai.core.agents.skills.SkillScanner.MAX_PER_RULE;
import static com.agenticai.core.agents.skills.SkillScanner.normalize;
import static com.agenticai.core.agents.skills.SkillScanner.scanHiddenUnicode;

import com.agenticai.core.agents.skills.SkillScanner.Collector;
import com.agenticai.core.agents.skills.SkillScanner.Kind;
import com.agenticai.core.agents.skills.SkillScanner.Severity;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * The payload rules of {@link SkillScanner}: web addresses, IP addresses and remote images,
 * encoded blobs (base64, hex, escaped bytes) and bundled file names. Split out of the scanner to
 * keep it under the file-size cap; same rules, same findings, called from {@link
 * SkillScanner#scan(String, String, String, java.util.Map)}.
 */
final class SkillScanPayloads {

    private SkillScanPayloads() {}

    static void scanUrls(String where, String norm, Kind kind, Collector c) {
        Matcher m = URL.matcher(norm);
        Set<String> hostsSeen = new HashSet<>();
        StringBuilder stripped = new StringBuilder(norm);
        int count = 0;
        while (m.find()) {
            for (int k = m.start(); k < m.end(); k++) {
                stripped.setCharAt(k, ' ');
            }
            if (count >= 20) {
                continue;
            }
            String url = trimUrl(m.group());
            String lower = url.toLowerCase(Locale.ROOT);
            String afterScheme = lower.substring(lower.indexOf("://") + 3);
            int authEnd = indexOfAny(afterScheme, "/?#");
            String authority = authEnd < 0 ? afterScheme : afterScheme.substring(0, authEnd);
            String pathAndRest = authEnd < 0 ? "" : afterScheme.substring(authEnd);
            boolean userinfo = authority.contains("@");
            String hostPort =
                    userinfo ? authority.substring(authority.lastIndexOf('@') + 1) : authority;
            String host = hostPort;
            int colon = hostPort.lastIndexOf(':');
            if (colon > 0 && !hostPort.endsWith("]") && hostPort.indexOf(':') == colon) {
                host = hostPort.substring(0, colon);
            }
            if (host.isEmpty()) {
                continue;
            }
            count++;
            if (!hostsSeen.add(host)) {
                continue;
            }
            Severity severity = Severity.LOW;
            String rule = "embedded-url";
            if (isSuspiciousEndpoint(host, host + pathAndRest)) {
                rule = "suspicious-endpoint";
                severity = Severity.HIGH;
            } else if (userinfo) {
                rule = "url-userinfo";
                severity = Severity.MEDIUM;
            } else if (IPV4.matcher(host).matches()) {
                rule = "embedded-ip-url";
                severity = isPrivateIp(host) ? Severity.LOW : Severity.MEDIUM;
            } else if (isShortener(host)) {
                rule = "url-shortener";
                severity = Severity.MEDIUM;
            } else if (host.contains("xn--")) {
                rule = "punycode-host";
                severity = Severity.MEDIUM;
            } else if (pathAndRest.matches("(?s).*/(?:webhooks?|hooks?)(?:[/?#].*|$)")) {
                rule = "webhook-url";
                severity = Severity.MEDIUM;
            }
            c.add(rule, severity, url, where);
        }

        Matcher image = MD_IMAGE.matcher(norm);
        int images = 0;
        while (images < 5 && image.find()) {
            images++;
            String url = image.group(1);
            int q = url.indexOf('?');
            if (q >= 0) {
                String query = url.substring(q);
                boolean placeholder =
                        query.contains("{")
                                || query.contains("<")
                                || query.contains("$")
                                || query.contains("[")
                                || query.contains("%7b")
                                || query.contains("%3c");
                c.add(
                        "markdown-image-exfil",
                        placeholder ? Severity.HIGH : Severity.MEDIUM,
                        "remote image with a query string: " + url,
                        where);
            }
        }

        Matcher ip = IPV4.matcher(stripped);
        int ips = 0;
        Set<String> ipsSeen = new HashSet<>();
        while (ips < 10 && ip.find()) {
            String addr = ip.group();
            if (ipsSeen.add(addr)) {
                ips++;
                c.add(
                        "embedded-ip",
                        isPrivateIp(addr) ? Severity.LOW : Severity.MEDIUM,
                        addr,
                        where);
            }
        }
    }

    private static String trimUrl(String url) {
        int end = url.length();
        while (end > 0 && ".,;:!?".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isSuspiciousEndpoint(String host, String hostAndPath) {
        for (String bad : SUSPICIOUS_HOSTS) {
            if (host.equals(bad) || host.endsWith("." + bad)) {
                return true;
            }
        }
        for (String prefix : SUSPICIOUS_PREFIXES) {
            if (hostAndPath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isShortener(String host) {
        for (String s : SHORTENERS) {
            if (host.equals(s) || host.endsWith("." + s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrivateIp(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int a;
        int b;
        try {
            a = Integer.parseInt(parts[0]);
            b = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        return a == 10
                || a == 127
                || a == 0
                || (a == 192 && b == 168)
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 169 && b == 254);
    }

    // ------------------------------------------------------------------ encoded blobs

    static void scanBlobs(String where, String text, Collector c) {
        Matcher b64 = BASE64_RUN.matcher(text);
        int reported = 0;
        int examined = 0;
        while (examined < 50 && reported < MAX_PER_RULE && b64.find()) {
            String token = b64.group();
            if (PURE_HEX.matcher(token).matches() || distinctChars(token) < 8) {
                continue; // a checksum / hex run (handled below) or a rule line like "-----"
            }
            examined++;
            String decoded = decodePrintable(token);
            if (decoded != null) {
                String hit = instructionHit(decoded);
                reported++;
                if (hit != null) {
                    c.add(
                            "encoded-instruction",
                            Severity.HIGH,
                            "base64 that decodes to an instruction (" + hit + "): " + decoded,
                            where);
                } else {
                    c.add(
                            "encoded-text",
                            Severity.MEDIUM,
                            "base64 that decodes to readable text: " + decoded,
                            where);
                }
            } else if (token.length() >= 120) {
                reported++;
                c.add(
                        "encoded-blob",
                        Severity.MEDIUM,
                        "long opaque base64-like run ("
                                + token.length()
                                + " characters): "
                                + token.substring(0, 24)
                                + "...",
                        where);
            }
        }

        Matcher hex = HEX_RUN.matcher(text);
        int hexReported = 0;
        while (hexReported < MAX_PER_RULE && hex.find()) {
            hexReported++;
            String token = hex.group();
            String decoded = decodeHex(token);
            String hit = decoded == null ? null : instructionHit(decoded);
            if (hit != null) {
                c.add(
                        "encoded-instruction",
                        Severity.HIGH,
                        "hex that decodes to an instruction (" + hit + "): " + decoded,
                        where);
            } else {
                c.add(
                        "hex-blob",
                        Severity.MEDIUM,
                        "long hex run ("
                                + token.length()
                                + " characters): "
                                + token.substring(0, 24)
                                + "...",
                        where);
            }
        }
        if (ESCAPED_BYTES.matcher(text).find()) {
            c.add("escaped-bytes", Severity.MEDIUM, "a run of \\x escaped bytes", where);
        }
        if (ESCAPED_UNICODE.matcher(text).find()) {
            c.add("escaped-unicode", Severity.MEDIUM, "a run of \\u escaped characters", where);
        }
        if (PERCENT_RUN.matcher(text).find()) {
            c.add(
                    "percent-encoded",
                    Severity.MEDIUM,
                    "a long run of %XX percent-encoded bytes",
                    where);
        }
    }

    private static int distinctChars(String token) {
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < token.length(); i++) {
            seen.add(token.charAt(i));
        }
        return seen.size();
    }

    /**
     * Decodes a base64 token and returns it only when the result is mostly printable ASCII text.
     */
    private static String decodePrintable(String token) {
        byte[] bytes;
        try {
            String normalised = token.replace('-', '+').replace('_', '/');
            bytes = Base64.getMimeDecoder().decode(normalised);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return printableOrNull(bytes);
    }

    private static String decodeHex(String token) {
        int n = token.length() / 2;
        byte[] bytes = new byte[n];
        for (int i = 0; i < n; i++) {
            bytes[i] = (byte) Integer.parseInt(token.substring(i * 2, i * 2 + 2), 16);
        }
        return printableOrNull(bytes);
    }

    private static String printableOrNull(byte[] bytes) {
        if (bytes.length < 16) {
            return null;
        }
        int printable = 0;
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v == 9 || v == 10 || v == 13 || (v >= 32 && v <= 126)) {
                printable++;
            }
        }
        if (printable * 100 < bytes.length * 90) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String instructionHit(String decoded) {
        String norm = normalize(decoded);
        for (PatternRule rule : RULES) {
            if (INSTRUCTION_RULE_IDS.contains(rule.id()) && rule.pattern().matcher(norm).find()) {
                return rule.id();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ file names

    static void scanFileName(String path, Collector c) {
        String where = "file-name:" + path;
        scanHiddenUnicode(where, path, false, c);
        String norm = normalize(path).replace('\\', '/');
        if (norm.startsWith("/")
                || norm.matches("^[a-z]:.*")
                || norm.equals("..")
                || norm.startsWith("../")
                || norm.contains("/../")
                || norm.endsWith("/..")) {
            c.add("path-traversal-name", Severity.HIGH, path, where);
        }
        if (DISGUISED_NAME.matcher(norm).find()) {
            c.add("disguised-file-name", Severity.HIGH, path, where);
        } else if (EXECUTABLE_NAME.matcher(norm).find()) {
            c.add("bundled-executable", Severity.MEDIUM, path, where);
        } else if (SCRIPT_NAME.matcher(norm).find()) {
            c.add("bundled-script", Severity.LOW, path, where);
        }
        if (SENSITIVE_NAME.matcher(norm).find()) {
            c.add("sensitive-file-name", Severity.MEDIUM, path, where);
        }
    }
}
