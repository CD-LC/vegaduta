package com.agenticai.core.agents.tools.e2b;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Shared shape of a persisted sandbox output (chart / workbook / script / notebook): the extension
 * allow-list, the object-storage keys, and the public download path. One place so the writing side
 * ({@link E2bRunCodeToolCallback}) and the serving side ({@link SandboxArtifactController}) can
 * never disagree.
 *
 * <p>Storage layout: {@code sandbox-artifacts/<uuid>.<ext>} holds the bytes and {@code
 * sandbox-artifacts/<uuid>.meta} the sidecar metadata (see {@link #metaKey}). The public path is
 * {@code /api/sandbox/artifacts/<uuid>} - the web chat's artifact sniffer ({@code MediaCard.tsx})
 * recognises exactly that shape.
 */
public final class SandboxArtifacts {

    public static final String KEY_PREFIX = "sandbox-artifacts/";
    public static final String URL_PREFIX = "/api/sandbox/artifacts/";

    /** Extension -> content type. Anything not listed is never persisted or served. */
    static final Map<String, String> EXTENSION_TO_MIME = new LinkedHashMap<>();

    static {
        EXTENSION_TO_MIME.put("png", "image/png");
        EXTENSION_TO_MIME.put("jpg", "image/jpeg");
        EXTENSION_TO_MIME.put("jpeg", "image/jpeg");
        EXTENSION_TO_MIME.put("svg", "image/svg+xml");
        EXTENSION_TO_MIME.put(
                "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        EXTENSION_TO_MIME.put("csv", "text/csv");
        EXTENSION_TO_MIME.put("json", "application/json");
        EXTENSION_TO_MIME.put("md", "text/markdown");
        EXTENSION_TO_MIME.put("txt", "text/plain");
        EXTENSION_TO_MIME.put("py", "text/x-python");
        EXTENSION_TO_MIME.put("pdf", "application/pdf");
    }

    private SandboxArtifacts() {}

    /** Lower-cased extension of {@code fileName} if it is allow-listed, else null. */
    public static String allowedExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXTENSION_TO_MIME.containsKey(ext) ? ext : null;
    }

    public static String mimeFor(String extension) {
        return EXTENSION_TO_MIME.get(extension);
    }

    public static String dataKey(UUID id, String extension) {
        return KEY_PREFIX + id + "." + extension;
    }

    /**
     * Sidecar JSON object {@code {"name","ext","agentId"}}: the original filename, the extension
     * (so the download needs ONE lookup, not one per allow-listed extension) and the producing
     * agent (so the download can re-check the caller may see that agent).
     */
    public static String metaKey(UUID id) {
        return KEY_PREFIX + id + ".meta";
    }

    public static String urlFor(UUID id) {
        return URL_PREFIX + id;
    }

    /** A filename safe to put in a Content-Disposition header: plain chars only. */
    public static String safeDisplayName(String name, String fallback) {
        if (name == null) {
            return fallback;
        }
        String cleaned = name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return cleaned.isEmpty() || cleaned.startsWith(".") ? fallback : cleaned;
    }
}
