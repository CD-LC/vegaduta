package com.agenticai.core.agents.skills;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads an uploaded skill .zip - the same packaging every other Agent Skills host accepts: a folder
 * (or the archive root) holding SKILL.md plus any bundled files.
 *
 * <p>An uploaded archive is hostile input, so everything is bounded BEFORE it is buffered: entry
 * count, per-file bytes and total uncompressed bytes are all capped while streaming (a zip bomb
 * declares a small compressed size and a huge real one, so {@link ZipEntry#getSize()} is never
 * trusted - the actual bytes read are counted). Paths are normalised and anything absolute or
 * containing ".." is refused outright rather than "cleaned", so there is no zip-slip shape that
 * produces a surprising stored path.
 *
 * <p>Static and Spring-free for direct unit testing.
 */
public final class SkillArchiveReader {

    public static final int MAX_FILES = 100;
    public static final int MAX_FILE_BYTES = 256 * 1024;
    public static final int MAX_TOTAL_BYTES = 2 * 1024 * 1024;
    public static final int MAX_PATH_LENGTH = 255;
    private static final String SKILL_MD = "SKILL.md";

    private SkillArchiveReader() {}

    /**
     * @param files bundled text files by normalised relative path, SKILL.md excluded
     * @param skippedPaths entries that were ignored because they are not UTF-8 text
     */
    public record Archive(
            SkillMarkdownParser.Parsed skill,
            Map<String, String> files,
            List<String> skippedPaths,
            Map<String, Object> extras) {

        public Archive(
                SkillMarkdownParser.Parsed skill,
                Map<String, String> files,
                List<String> skippedPaths) {
            this(skill, files, skippedPaths, Map.of());
        }
    }

    /**
     * A bare SKILL.md (no archive): the same parse and the same validation, no bundled files. The
     * size cap is the per-file one, applied to the bytes before they are decoded.
     */
    public static Archive readSingle(byte[] skillMd) {
        if (skillMd == null || skillMd.length == 0) {
            throw new SkillValidationException("SKILL.md is empty.");
        }
        if (skillMd.length > MAX_FILE_BYTES) {
            throw new SkillValidationException(
                    "SKILL.md is too large - it can be at most " + (MAX_FILE_BYTES / 1024) + " KB.");
        }
        String text = decodeUtf8(skillMd);
        if (text == null) {
            throw new SkillValidationException("SKILL.md is not valid UTF-8 text.");
        }
        return new Archive(
                SkillMarkdownParser.parse(text),
                Map.of(),
                List.of(),
                lenientExtras(text));
    }

    /**
     * The optional frontmatter keys, or none when they are malformed: they are informational
     * extras, and an archive that imported fine before they were kept must still import.
     */
    private static Map<String, Object> lenientExtras(String skillMd) {
        try {
            return SkillMarkdownParser.parseExtras(skillMd);
        } catch (SkillValidationException e) {
            return Map.of();
        }
    }

    /** True when the bytes start with a zip local-file-header signature ("PK\3\4"). */
    public static boolean looksLikeZip(byte[] bytes) {
        return bytes != null
                && bytes.length >= 4
                && bytes[0] == 'P'
                && bytes[1] == 'K'
                && bytes[2] == 3
                && bytes[3] == 4;
    }

    public static Archive read(InputStream zipStream) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(zipStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String path = normalise(entry.getName());
                if (path == null) {
                    continue;
                }
                if (entries.size() >= MAX_FILES) {
                    throw new SkillValidationException(
                            "This skill has too many files - the maximum is " + MAX_FILES + ".");
                }
                byte[] bytes = readCapped(zip, path);
                total += bytes.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new SkillValidationException(
                            "This skill is too large - the maximum unpacked size is "
                                    + (MAX_TOTAL_BYTES / 1024)
                                    + " KB.");
                }
                if (entries.put(path, bytes) != null) {
                    // Two entries with one name: tools that scan the archive and tools that unpack
                    // it can disagree on which one wins, so refuse the ambiguity outright.
                    throw new SkillValidationException(
                            "This archive contains \"" + path + "\" more than once.");
                }
            }
        } catch (IOException e) {
            throw new SkillValidationException("That file is not a readable .zip archive.");
        }
        if (entries.isEmpty()) {
            throw new SkillValidationException("That .zip archive is empty.");
        }

        Map<String, byte[]> rooted = stripCommonRoot(entries);
        byte[] skillMd = rooted.remove(SKILL_MD);
        if (skillMd == null) {
            throw new SkillValidationException(
                    "No SKILL.md found. It must sit at the top of the archive, or at the top of the single folder inside it.");
        }
        String skillText = decodeUtf8(skillMd);
        if (skillText == null) {
            throw new SkillValidationException("SKILL.md is not valid UTF-8 text.");
        }
        SkillMarkdownParser.Parsed parsed = SkillMarkdownParser.parse(skillText);

        Map<String, String> files = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        rooted.forEach(
                (path, bytes) -> {
                    String text = decodeUtf8(bytes);
                    if (text == null) {
                        skipped.add(path);
                    } else {
                        files.put(path, text);
                    }
                });
        return new Archive(
                parsed, files, List.copyOf(skipped), lenientExtras(skillText));
    }

    /**
     * Returns the safe relative path, or null for OS junk that should be silently ignored. Throws
     * for anything that tries to leave the skill folder.
     */
    static String normalise(String rawName) {
        for (int i = 0; i < rawName.length(); i++) {
            if (rawName.charAt(i) < 0x20 || rawName.charAt(i) == 0x7f) {
                throw new SkillValidationException(
                        "This archive contains a file name with a control character, which is not allowed.");
            }
        }
        String name = rawName.replace('\\', '/');
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new SkillValidationException(
                    "This archive contains an absolute path ("
                            + rawName
                            + "), which is not allowed.");
        }
        List<String> parts = new ArrayList<>();
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                throw new SkillValidationException(
                        "This archive contains a path that leaves the skill folder ("
                                + rawName
                                + ").");
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            return null;
        }
        String last = parts.get(parts.size() - 1);
        if (parts.contains("__MACOSX") || last.equals(".DS_Store") || last.equals("Thumbs.db")) {
            return null;
        }
        String path = String.join("/", parts);
        if (path.length() > MAX_PATH_LENGTH) {
            throw new SkillValidationException(
                    "A file path in this archive is too long (" + rawName + ").");
        }
        return path;
    }

    private static byte[] readCapped(InputStream in, String path) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > MAX_FILE_BYTES) {
                throw new SkillValidationException(
                        "\""
                                + path
                                + "\" is too large - each file in a skill can be at most "
                                + (MAX_FILE_BYTES / 1024)
                                + " KB.");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** A zip of a folder puts everything under "my-skill/..." - peel that one shared folder off. */
    private static Map<String, byte[]> stripCommonRoot(Map<String, byte[]> entries) {
        if (entries.containsKey(SKILL_MD)) {
            return new LinkedHashMap<>(entries);
        }
        String root = null;
        for (String path : entries.keySet()) {
            int slash = path.indexOf('/');
            if (slash < 0) {
                return new LinkedHashMap<>(entries);
            }
            String first = path.substring(0, slash);
            if (root == null) {
                root = first;
            } else if (!root.equals(first)) {
                return new LinkedHashMap<>(entries);
            }
        }
        Map<String, byte[]> stripped = new LinkedHashMap<>();
        int cut = root.length() + 1;
        entries.forEach((path, bytes) -> stripped.put(path.substring(cut), bytes));
        return stripped;
    }

    /** Null when the bytes are not text: a NUL byte, or not decodable as strict UTF-8. */
    private static String decodeUtf8(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
