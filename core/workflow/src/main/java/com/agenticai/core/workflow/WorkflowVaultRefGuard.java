package com.agenticai.core.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps vault references ({@code {{secret.NAME}}}) out of the expression engine and out of data:
 * config-side references are swapped for opaque tokens while rendering, and any vault-shaped span
 * that arrives through data is neutralised. Extracted unchanged from {@link
 * WorkflowExpressionResolver}.
 */
final class WorkflowVaultRefGuard {

    private WorkflowVaultRefGuard() {}

    /**
     * Vault references are resolved later, inside the HTTP executor, and must reach it verbatim: {@code
     * {{secret.NAME}}} would otherwise parse as a property lookup on a bare name and render as empty text,
     * and {@code {{secret:name}}} is not valid expression syntax at all.
     */
    private static final Pattern SECRET_REF =
        Pattern.compile("\\{\\{\\s*secret\\s*[.:]\\s*[A-Za-z][A-Za-z0-9_]{0,63}\\s*}}");

    /**
     * Data is not config: a substituted value that itself reads {@code {{secret.NAME}}} must never reach the
     * HTTP executor as a live vault reference (a webhook body could otherwise make the node send the tenant's
     * secret to a data-chosen host). By the time this runs the REAL config-side references are opaque tokens
     * (see {@link #protect}), so every vault-shaped span left in the rendered text came from data; a
     * zero-width space inside the braces keeps it readable but no longer matches the executor's patterns.
     */
    static Object neutralizeVaultRefs(Object v) {
        if (v instanceof String s) {
            if (!s.contains("{{")) {
                return s;
            }
            Matcher m = SECRET_REF.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group().replace("{{", "{​{")));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, val) -> out.put(String.valueOf(k), neutralizeVaultRefs(val)));
            return out;
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(neutralizeVaultRefs(o));
            }
            return out;
        }
        return v;
    }

    static String neutralizeVaultRefs(String s) {
        return (String) neutralizeVaultRefs((Object) s);
    }

    /** Vault references swapped for opaque tokens (unguessable per call) and swapped back after rendering. */
    static Protected protect(String template) {
        Matcher m = SECRET_REF.matcher(template);
        if (!m.find()) {
            return new Protected(template, null, null);
        }
        String nonce = UUID.randomUUID().toString().replace("-", "");
        List<String> originals = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        do {
            String token = "" + nonce + "_" + originals.size() + "";
            originals.add(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(token));
        } while (m.find());
        m.appendTail(sb);
        return new Protected(sb.toString(), nonce, originals);
    }

    record Protected(String text, String nonce, List<String> originals) {

        String restore(String rendered) {
            if (nonce == null || rendered == null) {
                return rendered;
            }
            String out = rendered;
            for (int i = 0; i < originals.size(); i++) {
                out = out.replace("" + nonce + "_" + i + "", originals.get(i));
            }
            return out;
        }
    }
}
