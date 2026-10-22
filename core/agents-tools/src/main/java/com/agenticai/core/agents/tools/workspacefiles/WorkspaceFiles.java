package com.agenticai.core.agents.tools.workspacefiles;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * File access confined to one root folder (the person's projects folder, mounted into core). Every
 * path is relative to the root; it is normalised, symlinks are resolved, and anything that ends up
 * outside the root is refused, so "../", an absolute path or a link pointing out cannot reach the
 * rest of the disk. Writes go through a temporary file and a move, so a reader never sees half a
 * file.
 */
final class WorkspaceFiles {

    static final int MAX_READ_CHARS = 200_000;
    static final int MAX_WRITE_CHARS = 1_000_000;
    static final int MAX_LIST_ENTRIES = 500;

    /**
     * Folders a listing skips: dependency and build output, never what the person wants to read.
     */
    private static final List<String> SKIPPED =
            List.of(".git", "node_modules", "target", "build", ".gradle", ".idea", "dist", ".next");

    static final int MAX_SEARCH_HITS = 60;
    static final long MAX_SEARCH_FILE_BYTES = 4L * 1024 * 1024;
    private static final int MAX_SEARCH_LINE_CHARS = 240;

    record Entry(String path, boolean directory, long size) {}

    /** One match: a file whose name matched ({@code line} 0) or a line inside a text file. */
    record Hit(String path, int line, String text) {}

    private final Path root;

    WorkspaceFiles(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    boolean available() {
        return Files.isDirectory(root);
    }

    /**
     * The absolute path for {@code relative}, refused when it leaves the root. The target may not
     * exist yet.
     */
    Path resolve(String relative) throws IOException {
        String rel = relative == null ? "" : relative.strip().replace('\\', '/');
        if (rel.indexOf('\0') >= 0) {
            throw new IOException("The path contains a NUL character.");
        }
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        Path realRoot = root.toRealPath();
        Path candidate = realRoot.resolve(rel).normalize();
        if (!candidate.startsWith(realRoot)) {
            throw new IOException("'" + relative + "' is outside the workspace.");
        }
        // Resolve links on the deepest part that exists: a link inside the root that points out is
        // refused.
        Path existing = candidate;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing != null && !existing.toRealPath().startsWith(realRoot)) {
            throw new IOException("'" + relative + "' leads outside the workspace.");
        }
        return candidate;
    }

    String relativize(Path abs) throws IOException {
        return root.toRealPath().relativize(abs).toString().replace('\\', '/');
    }

    List<Entry> list(String folder, boolean recursive) throws IOException {
        Path dir = resolve(folder);
        if (!Files.isDirectory(dir)) {
            throw new NoSuchFileException("'" + folder + "' is not a folder in the workspace.");
        }
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> walk = recursive ? Files.walk(dir, 6) : Files.list(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted()::iterator) {
                if (p.equals(dir) || skipped(dir, p)) {
                    continue;
                }
                boolean isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
                out.add(new Entry(relativize(p), isDir, isDir ? 0 : Files.size(p)));
                if (out.size() >= MAX_LIST_ENTRIES) {
                    break;
                }
            }
        }
        return out;
    }

    private static boolean skipped(Path base, Path p) {
        for (Path part : base.relativize(p)) {
            if (SKIPPED.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    String read(String file) throws IOException {
        Path p = resolve(file);
        if (!Files.isRegularFile(p)) {
            throw new NoSuchFileException("'" + file + "' is not a file in the workspace.");
        }
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new IOException("'" + file + "' is not a text file.");
        }
    }

    /**
     * Writes the whole file, creating missing parent folders. Returns true when it already existed.
     */
    boolean write(String file, String content) throws IOException {
        if (content.length() > MAX_WRITE_CHARS) {
            throw new IOException(
                    "The content is over " + MAX_WRITE_CHARS + " characters; write it in parts.");
        }
        Path p = resolve(file);
        if (Files.isDirectory(p)) {
            throw new IOException("'" + file + "' is a folder.");
        }
        boolean existed = Files.exists(p);
        createSharedDirectories(p.getParent());
        Path tmp = Files.createTempFile(p.getParent(), ".vd-write-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            shareWithSandbox(tmp, "rw-rw-rw-");
            try {
                Files.move(
                        tmp,
                        p,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some shared folders (Docker Desktop bind mounts) cannot rename atomically.
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return existed;
    }

    /**
     * Case-insensitive search under {@code folder}: file names containing the query, then lines of
     * text files containing it (every word of the query must appear on the line). Binary and
     * oversized files are skipped; the same dependency and build folders as a listing are skipped.
     * Stops at {@link #MAX_SEARCH_HITS} hits.
     */
    List<Hit> search(String folder, String query) throws IOException {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            throw new IOException("query is required");
        }
        String[] words = q.split("\\s+");
        Path dir = resolve(folder);
        if (!Files.isDirectory(dir)) {
            throw new NoSuchFileException("'" + folder + "' is not a folder in the workspace.");
        }
        List<Hit> nameHits = new ArrayList<>();
        List<Hit> lineHits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir, 8)) {
            for (Path p : (Iterable<Path>) walk.sorted()::iterator) {
                if (p.equals(dir)
                        || skipped(dir, p)
                        || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String rel = relativize(p);
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (nameHits.size() < MAX_SEARCH_HITS && containsAll(name, words)) {
                    nameHits.add(new Hit(rel, 0, ""));
                }
                if (lineHits.size() >= MAX_SEARCH_HITS || Files.size(p) > MAX_SEARCH_FILE_BYTES) {
                    continue;
                }
                searchLines(p, rel, words, lineHits);
                if (nameHits.size() + lineHits.size() >= MAX_SEARCH_HITS * 2) {
                    break;
                }
            }
        }
        List<Hit> out = new ArrayList<>(nameHits);
        out.addAll(lineHits);
        return out.size() > MAX_SEARCH_HITS ? out.subList(0, MAX_SEARCH_HITS) : out;
    }

    private static void searchLines(Path p, String rel, String[] words, List<Hit> out) {
        try (Stream<String> lines = Files.lines(p, StandardCharsets.UTF_8)) {
            int[] n = {0};
            for (String line : (Iterable<String>) lines::iterator) {
                n[0]++;
                if (out.size() >= MAX_SEARCH_HITS) {
                    return;
                }
                if (containsAll(line.toLowerCase(Locale.ROOT), words)) {
                    String text = line.strip();
                    out.add(
                            new Hit(
                                    rel,
                                    n[0],
                                    text.length() > MAX_SEARCH_LINE_CHARS
                                            ? text.substring(0, MAX_SEARCH_LINE_CHARS) + "..."
                                            : text));
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            // A binary file (not UTF-8) or one that vanished: nothing to search in it.
        }
    }

    private static boolean containsAll(String haystack, String[] words) {
        for (String w : words) {
            if (!haystack.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Saves uploaded bytes as {@code folder/name}, never replacing a file (a clash becomes "name
     * (2).ext"). Returns the relative path.
     */
    String saveUpload(String folder, String name, byte[] bytes) throws IOException {
        // The controller's safeName already reduces a name to a bare file name; this is the second
        // lock.
        if (name == null
                || name.isBlank()
                || name.equals(".")
                || name.equals("..")
                || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0) {
            throw new IOException("'" + name + "' is not a plain file name.");
        }
        Path dir = resolve(folder);
        if (Files.exists(dir) && !Files.isDirectory(dir)) {
            throw new IOException("'" + folder + "' is not a folder.");
        }
        createSharedDirectories(dir);
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        Path target = dir.resolve(name);
        for (int i = 2; Files.exists(target, LinkOption.NOFOLLOW_LINKS); i++) {
            target = dir.resolve(base + " (" + i + ")" + ext);
        }
        resolve(relativize(dir) + "/" + target.getFileName());
        Files.write(target, bytes);
        shareWithSandbox(target, "rw-rw-rw-");
        return relativize(target);
    }

    /**
     * Creates {@code dir} and any missing parents, opening the new folders to the sandbox user. The
     * tool process and the shell sandbox run as different users on the same volume, and
     * Files.createTempFile makes owner-only (0600) files, so without this a dev server started in
     * the sandbox cannot read what the agent just wrote (a blank preview).
     */
    private static void createSharedDirectories(Path dir) throws IOException {
        Path firstMissing = dir;
        while (firstMissing.getParent() != null && !Files.exists(firstMissing.getParent())) {
            firstMissing = firstMissing.getParent();
        }
        boolean created = !Files.exists(dir);
        Files.createDirectories(dir);
        if (created) {
            for (Path d = dir; d != null && d.startsWith(firstMissing); d = d.getParent()) {
                shareWithSandbox(d, "rwxrwxrwx");
            }
        }
    }

    /** Best effort: a filesystem without POSIX permissions keeps its defaults. */
    private static void shareWithSandbox(Path path, String perms) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not POSIX, or not our file: leave it as it is
        }
    }
}
