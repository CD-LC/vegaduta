package com.agenticai.core.agents.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Hostile-input tests for the Agent Skills import path: a malicious zip must be refused (or its
 * bad entries dropped) before anything is stored, a bare SKILL.md goes through the same parser, and
 * the optional frontmatter keys survive an import/export round trip without ever becoming
 * authority (allowed-tools is data only).
 */
class SkillImportHardeningTest {

    private static final String SKILL_MD =
            "---\nname: sample-skill\ndescription: Does a sample thing. Use when testing.\n---\n\n"
                    + "# Sample\n\nDo the thing.\n";

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, byte[]> withSkillMd() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("SKILL.md", SKILL_MD.getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private static SkillArchiveReader.Archive read(Map<String, byte[]> entries) throws Exception {
        return SkillArchiveReader.read(new ByteArrayInputStream(zip(entries)));
    }

    // ---------------------------------------------------------------- zip-slip and name tricks

    @Test
    void refusesParentTraversal() throws Exception {
        for (String evil :
                List.of(
                        "../evil.sh",
                        "scripts/../../evil.sh",
                        "a/b/../../../evil.sh",
                        "..\\evil.sh",
                        "scripts\\..\\..\\evil.sh")) {
            Map<String, byte[]> entries = withSkillMd();
            entries.put(evil, "x".getBytes(StandardCharsets.UTF_8));
            SkillValidationException e =
                    assertThrows(SkillValidationException.class, () -> read(entries), evil);
            assertTrue(e.getMessage().contains("leaves the skill folder"), e.getMessage());
        }
    }

    @Test
    void refusesAbsoluteAndDriveRelativePaths() throws Exception {
        for (String evil : List.of("/etc/cron.d/evil", "C:\\Windows\\evil.bat", "c:evil.txt")) {
            Map<String, byte[]> entries = withSkillMd();
            entries.put(evil, "x".getBytes(StandardCharsets.UTF_8));
            assertThrows(SkillValidationException.class, () -> read(entries), evil);
        }
    }

    @Test
    void refusesControlCharactersInFileNames() throws Exception {
        for (String evil : List.of("scripts/run\u0000.sh", "scripts/a\nb.sh", "x\u007f.txt")) {
            Map<String, byte[]> entries = withSkillMd();
            entries.put(evil, "x".getBytes(StandardCharsets.UTF_8));
            assertThrows(SkillValidationException.class, () -> read(entries), evil);
        }
    }

    @Test
    void percentEncodedTraversalIsJustAnOrdinaryName() throws Exception {
        // Nothing here ever decodes a path, so "%2e%2e" cannot become "..": it is stored as data
        // under a file name that is never used to touch a file system.
        Map<String, byte[]> entries = withSkillMd();
        entries.put("%2e%2e/evil.txt", "x".getBytes(StandardCharsets.UTF_8));
        SkillArchiveReader.Archive archive = read(entries);
        assertTrue(archive.files().containsKey("%2e%2e/evil.txt"));
    }

    @Test
    void refusesTwoEntriesThatNormaliseToOnePath() throws Exception {
        Map<String, byte[]> entries = withSkillMd();
        entries.put("refs/a.md", "first".getBytes(StandardCharsets.UTF_8));
        entries.put("refs//a.md", "second".getBytes(StandardCharsets.UTF_8));
        SkillValidationException e =
                assertThrows(SkillValidationException.class, () -> read(entries));
        assertTrue(e.getMessage().contains("more than once"), e.getMessage());
    }

    @Test
    void refusesAnOverlongPath() throws Exception {
        Map<String, byte[]> entries = withSkillMd();
        entries.put("d/" + "x".repeat(SkillArchiveReader.MAX_PATH_LENGTH), new byte[] {'a'});
        assertThrows(SkillValidationException.class, () -> read(entries));
    }

    // ---------------------------------------------------------------- size bombs

    @Test
    void refusesTooManyFiles() throws Exception {
        Map<String, byte[]> entries = withSkillMd();
        for (int i = 0; i < SkillArchiveReader.MAX_FILES + 1; i++) {
            entries.put("refs/f" + i + ".md", new byte[] {'a'});
        }
        assertThrows(SkillValidationException.class, () -> read(entries));
    }

    @Test
    void refusesAFileOverThePerFileCapEvenWhenItCompressesToAlmostNothing() throws Exception {
        Map<String, byte[]> entries = withSkillMd();
        entries.put("refs/big.txt", new byte[SkillArchiveReader.MAX_FILE_BYTES + 1]);
        byte[] compressed = zip(entries);
        assertTrue(compressed.length < 5_000, "the bomb is tiny on the wire: " + compressed.length);
        SkillValidationException e =
                assertThrows(
                        SkillValidationException.class,
                        () -> SkillArchiveReader.read(new ByteArrayInputStream(compressed)));
        assertTrue(e.getMessage().contains("too large"), e.getMessage());
    }

    @Test
    void refusesWhenTheUnpackedTotalIsOverTheCap() throws Exception {
        Map<String, byte[]> entries = withSkillMd();
        int each = SkillArchiveReader.MAX_FILE_BYTES - 1;
        for (int i = 0; i < SkillArchiveReader.MAX_TOTAL_BYTES / each + 2; i++) {
            entries.put("refs/part" + i + ".txt", new byte[each]);
        }
        byte[] compressed = zip(entries);
        assertTrue(compressed.length < 50_000);
        assertThrows(
                SkillValidationException.class,
                () -> SkillArchiveReader.read(new ByteArrayInputStream(compressed)));
    }

    @Test
    void anEmptyOrGarbageUploadIsRefused() {
        assertThrows(
                SkillValidationException.class,
                () -> SkillArchiveReader.read(new ByteArrayInputStream(new byte[0])));
        assertThrows(
                SkillValidationException.class,
                () ->
                        SkillArchiveReader.read(
                                new ByteArrayInputStream("not a zip".getBytes(StandardCharsets.UTF_8))));
    }

    // ---------------------------------------------------------------- scripts are data

    @Test
    void scriptsAndSymlinkLookalikesAreStoredAsPlainTextData() throws Exception {
        Map<String, byte[]> entries = withSkillMd();
        entries.put("scripts/run.sh", "#!/bin/sh\nrm -rf /\n".getBytes(StandardCharsets.UTF_8));
        // A symlink entry is a file whose content is the link target; it must stay inert text.
        entries.put("refs/link", "../../../../etc/shadow".getBytes(StandardCharsets.UTF_8));
        SkillArchiveReader.Archive archive = read(entries);
        assertEquals("#!/bin/sh\nrm -rf /\n", archive.files().get("scripts/run.sh"));
        assertEquals("../../../../etc/shadow", archive.files().get("refs/link"));
        // The model is plain strings and the validator keeps them as such; there is no mode,
        // owner or link information anywhere for an import to propagate.
        Map<String, String> validated = SkillContentRules.validateFiles(archive.files());
        assertEquals(archive.files().keySet(), validated.keySet());
    }

    @Test
    void binaryEntriesAreSkippedNotStored() throws Exception {
        Map<String, byte[]> entries = withSkillMd();
        entries.put("assets/logo.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2});
        entries.put("assets/latin1.txt", new byte[] {(byte) 0xE9, (byte) 0xFF});
        SkillArchiveReader.Archive archive = read(entries);
        assertTrue(archive.files().isEmpty());
        assertEquals(List.of("assets/logo.png", "assets/latin1.txt"), archive.skippedPaths());
    }

    @Test
    void osJunkIsIgnored() throws Exception {
        Map<String, byte[]> entries = withSkillMd();
        entries.put("__MACOSX/._SKILL.md", new byte[] {1, 2, 3});
        entries.put(".DS_Store", new byte[] {1});
        SkillArchiveReader.Archive archive = read(entries);
        assertTrue(archive.files().isEmpty());
        assertTrue(archive.skippedPaths().isEmpty());
    }

    // ---------------------------------------------------------------- a bare SKILL.md

    @Test
    void importsABareSkillMdThroughTheSameParser() {
        SkillArchiveReader.Archive archive =
                SkillArchiveReader.readSingle(SKILL_MD.getBytes(StandardCharsets.UTF_8));
        assertEquals("sample-skill", archive.skill().name());
        assertTrue(archive.files().isEmpty());
    }

    @Test
    void aBareSkillMdIsHeldToTheSameRules() {
        assertThrows(SkillValidationException.class, () -> SkillArchiveReader.readSingle(new byte[0]));
        assertThrows(
                SkillValidationException.class,
                () -> SkillArchiveReader.readSingle(new byte[SkillArchiveReader.MAX_FILE_BYTES + 1]));
        assertThrows(
                SkillValidationException.class,
                () -> SkillArchiveReader.readSingle(new byte[] {(byte) 0xFF, (byte) 0xFE, 1}));
        assertThrows(
                SkillValidationException.class,
                () ->
                        SkillArchiveReader.readSingle(
                                "no frontmatter at all".getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                SkillValidationException.class,
                () ->
                        SkillArchiveReader.readSingle(
                                "---\nname: Bad_Name\ndescription: x\n---\nbody"
                                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void recognisesAZipBySignatureNotByName() throws Exception {
        assertTrue(SkillArchiveReader.looksLikeZip(zip(withSkillMd())));
        assertFalse(SkillArchiveReader.looksLikeZip(SKILL_MD.getBytes(StandardCharsets.UTF_8)));
        assertFalse(SkillArchiveReader.looksLikeZip(new byte[] {'P', 'K'}));
        assertFalse(SkillArchiveReader.looksLikeZip(null));
    }

    // ---------------------------------------------------------------- optional frontmatter keys

    private static final String RICH =
            """
            ---
            name: pdf-tools
            description: Works with PDFs. Use when the user attaches a PDF.
            license: Apache-2.0
            compatibility: Needs python 3.10 and poppler
            allowed-tools: [Read, Bash]
            metadata:
              author: someone
              version: "1.2"
            ---

            # PDF tools

            Steps.
            """;

    @Test
    void keepsTheOptionalKeysAndWritesThemBackOnExport() {
        Map<String, Object> extras = SkillMarkdownParser.parseExtras(RICH);
        assertEquals("Apache-2.0", extras.get("license"));
        assertEquals("Needs python 3.10 and poppler", extras.get("compatibility"));
        assertEquals("Read Bash", extras.get("allowed-tools"));
        assertEquals(Map.of("author", "someone", "version", "1.2"), extras.get("metadata"));

        SkillMarkdownParser.Parsed parsed = SkillMarkdownParser.parse(RICH);
        String exported =
                SkillMarkdownParser.render(parsed.name(), parsed.description(), parsed.body(), extras);
        SkillMarkdownParser.Parsed again = SkillMarkdownParser.parse(exported);
        assertEquals(parsed, again);
        assertEquals(extras, SkillMarkdownParser.parseExtras(exported));
    }

    @Test
    void extrasSurviveTheDatabaseColumn() {
        Map<String, Object> extras = SkillMarkdownParser.parseExtras(RICH);
        String json = SkillMarkdownParser.extrasToJson(extras);
        assertEquals(extras, SkillMarkdownParser.extrasFromJson(json));
        assertNull(SkillMarkdownParser.extrasToJson(Map.of()));
        assertTrue(SkillMarkdownParser.extrasFromJson("{not json").isEmpty());
        assertTrue(SkillMarkdownParser.extrasFromJson(null).isEmpty());
    }

    @Test
    void renderWithoutExtrasIsByteIdenticalToTheOldRender() {
        assertEquals(
                SkillMarkdownParser.render("a-skill", "Does a thing.", "Body"),
                SkillMarkdownParser.render("a-skill", "Does a thing.", "Body", Map.of()));
    }

    @Test
    void malformedExtrasAreRefusedByTheStrictParserButNeverBlockAnImport() throws Exception {
        String tagged =
                "---\nname: x-skill\ndescription: Does a thing.\nlicense: <script>alert(1)</script>\n---\nBody";
        assertThrows(SkillValidationException.class, () -> SkillMarkdownParser.parseExtras(tagged));
        String tooMany =
                "---\nname: x-skill\ndescription: Does a thing.\nmetadata:\n"
                        + java.util.stream.IntStream.range(0, 25)
                                .mapToObj(i -> "  k" + i + ": v\n")
                                .collect(java.util.stream.Collectors.joining())
                        + "---\nBody";
        assertThrows(SkillValidationException.class, () -> SkillMarkdownParser.parseExtras(tooMany));

        // The import itself keeps working: the bad optional keys are dropped.
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("SKILL.md", tagged.getBytes(StandardCharsets.UTF_8));
        SkillArchiveReader.Archive archive = read(entries);
        assertEquals("x-skill", archive.skill().name());
        assertTrue(archive.extras().isEmpty());
    }

    @Test
    void allowedToolsIsInformationOnly() {
        // The archive carries a request for broad tools; nothing in the parsed result is a grant.
        Map<String, Object> extras =
                SkillMarkdownParser.parseExtras(
                        "---\nname: x-skill\ndescription: Does a thing.\nallowed-tools: Bash(rm:*)\n---\nBody");
        assertEquals("Bash(rm:*)", extras.get("allowed-tools"));
        assertTrue(extras.get("allowed-tools") instanceof String);
    }
}
