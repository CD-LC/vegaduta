package com.agenticai.core.agents.skills;

import com.agenticai.core.json.JsonMappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Content-hash pinning for skills: the pure half (no Spring, no database).
 *
 * <p>A skill's <em>content hash</em> is the SHA-256 over its canonical content - description, body
 * and every bundled file (path + content), in a fixed order. It is byte-for-byte the digest {@code
 * SkillService} already stores in {@code skill.content_sha256}, so hashes written before this class
 * existed stay valid. A skill's <em>approved hash</em> is the content hash an owner explicitly
 * accepted; the difference between the two is what the review gate looks at.
 *
 * <p>Alongside the approved hash the service stores a small <em>manifest</em> (one SHA-256 per
 * component) so that when the content later changes the reviewer can be told <em>what</em> changed
 * ("instructions changed, file scripts/x.py added") without keeping a full copy of the approved
 * text.
 */
public final class SkillIntegrity {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int MAX_CHANGES = 20;

    private SkillIntegrity() {}

    /** Where a skill stands against the pin its owner recorded. */
    public enum Status {
        /** Ships on the classpath: platform-reviewed, never gated, reported only. */
        BUILT_IN,
        /**
         * No approval recorded yet (legacy skill, or imported and not yet
         * attached/enabled/approved).
         */
        UNPINNED,
        /** The current content is exactly what the owner approved. */
        APPROVED,
        /** The content differs from what the owner approved. */
        NEEDS_REVIEW
    }

    /** Canonical SHA-256 (lower-case hex). Must stay identical to {@code SkillService#sha256}. */
    public static String hash(String description, String body, Map<String, String> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(nz(description).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(nz(body).getBytes(StandardCharsets.UTF_8));
            if (files != null) {
                new TreeMap<>(files)
                        .forEach(
                                (path, content) -> {
                                    digest.update((byte) 0);
                                    digest.update(nz(path).getBytes(StandardCharsets.UTF_8));
                                    digest.update((byte) 0);
                                    digest.update(nz(content).getBytes(StandardCharsets.UTF_8));
                                });
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hash(SkillDefinition skill) {
        return hash(skill.description(), skill.body(), skill.files());
    }

    /**
     * One SHA-256 per component, as JSON: {"description":"..","body":"..","files":{"path":".."}}.
     */
    public static String manifestJson(String description, String body, Map<String, String> files) {
        Map<String, Object> root = new TreeMap<>();
        root.put("description", sha(nz(description)));
        root.put("body", sha(nz(body)));
        Map<String, String> perFile = new TreeMap<>();
        if (files != null) {
            files.forEach((path, content) -> perFile.put(nz(path), sha(nz(content))));
        }
        root.put("files", perFile);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Human-readable list of what changed between two manifests. Never throws: an unreadable or
     * missing approved manifest yields one generic line, because the status itself already says the
     * hash differs.
     */
    public static List<String> diffSummary(
            String approvedManifestJson, String currentManifestJson) {
        List<String> changes = new ArrayList<>();
        JsonNode approved = parse(approvedManifestJson);
        JsonNode current = parse(currentManifestJson);
        if (approved == null || current == null) {
            changes.add(
                    "The content changed since it was approved (no detailed record of the approved version is available).");
            return changes;
        }
        if (!text(approved, "description").equals(text(current, "description"))) {
            changes.add("Description changed");
        }
        if (!text(approved, "body").equals(text(current, "body"))) {
            changes.add("Instructions (SKILL.md body) changed");
        }
        JsonNode before = approved.path("files");
        JsonNode after = current.path("files");
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        Iterator<String> names = after.propertyNames().iterator();
        while (names.hasNext()) {
            String path = names.next();
            if (!before.has(path)) {
                added.add(path);
            } else if (!before.path(path).asString().equals(after.path(path).asString())) {
                modified.add(path);
            }
        }
        Iterator<String> old = before.propertyNames().iterator();
        while (old.hasNext()) {
            String path = old.next();
            if (!after.has(path)) {
                removed.add(path);
            }
        }
        added.sort(null);
        removed.sort(null);
        modified.sort(null);
        for (String p : added) {
            changes.add("Added file: " + SkillScanner.sanitize(p, 80));
        }
        for (String p : removed) {
            changes.add("Removed file: " + SkillScanner.sanitize(p, 80));
        }
        for (String p : modified) {
            changes.add("Changed file: " + SkillScanner.sanitize(p, 80));
        }
        if (changes.isEmpty()) {
            changes.add("The content changed since it was approved.");
        }
        if (changes.size() > MAX_CHANGES) {
            int more = changes.size() - MAX_CHANGES;
            changes = new ArrayList<>(changes.subList(0, MAX_CHANGES));
            changes.add("... and " + more + " more change(s)");
        }
        return changes;
    }

    public static Status status(String contentSha256, String approvedSha256) {
        if (approvedSha256 == null || approvedSha256.isBlank()) {
            return Status.UNPINNED;
        }
        return approvedSha256.equalsIgnoreCase(contentSha256 == null ? "" : contentSha256.trim())
                ? Status.APPROVED
                : Status.NEEDS_REVIEW;
    }

    /**
     * Whether {@code load_skill} must refuse a skill. Only ever true when enforcement is on, and
     * never for a built-in: a changed skill waits for re-approval, and so does a high-risk skill
     * nobody has approved at its current content.
     */
    public static boolean blocked(boolean enforce, Status status, SkillScanner.RiskLevel risk) {
        if (!enforce || status == null || status == Status.BUILT_IN) {
            return false;
        }
        if (status == Status.NEEDS_REVIEW) {
            return true;
        }
        return risk == SkillScanner.RiskLevel.HIGH && status != Status.APPROVED;
    }

    /** One short, person-facing sentence on why a skill is paused. */
    public static String blockedReason(
            String skillName, Status status, SkillScanner.RiskLevel risk) {
        String name = SkillScanner.sanitize(skillName, 64);
        if (status == Status.NEEDS_REVIEW) {
            return "Skill \""
                    + name
                    + "\" is paused: its content changed since a workspace admin approved it. "
                    + "An admin must review and re-approve it on the Skills page before agents can use it. Continue without it.";
        }
        return "Skill \""
                + name
                + "\" is paused: a security scan rated it "
                + (risk == null ? "high" : risk.wire())
                + " risk and no admin has approved it yet. An admin must review it on the Skills page. Continue without it.";
    }

    // ------------------------------------------------------------------ helpers

    private static String sha(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asString("");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
