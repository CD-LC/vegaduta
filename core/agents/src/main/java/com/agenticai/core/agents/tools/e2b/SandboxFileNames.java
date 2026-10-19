package com.agenticai.core.agents.tools.e2b;

import com.agenticai.core.sdlc.dto.SandboxFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * File naming for {@link SandboxFileRegistry}: which mime types are mountable (and their
 * extension), the sanitised in-sandbox file name, collision suffixes, and human-readable sizes.
 * Pure and static; split out of the registry only to keep each file reviewable.
 */
final class SandboxFileNames {

    /**
     * Mime types worth mounting - data a script can read. Images/audio/video are not data inputs.
     */
    static final Map<String, String> MIME_TO_EXTENSION =
            Map.of(
                    "text/csv", ".csv",
                    "application/json", ".json",
                    "application/pdf", ".pdf",
                    "text/plain", ".txt",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx");

    static String extensionFor(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        return MIME_TO_EXTENSION.get(mimeType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim());
    }

    /**
     * Plain filename of {@code [A-Za-z0-9._-]}, forced to the mime's extension; "data[_n]" when
     * unnamed.
     */
    static String sanitizeName(String fileName, String ext, int ordinal) {
        String base = fileName == null ? "" : fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replaceAll("[^A-Za-z0-9_-]", "_");
        base = base.replaceAll("^_+", "");
        if (base.isEmpty()) {
            base = ordinal <= 1 ? "data" : "data_" + ordinal;
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        return base + ext;
    }

    static String uniqueName(List<SandboxFile> files, String candidate) {
        List<String> taken = new ArrayList<>();
        for (SandboxFile f : files) {
            taken.add(f.name());
        }
        if (!taken.contains(candidate)) {
            return candidate;
        }
        for (int i = 2; ; i++) {
            String next = suffixed(candidate, i);
            if (!taken.contains(next)) {
                return next;
            }
        }
    }

    /**
     * {@code sales.csv} + 2 = {@code sales_2.csv}; also used by the Redis writer to resolve a
     * cross-replica clash.
     */
    static String suffixed(String candidate, int n) {
        int dot = candidate.lastIndexOf('.');
        return candidate.substring(0, dot) + "_" + n + candidate.substring(dot);
    }

    static String humanBytes(long bytes) {
        return bytes >= 1024 * 1024
                ? (bytes / 1024 / 1024) + " MB"
                : Math.max(1, bytes / 1024) + " KB";
    }

    private SandboxFileNames() {}
}
