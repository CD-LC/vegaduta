package com.agenticai.core.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Tool-definition pinning primitives: a canonical, order-independent SHA-256 over the tool list a
 * remote MCP server advertised. The MCP specification treats tool descriptions and annotations as
 * untrusted unless they come from a trusted server, and nothing stops a server from changing them
 * after an administrator reviewed it (a "rug pull"). Pinning records what was approved so a later
 * difference can be detected, audited and gated.
 *
 * <p>Pure (no Spring, no SDK types) so core/mcp keeps its leaf-module shape; the caller maps the
 * SDK's tool objects into {@link ToolDescriptor}s. What is hashed is what the model sees: name,
 * description, input schema and annotations. Everything is length-prefixed so field boundaries
 * cannot be forged by moving text between description and schema.
 */
public final class McpToolPin {

    private McpToolPin() {}

    /** One advertised tool, reduced to the fields that shape model behaviour. */
    public record ToolDescriptor(
            String name, String description, String inputSchema, String annotations) {}

    /** Per-tool hashes keyed by tool name (sorted) plus the aggregate over all of them. */
    public record Manifest(TreeMap<String, String> toolHashes) {

        public String aggregateHash() {
            MessageDigest md = sha256();
            toolHashes.forEach((name, hash) -> {
                update(md, name);
                update(md, hash);
            });
            return HexFormat.of().formatHex(md.digest());
        }

        /** Compact JSON object {@code {"tool":"hash",...}} for storage. */
        public String toJson() {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> e : toolHashes.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(e.getKey())).append("\":\"").append(e.getValue()).append('"');
            }
            return sb.append('}').toString();
        }

        public static Manifest fromJson(String json) {
            TreeMap<String, String> out = new TreeMap<>();
            if (json == null || json.isBlank()) {
                return new Manifest(out);
            }
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        com.agenticai.core.json.JsonMappers.plain().readTree(json);
                node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // A corrupt stored manifest only loses the "which tool" detail; the aggregate hash
                // comparison still decides changed/unchanged.
                return new Manifest(new TreeMap<>());
            }
            return new Manifest(out);
        }
    }

    /** What differs between an approved manifest and a newly observed one. */
    public record Diff(List<String> added, List<String> removed, List<String> changed) {

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }

        public String summary() {
            return "added=" + added + " removed=" + removed + " changed=" + changed;
        }
    }

    public static Manifest manifest(List<ToolDescriptor> tools) {
        TreeMap<String, String> hashes = new TreeMap<>();
        if (tools != null) {
            for (ToolDescriptor t : tools) {
                if (t == null || t.name() == null) {
                    continue;
                }
                MessageDigest md = sha256();
                update(md, t.name());
                update(md, nullToEmpty(t.description()));
                update(md, nullToEmpty(t.inputSchema()));
                update(md, nullToEmpty(t.annotations()));
                hashes.put(t.name(), HexFormat.of().formatHex(md.digest()));
            }
        }
        return new Manifest(hashes);
    }

    public static Diff diff(Manifest approved, Manifest observed) {
        Objects.requireNonNull(approved);
        Objects.requireNonNull(observed);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String name : new TreeSet<>(observed.toolHashes().keySet())) {
            String before = approved.toolHashes().get(name);
            if (before == null) {
                added.add(name);
            } else if (!before.equals(observed.toolHashes().get(name))) {
                changed.add(name);
            }
        }
        for (String name : approved.toolHashes().keySet()) {
            if (!observed.toolHashes().containsKey(name)) {
                removed.add(name);
            }
        }
        return new Diff(added, removed, changed);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void update(MessageDigest md, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        md.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        md.update((byte) ':');
        md.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JCA algorithm", e);
        }
    }
}
