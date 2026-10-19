package com.agenticai.core.sdlc.harness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Turns an untrusted uploaded repository archive (zip, tar or tar.gz) into a clean, re-packed zip
 * that contains ONLY regular files under safe relative paths. Runs on the core host, in memory, and
 * never writes the upload to disk or executes anything.
 *
 * <p>What it refuses (the whole archive is rejected, not silently trimmed):
 * <ul>
 *   <li>zip-slip: any entry that is absolute, drive-lettered, contains a {@code ..} segment, a
 *       backslash or a control character (so no write can land outside the workspace);</li>
 *   <li>tar symlinks, hard links, devices and FIFOs (a link that points out of the tree is how a
 *       later write escapes it);</li>
 *   <li>too many entries, a single file or the total uncompressed size over the caps (zip bombs are
 *       caught while streaming - nothing is inflated past the cap), or a file/directory name clash.</li>
 * </ul>
 * Silently skipped: {@code .git} directories (the harness owns the history), directory entries.
 *
 * <p>Known limit: a {@code ZipInputStream} cannot see a zip entry's unix mode, so a symlink stored
 * in a zip arrives as a plain file whose content is the link target text. That is harmless (the
 * output has no links at all), and the tar path rejects links outright.
 */
public final class RepoArchive {

    public record Limits(long maxArchiveBytes, long maxTotalBytes, long maxFileBytes, int maxEntries) {
        public static Limits defaults() {
            return new Limits(25L << 20, 60L << 20, 5L << 20, 20_000);
        }
    }

    public record Sanitized(byte[] zip, int fileCount, long totalBytes, int skippedGitEntries) { }

    /** Raised for any archive the harness will not unpack. The message is safe to show the user. */
    public static final class UnsafeArchiveException extends IllegalArgumentException {
        public UnsafeArchiveException(String message) {
            super(message);
        }
    }

    private RepoArchive() {
    }

