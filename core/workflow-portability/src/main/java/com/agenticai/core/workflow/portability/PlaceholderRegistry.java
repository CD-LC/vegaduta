package com.agenticai.core.workflow.portability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything a workflow file refers to that only exists in the tenant it came from (or in the
 * platform it came from): agents, knowledge bases, other workflows, contact groups, devices,
 * delivery destinations, credentials and vault secrets. Each one is given a stable, named key
 * ({@code agent_1}, {@code kb_2}, {@code CREDENTIAL_n3_1}, ...) so a file can carry a recognisable
 * hole instead of a foreign id.
 *
 * <p>Inside node config a mappable reference is written as the whole-string token
 * {@code @placeholder:<key>}; {@link PlaceholderResolver} swaps it for the id the importing user
 * mapped it to, or removes the field when nothing was mapped, so a DRAFT never carries a foreign
 * id. Credentials and vault secrets are not mappable (a credential is never imported); they stay in
 * the text as {@code [[CREDENTIAL_..]]} / {@code {{secret.NAME}}} and are only listed.
 */
public final class PlaceholderRegistry {

    public static final String AGENT = "agent";
    public static final String KNOWLEDGE_BASE = "kb";
    public static final String WORKFLOW = "workflow";
    public static final String CONTACT_GROUP = "group";
    public static final String DEVICE = "device";
    public static final String DESTINATION = "destination";
    public static final String CREDENTIAL = "credential";
    public static final String SECRET = "secret";
    public static final String UNKNOWN = "unknown";

    public static final String TOKEN_PREFIX = "@placeholder:";
    private static final Pattern TOKEN = Pattern.compile("^@placeholder:([A-Za-z0-9_.:-]{1,80})$");

    /**
     * Most places one placeholder is listed as used; a hostile file with thousands of uses stays
     * small.
     */
    private static final int MAX_USES = 20;

    /**
     * Where a placeholder is used: a node id and the config field (or a short description) inside
     * it.
     */
    public record Use(String nodeId, String field) {}

    /**
     * One named hole. {@code label} is display text only (a node label, a credential name, ...).
     */
    public static final class Entry {
        private final String key;
        private final String kind;
        private String label;
        private final List<Use> usedBy = new ArrayList<>();

        Entry(String key, String kind, String label) {
            this.key = key;
            this.kind = kind;
            this.label = label == null ? "" : label;
        }

        public String key() {
            return key;
        }

        public String kind() {
            return kind;
        }

        public String label() {
            return label;
        }

        public List<Use> usedBy() {
            return List.copyOf(usedBy);
        }
    }

    private final Map<String, Entry> byKey = new LinkedHashMap<>();
    private final Map<String, String> keyByIdentity = new HashMap<>();
    private final Map<String, Integer> counters = new HashMap<>();

    /** True for the kinds an importing user can point at an existing resource of this tenant. */
    public static boolean isMappable(String kind) {
        return AGENT.equals(kind)
                || KNOWLEDGE_BASE.equals(kind)
                || WORKFLOW.equals(kind)
                || CONTACT_GROUP.equals(kind)
                || DEVICE.equals(kind);
    }

    /** The key's kind, read off its prefix ({@code agent_3} is an agent). */
    public static String kindOfKey(String key) {
        if (key == null) {
            return UNKNOWN;
        }
        if (key.startsWith("agent_")) {
            return AGENT;
        }
        if (key.startsWith("kb_")) {
            return KNOWLEDGE_BASE;
        }
        if (key.startsWith("workflow_")) {
            return WORKFLOW;
        }
        if (key.startsWith("group_")) {
            return CONTACT_GROUP;
        }
        if (key.startsWith("device_")) {
            return DEVICE;
        }
        if (key.startsWith("destination_")) {
            return DESTINATION;
        }
        if (key.startsWith("CREDENTIAL_") || key.startsWith("credential_")) {
            return CREDENTIAL;
        }
        if (key.startsWith("secret:")) {
            return SECRET;
        }
        return UNKNOWN;
    }

    public static String token(String key) {
        return TOKEN_PREFIX + key;
    }

    /**
     * The key inside a whole-string {@code @placeholder:<key>} token, or null when {@code text} is
     * not one.
     */
    public static String keyOfToken(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = TOKEN.matcher(text);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * Registers (or re-uses) the placeholder for {@code identity} of this {@code kind} and records
     * one use. {@code identity} is what makes two nodes that point at the same foreign agent share
     * one key; it is never shown (it may be a foreign id).
     *
     * @return the key, {@code <kind>_<n>} in order of first appearance
     */
    public String register(
            String kind, String identity, String label, String nodeId, String field) {
        String id = kind + "\u0000" + (identity == null ? label : identity);
        String key = keyByIdentity.get(id);
        if (key == null) {
            int n = counters.merge(kind, 1, Integer::sum);
            key = kind + "_" + n;
            keyByIdentity.put(id, key);
            byKey.put(key, new Entry(key, kind, label));
        }
        use(key, nodeId, field);
        return key;
    }

    /**
     * Registers a placeholder under a key that came from a file (or a scan), keeping that spelling.
     */
    public Entry registerExact(String key, String kind, String label, String nodeId, String field) {
        Entry entry = byKey.computeIfAbsent(key, k -> new Entry(k, kind, label));
        if (entry.label.isBlank() && label != null) {
            entry.label = label;
        }
        use(key, nodeId, field);
        return entry;
    }

    /** Records one more use of an existing key; unknown keys are ignored. */
    public void use(String key, String nodeId, String field) {
        Entry entry = byKey.get(key);
        if (entry == null || entry.usedBy.size() >= MAX_USES) {
            return;
        }
        Use use = new Use(nodeId == null ? "" : nodeId, field == null ? "" : field);
        if (!entry.usedBy.contains(use)) {
            entry.usedBy.add(use);
        }
    }

    public Entry get(String key) {
        return byKey.get(key);
    }

    public Collection<Entry> entries() {
        return List.copyOf(byKey.values());
    }

    public boolean isEmpty() {
        return byKey.isEmpty();
    }
}
