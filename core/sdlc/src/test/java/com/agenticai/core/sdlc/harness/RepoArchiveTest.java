package com.agenticai.core.sdlc.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class RepoArchiveTest {

    private static final RepoArchive.Limits LIMITS = new RepoArchive.Limits(1 << 20, 4 << 20, 1 << 20, 50);

    // --- builders -------------------------------------------------------------------------------

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (var e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void tarEntry(ByteArrayOutputStream out, String name, char type, String content, String linkName)
        throws IOException {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        byte[] h = new byte[512];
        put(h, 0, name, 100);
        put(h, 100, "0000644", 8);
        put(h, 108, "0000000", 8);
        put(h, 116, "0000000", 8);
        put(h, 124, String.format("%011o", data.length), 12);
        put(h, 136, "00000000000", 12);
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        h[156] = (byte) type;
        put(h, 157, linkName, 100);
        put(h, 257, "ustar", 6);
        h[263] = '0';
        h[264] = '0';
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        put(h, 148, String.format("%06o", sum), 7);
        h[155] = ' ';
        out.write(h);
        out.write(data);
        int pad = (512 - data.length % 512) % 512;
        out.write(new byte[pad]);
    }

    private static void put(byte[] h, int off, String s, int max) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, h, off, Math.min(b.length, max - 1 < 0 ? 0 : max));
    }

    private static byte[] tar(Object... triples) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < triples.length; i += 4) {
            tarEntry(out, (String) triples[i], (Character) triples[i + 1], (String) triples[i + 2], (String) triples[i + 3]);
        }
        out.write(new byte[1024]);
        return out.toByteArray();
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) {
            g.write(data);
        }
        return out.toByteArray();
    }

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                out.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    // --- happy paths ------------------------------------------------------------------------------

    @Test
    void repacksAZipAsRegularFilesOnly() throws Exception {
        var s = RepoArchive.sanitize(zip(Map.of("a.txt", "A", "src/b.txt", "B")), LIMITS);
        assertEquals(2, s.fileCount());
        assertEquals(Map.of("a.txt", "A", "src/b.txt", "B"), unzip(s.zip()));
    }

    @Test
    void readsTarGzAndStripsTheGithubStyleRootFolder() throws Exception {
        byte[] tgz = gzip(tar("repo-abc/", '5', "", "", "repo-abc/pom.xml", '0', "<project/>", "", "repo-abc/src/A.java", '0', "class A{}", ""));
        var s = RepoArchive.sanitize(tgz, LIMITS);
        assertEquals(Map.of("pom.xml", "<project/>", "src/A.java", "class A{}"), unzip(s.zip()));
    }

    @Test
    void readsPlainTar() throws Exception {
        var s = RepoArchive.sanitize(tar("x.txt", '0', "hi", "", "y/z.txt", '0', "yo", ""), LIMITS);
        assertEquals(Map.of("x.txt", "hi", "y/z.txt", "yo"), unzip(s.zip()));
    }

    @Test
    void dropsGitDirectoriesSilently() throws Exception {
        var s = RepoArchive.sanitize(zip(Map.of("a.txt", "A", ".git/config", "[core]", "sub/.git/HEAD", "x")), LIMITS);
        assertEquals(Map.of("a.txt", "A"), unzip(s.zip()));
        assertEquals(2, s.skippedGitEntries());
    }

    // --- zip-slip -----------------------------------------------------------------------------------

    @Test
    void rejectsZipSlipTraversal() throws Exception {
        assertThrows(RepoArchive.UnsafeArchiveException.class,
            () -> RepoArchive.sanitize(zip(Map.of("ok.txt", "x", "../../etc/cron.d/evil", "boom")), LIMITS));
        assertThrows(RepoArchive.UnsafeArchiveException.class,
            () -> RepoArchive.sanitize(zip(Map.of("a/../../evil", "boom")), LIMITS));
    }

    @Test
    void rejectsAbsoluteAndWindowsStylePaths() throws Exception {
        assertThrows(RepoArchive.UnsafeArchiveException.class, () -> RepoArchive.sanitize(zip(Map.of("/etc/passwd", "x")), LIMITS));
        assertThrows(RepoArchive.UnsafeArchiveException.class, () -> RepoArchive.sanitize(zip(Map.of("C:/x/y", "x")), LIMITS));
        assertThrows(RepoArchive.UnsafeArchiveException.class, () -> RepoArchive.sanitize(zip(Map.of("a\\..\\b", "x")), LIMITS));
    }

    @Test
    void rejectsTarTraversalToo() throws Exception {
        assertThrows(RepoArchive.UnsafeArchiveException.class,
            () -> RepoArchive.sanitize(tar("../../evil", '0', "boom", ""), LIMITS));
    }

    // --- links ------------------------------------------------------------------------------------

    @Test
    void rejectsTarSymlinksAndHardlinks() throws Exception {
        assertThrows(RepoArchive.UnsafeArchiveException.class,
            () -> RepoArchive.sanitize(tar("ok", '0', "x", "", "link", '2', "", "/etc"), LIMITS));
        assertThrows(RepoArchive.UnsafeArchiveException.class,
            () -> RepoArchive.sanitize(tar("ok", '0', "x", "", "hard", '1', "", "ok"), LIMITS));
        assertThrows(RepoArchive.UnsafeArchiveException.class,
            () -> RepoArchive.sanitize(gzip(tar("dev", '3', "", "")), LIMITS));
    }

    @Test
    void zipSymlinkIsMaterialisedAsAPlainFileNeverALink() throws Exception {
        // A zip symlink entry is just a tiny file whose content is the target path; the output has no links.
        var s = RepoArchive.sanitize(zip(Map.of("a.txt", "A", "link", "/etc/passwd")), LIMITS);
        Map<String, String> out = unzip(s.zip());
        assertEquals("/etc/passwd", out.get("link"));
        assertFalse(out.containsKey("etc/passwd"));
    }

    @Test
    void rejectsAFileUsedAsAFolder() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put("link", "x");
        entries.put("link/inner.txt", "y");
        assertThrows(RepoArchive.UnsafeArchiveException.class, () -> RepoArchive.sanitize(zip(entries), LIMITS));
    }

    // --- size caps --------------------------------------------------------------------------------

    @Test
    void rejectsAnOversizedArchive() {
        byte[] big = new byte[(int) LIMITS.maxArchiveBytes() + 1];
        big[0] = 'P';
        big[1] = 'K';
        assertThrows(RepoArchive.UnsafeArchiveException.class, () -> RepoArchive.sanitize(big, LIMITS));
    }

    @Test
    void rejectsAZipBombByTotalExpandedSize() throws Exception {
        // 5 MB of zeros compresses to a few KB: the archive is small, the expansion is not.
        String five = "0".repeat(1 << 20);
        var entries = new LinkedHashMap<String, String>();
        for (int i = 0; i < 6; i++) {
            entries.put("f" + i + ".txt", five);
        }
        byte[] z = zip(entries);
        assertTrue(z.length < LIMITS.maxArchiveBytes());
        var ex = assertThrows(RepoArchive.UnsafeArchiveException.class, () -> RepoArchive.sanitize(z, LIMITS));
        assertTrue(ex.getMessage().contains("expands"), ex.getMessage());
    }

    @Test
    void rejectsASingleFileOverThePerFileCap() throws Exception {
        var limits = new RepoArchive.Limits(1 << 20, 4 << 20, 1000, 50);
        assertThrows(RepoArchive.UnsafeArchiveException.class,
            () -> RepoArchive.sanitize(zip(Map.of("big.txt", "x".repeat(2000))), limits));
    }

    @Test
    void rejectsTooManyEntries() throws Exception {
        var entries = new LinkedHashMap<String, String>();
        for (int i = 0; i < 60; i++) {
            entries.put("f" + i, "x");
        }
        assertThrows(RepoArchive.UnsafeArchiveException.class, () -> RepoArchive.sanitize(zip(entries), LIMITS));
    }

    @Test
    void rejectsEmptyUnknownAndCorruptInput() {
        assertThrows(RepoArchive.UnsafeArchiveException.class, () -> RepoArchive.sanitize(new byte[0], LIMITS));
        assertThrows(RepoArchive.UnsafeArchiveException.class, () -> RepoArchive.sanitize(null, LIMITS));
        assertThrows(RepoArchive.UnsafeArchiveException.class,
            () -> RepoArchive.sanitize("just some text".getBytes(StandardCharsets.UTF_8), LIMITS));
        assertThrows(RepoArchive.UnsafeArchiveException.class,
            () -> RepoArchive.sanitize(new byte[] {0x1f, (byte) 0x8b, 1, 2, 3, 4}, LIMITS));
    }
}