    public static Sanitized sanitize(byte[] archive, Limits limits) {
        if (archive == null || archive.length == 0) {
            throw new UnsafeArchiveException("The archive is empty.");
        }
        if (archive.length > limits.maxArchiveBytes()) {
            throw new UnsafeArchiveException("The archive is larger than the "
                + (limits.maxArchiveBytes() >> 20) + " MB limit.");
        }
        Collector collector = new Collector(limits);
        try {
            if (archive.length >= 4 && archive[0] == 'P' && archive[1] == 'K') {
                readZip(archive, collector);
            } else if (archive.length >= 2 && (archive[0] & 0xff) == 0x1f && (archive[1] & 0xff) == 0x8b) {
                try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(archive))) {
                    readTar(in, collector);
                }
            } else if (archive.length >= 512 && isTarHeader(archive)) {
                readTar(new ByteArrayInputStream(archive), collector);
            } else {
                throw new UnsafeArchiveException("Unsupported archive format. Upload a .zip, .tar or .tar.gz.");
            }
        } catch (UnsafeArchiveException e) {
            throw e;
        } catch (IOException e) {
            throw new UnsafeArchiveException("The archive could not be read (it may be corrupt).");
        }
        return collector.build();
    }

    // --- collection ---------------------------------------------------------------------------

    private static final class Collector {
        final Limits limits;
        final Map<String, byte[]> files = new LinkedHashMap<>();
        long total;
        int entries;
        int skippedGit;

        Collector(Limits limits) {
            this.limits = limits;
        }

        void entry() {
            if (++entries > limits.maxEntries()) {
                throw new UnsafeArchiveException("The archive has more than " + limits.maxEntries() + " entries.");
            }
        }

        void add(String rawName, byte[] data) {
            String name = safeName(rawName);
            if (name == null) {
                skippedGit++;
                return;
            }
            total += data.length;
            if (total > limits.maxTotalBytes()) {
                throw new UnsafeArchiveException("The archive expands to more than "
                    + (limits.maxTotalBytes() >> 20) + " MB.");
            }
            if (files.put(name, data) != null) {
                throw new UnsafeArchiveException("The archive contains the same path twice: " + name);
            }
        }

        void skipDirectory(String rawName) {
            safeName(rawName.endsWith("/") ? rawName.substring(0, rawName.length() - 1) : rawName);
        }

        Sanitized build() {
            if (files.isEmpty()) {
                throw new UnsafeArchiveException("The archive contains no files.");
            }
            Map<String, byte[]> out = stripCommonRoot(files);
            Set<String> names = out.keySet();
            Set<String> dirs = new HashSet<>();
            for (String name : names) {
                int slash = name.indexOf('/');
                while (slash > 0) {
                    dirs.add(name.substring(0, slash));
                    slash = name.indexOf('/', slash + 1);
                }
            }
            for (String name : names) {
                if (dirs.contains(name)) {
                    throw new UnsafeArchiveException("The archive uses '" + name + "' as both a file and a folder.");
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (Map.Entry<String, byte[]> e : out.entrySet()) {
                    ZipEntry ze = new ZipEntry(e.getKey());
                    ze.setTime(0L);
                    zip.putNextEntry(ze);
                    zip.write(e.getValue());
                    zip.closeEntry();
                }
            } catch (IOException e) {
                throw new UnsafeArchiveException("The archive could not be repacked.");
            }
            long size = out.values().stream().mapToLong(b -> b.length).sum();
            return new Sanitized(bytes.toByteArray(), out.size(), size, skippedGit);
        }
    }

    /** GitHub-style tarballs wrap everything in one {@code repo-sha/} folder; drop it. */
    private static Map<String, byte[]> stripCommonRoot(Map<String, byte[]> files) {
        String root = null;
        for (String name : files.keySet()) {
            int slash = name.indexOf('/');
            if (slash <= 0) {
                return files;
            }
            String first = name.substring(0, slash);
            if (root == null) {
                root = first;
            } else if (!root.equals(first)) {
                return files;
            }
        }
        Map<String, byte[]> stripped = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            stripped.put(e.getKey().substring(root.length() + 1), e.getValue());
        }
        return stripped;
    }

    /**
     * Normalizes an entry name. Returns {@code null} for a {@code .git} entry (skipped); throws for
     * anything that could escape the workspace.
     */
    static String safeName(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new UnsafeArchiveException("The archive has an entry with no name.");
        }
        if (raw.length() > 400) {
            throw new UnsafeArchiveException("The archive has an entry with a very long path.");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\\') {
                throw new UnsafeArchiveException("The archive has an entry with a forbidden character in its path.");
            }
        }
        if (raw.startsWith("/") || raw.matches("^[A-Za-z]:.*")) {
            throw new UnsafeArchiveException("The archive has an absolute path (" + shorten(raw) + ").");
        }
        List<String> kept = new ArrayList<>();
        boolean git = false;
        for (String segment : raw.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new UnsafeArchiveException("The archive tries to write outside the project (" + shorten(raw) + ").");
            }
            if (segment.equalsIgnoreCase(".git")) {
                git = true;
            }
            kept.add(segment);
        }
        if (kept.isEmpty()) {
            throw new UnsafeArchiveException("The archive has an entry with no name.");
        }
        return git ? null : String.join("/", kept);
    }

    private static String shorten(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }

    // --- zip ----------------------------------------------------------------------------------

    private static void readZip(byte[] archive, Collector out) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                out.entry();
                if (entry.isDirectory()) {
                    out.skipDirectory(entry.getName());
                    continue;
                }
                byte[] data = readCapped(zip, out.limits.maxFileBytes(), entry.getName());
                out.add(entry.getName(), data);
            }
        }
    }

    private static byte[] readCapped(InputStream in, long cap, String name) throws IOException {
        byte[] data = in.readNBytes((int) Math.min(cap + 1, Integer.MAX_VALUE - 8));
        if (data.length > cap) {
            throw new UnsafeArchiveException("A file in the archive is larger than the "
                + (cap >> 20) + " MB per-file limit (" + shorten(name) + ").");
        }
        return data;
    }

    // --- tar ----------------------------------------------------------------------------------

    private static boolean isTarHeader(byte[] b) {
        return b.length >= 512 && b[257] == 'u' && b[258] == 's' && b[259] == 't' && b[260] == 'a' && b[261] == 'r';
    }

    private static void readTar(InputStream in, Collector out) throws IOException {
        byte[] header = new byte[512];
        String longName = null;
        String paxPath = null;
        while (true) {
            int read = in.readNBytes(header, 0, 512);
            if (read < 512 || isZeroBlock(header)) {
                return;
            }
            verifyChecksum(header);
            String name = cString(header, 0, 100);
            String prefix = cString(header, 345, 155);
            if (!prefix.isEmpty() && isTarHeader(header)) {
                name = prefix + "/" + name;
            }
            long size = octal(header, 124, 12);
            char type = (char) (header[156] == 0 ? '0' : header[156]);

            switch (type) {
                case 'g' -> skip(in, padded(size));
                case 'x' -> {
                    byte[] pax = readSmall(in, size);
                    paxPath = parsePaxPath(pax, paxPath);
                    skip(in, padded(size) - size);
                }
                case 'L' -> {
                    byte[] data = readSmall(in, size);
                    longName = cString(data, 0, data.length);
                    skip(in, padded(size) - size);
                }
                case '0', '7' -> {
                    out.entry();
                    String effective = paxPath != null ? paxPath : longName != null ? longName : name;
                    paxPath = null;
                    longName = null;
                    if (size > out.limits.maxFileBytes()) {
                        throw new UnsafeArchiveException("A file in the archive is larger than the "
                            + (out.limits.maxFileBytes() >> 20) + " MB per-file limit (" + shorten(effective) + ").");
                    }
                    byte[] data = in.readNBytes((int) size);
                    if (data.length < size) {
                        throw new UnsafeArchiveException("The archive is truncated.");
                    }
                    skip(in, padded(size) - size);
                    out.add(effective, data);
                }
                case '5' -> {
                    out.entry();
                    String effective = paxPath != null ? paxPath : longName != null ? longName : name;
                    paxPath = null;
                    longName = null;
                    out.skipDirectory(effective);
                }
                case '1', '2' -> throw new UnsafeArchiveException(
                    "The archive contains a link (" + shorten(name) + "). Links are not allowed.");
                default -> throw new UnsafeArchiveException(
                    "The archive contains an unsupported entry type (" + shorten(name) + ").");
            }
        }
    }

    private static long padded(long size) {
        return (size + 511) / 512 * 512;
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new UnsafeArchiveException("The archive is truncated.");
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }

    private static byte[] readSmall(InputStream in, long size) throws IOException {
        if (size > 64 * 1024) {
            throw new UnsafeArchiveException("The archive has an oversized header.");
        }
        byte[] data = in.readNBytes((int) size);
        if (data.length < size) {
            throw new UnsafeArchiveException("The archive is truncated.");
        }
        return data;
    }

    private static String parsePaxPath(byte[] pax, String current) {
        String text = new String(pax, StandardCharsets.UTF_8);
        String result = current;
        for (String line : text.split("\n")) {
            int space = line.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String kv = line.substring(space + 1);
            if (kv.startsWith("path=")) {
                result = kv.substring(5);
            } else if (kv.startsWith("linkpath=")) {
                throw new UnsafeArchiveException("The archive contains a link. Links are not allowed.");
            }
        }
        return result;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static void verifyChecksum(byte[] header) {
        long expected = octal(header, 148, 8);
        long sum = 0;
        for (int i = 0; i < 512; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (header[i] & 0xff);
        }
        if (sum != expected) {
            throw new UnsafeArchiveException("The archive is corrupt (bad tar header).");
        }
    }

    private static String cString(byte[] b, int off, int len) {
        int end = off;
        int max = Math.min(b.length, off + len);
        while (end < max && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) {
            throw new UnsafeArchiveException("The archive uses an unsupported size encoding.");
        }
        long v = 0;
        boolean any = false;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') {
                if (any) {
                    break;
                }
                continue;
            }
            if (c < '0' || c > '7') {
                throw new UnsafeArchiveException("The archive is corrupt (bad tar number).");
            }
            any = true;
            v = v * 8 + (c - '0');
        }
        return v;
    }
}
