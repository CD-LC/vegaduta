package com.agenticai.core.agents.tools.workspacefiles;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFileToolCallbackTest {

    @TempDir Path root;
    @TempDir Path outside;
    WorkspaceFiles files;

    @BeforeEach
    void setUp() throws Exception {
        files = new WorkspaceFiles(root);
        Files.createDirectories(root.resolve("demo/src"));
        Files.writeString(root.resolve("demo/README.md"), "line one\nline two\n");
        Files.writeString(outside.resolve("secret.txt"), "nope");
        Files.createSymbolicLink(root.resolve("demo/escape"), outside);
    }

    private JsonNode call(WorkspaceFileToolCallback.Kind kind, String json) throws Exception {
        return JsonMappers.plain()
                .readTree(new WorkspaceFileToolCallback(files, kind, "/workspace").call(json));
    }

    @Test
    void writesCreatesFoldersAndReadsBackWithLineNumbers() throws Exception {
        JsonNode w =
                call(
                        WorkspaceFileToolCallback.Kind.WRITE,
                        "{\"path\":\"demo/src/App.java\",\"content\":\"class App {}\\n\"}");
        assertThat(w.path("created").asBoolean()).isTrue();
        JsonNode r = call(WorkspaceFileToolCallback.Kind.READ, "{\"path\":\"demo/src/App.java\"}");
        assertThat(r.path("content").asText()).startsWith("1\tclass App {}");
    }

    @Test
    void writtenFilesAndNewFoldersAreOpenToTheSandboxUser() throws Exception {
        call(
                WorkspaceFileToolCallback.Kind.WRITE,
                "{\"path\":\"site/index.html\",\"content\":\"<h1>hi</h1>\"}");
        assertThat(
                        java.nio.file.attribute.PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(root.resolve("site/index.html"))))
                .isEqualTo("rw-rw-rw-");
        assertThat(
                        java.nio.file.attribute.PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(root.resolve("site"))))
                .isEqualTo("rwxrwxrwx");
    }

    @Test
    void editReplacesExactlyOneOccurrence() throws Exception {
        assertThat(
                        call(
                                        WorkspaceFileToolCallback.Kind.EDIT,
                                        "{\"path\":\"demo/README.md\",\"old_text\":\"line\",\"new_text\":\"x\"}")
                                .path("error")
                                .asText())
                .contains("more than once");
        assertThat(
                        call(
                                        WorkspaceFileToolCallback.Kind.EDIT,
                                        "{\"path\":\"demo/README.md\",\"old_text\":\"missing\",\"new_text\":\"x\"}")
                                .path("error")
                                .asText())
                .contains("not found");
        call(
                WorkspaceFileToolCallback.Kind.EDIT,
                "{\"path\":\"demo/README.md\",\"old_text\":\"line two\",\"new_text\":\"line 2\"}");
        assertThat(Files.readString(root.resolve("demo/README.md")))
                .isEqualTo("line one\nline 2\n");
    }

    @Test
    void nothingOutsideTheWorkspaceCanBeReadOrWritten() throws Exception {
        for (String path :
                new String[] {
                    "../x.txt", "/etc/passwd", "demo/../../x", "demo/escape/secret.txt"
                }) {
            assertThat(
                            call(WorkspaceFileToolCallback.Kind.READ, "{\"path\":\"" + path + "\"}")
                                    .has("error"))
                    .as(path)
                    .isTrue();
        }
        assertThat(
                        call(
                                        WorkspaceFileToolCallback.Kind.WRITE,
                                        "{\"path\":\"demo/escape/new.txt\",\"content\":\"x\"}")
                                .has("error"))
                .isTrue();
        assertThat(Files.exists(outside.resolve("new.txt"))).isFalse();
    }

    @Test
    void listingSkipsBuildOutputAndShowsSizes() throws Exception {
        Files.createDirectories(root.resolve("demo/node_modules/pkg"));
        Files.createDirectories(root.resolve("demo/target/classes"));
        JsonNode l =
                call(WorkspaceFileToolCallback.Kind.LIST, "{\"path\":\"demo\",\"recursive\":true}");
        assertThat(l.path("entries").toString())
                .contains("demo/README.md")
                .contains("demo/src")
                .doesNotContain("node_modules")
                .doesNotContain("target");
    }

    @Test
    void longFilesAreReadInPages() throws Exception {
        Files.writeString(root.resolve("demo/long.txt"), "a\nb\nc\nd\n");
        JsonNode r =
                call(
                        WorkspaceFileToolCallback.Kind.READ,
                        "{\"path\":\"demo/long.txt\",\"offset\":2,\"limit\":2}");
        assertThat(r.path("content").asText()).isEqualTo("2\tb\n3\tc\n");
    }

    private String readWithLimit(int outputLimit, String json) throws Exception {
        return new WorkspaceFileToolCallback(files, WorkspaceFileToolCallback.Kind.READ, "/workspace", outputLimit)
                .call(json);
    }

    @Test
    void aReadStopsEarlyEnoughThatTheWholeAnswerFitsTheOutputLimitAndTheNoteSurvives() throws Exception {
        // 400 numbered lines of 20 characters, with a quote on each so JSON escaping inflates the content.
        StringBuilder body = new StringBuilder();
        for (int n = 1; n <= 400; n++) {
            body.append("row \"").append(n).append("\" xxxxxxxxxx\n");
        }
        Files.writeString(root.resolve("demo/big.txt"), body.toString());

        String answer = readWithLimit(2_000, "{\"path\":\"demo/big.txt\"}");
        JsonNode r = JsonMappers.plain().readTree(answer);

        assertThat(answer.length()).as("whole answer incl. JSON escaping").isLessThanOrEqualTo(2_000);
        assertThat(r.path("note").asText()).startsWith("Output cut at line ").contains("read on with offset=");
        int next = Integer.parseInt(r.path("note").asText().replaceAll(".*offset=(\\d+)\\..*", "$1"));
        String content = r.path("content").asText();
        assertThat(content).endsWith("row \"" + (next - 1) + "\" xxxxxxxxxx\n");
        JsonNode second = JsonMappers.plain().readTree(
                readWithLimit(2_000, "{\"path\":\"demo/big.txt\",\"offset\":" + next + "}"));
        assertThat(second.path("content").asText()).startsWith(next + "\trow ");
    }

    @Test
    void aSingleLineWiderThanAPageStillMakesProgress() throws Exception {
        Files.writeString(root.resolve("demo/min.js"), "x".repeat(50_000) + "\nsecond\n");

        JsonNode r = JsonMappers.plain().readTree(readWithLimit(2_000, "{\"path\":\"demo/min.js\"}"));

        assertThat(r.path("content").asText()).isNotEmpty().startsWith("1\txxx");
        assertThat(r.path("note").asText()).contains("offset=2");
    }

    @Test
    void searchFindsFileNamesAndLinesAndSkipsBinaryAndEscapes() throws Exception {
        Files.writeString(
                root.resolve("demo/Invoice March.txt"), "Total due: 420 EUR\nPaid by card\n");
        Files.write(
                root.resolve("demo/blob.bin"),
                new byte[] {(byte) 0xff, (byte) 0xfe, 'i', 'n', 'v', 'o', 'i', 'c', 'e'});
        JsonNode byName =
                call(
                        WorkspaceFileToolCallback.Kind.SEARCH,
                        "{\"query\":\"invoice\",\"path\":\"demo\"}");
        assertThat(byName.path("matches").toString())
                .contains("demo/Invoice March.txt")
                .doesNotContain("blob.bin");
        JsonNode byLine = call(WorkspaceFileToolCallback.Kind.SEARCH, "{\"query\":\"TOTAL eur\"}");
        assertThat(byLine.path("matches").get(0).path("line").asInt()).isEqualTo(1);
        assertThat(byLine.path("matches").get(0).path("text").asText())
                .isEqualTo("Total due: 420 EUR");
        assertThat(
                        call(
                                        WorkspaceFileToolCallback.Kind.SEARCH,
                                        "{\"query\":\"nonexistentword\"}")
                                .path("note")
                                .asText())
                .contains("No file name");
        assertThat(
                        call(
                                        WorkspaceFileToolCallback.Kind.SEARCH,
                                        "{\"query\":\"nope\",\"path\":\"demo/escape\"}")
                                .has("error"))
                .isTrue();
        assertThat(call(WorkspaceFileToolCallback.Kind.SEARCH, "{\"query\":\" \"}").has("error"))
                .isTrue();
    }

    @Test
    void uploadsNeverReplaceAnExistingFile() throws Exception {
        String first = files.saveUpload("Chat uploads", "notes.txt", "one".getBytes());
        String second = files.saveUpload("Chat uploads", "notes.txt", "two".getBytes());
        assertThat(first).isEqualTo("Chat uploads/notes.txt");
        assertThat(second).isEqualTo("Chat uploads/notes (2).txt");
        assertThat(Files.readString(root.resolve(first))).isEqualTo("one");
        assertThat(WorkspaceFolderController.safeName("../../etc/.passwd")).isEqualTo("passwd");
        assertThat(WorkspaceFolderController.safeName("  ")).isEqualTo("attachment");
    }

    @Test
    void saveUploadRefusesANameThatIsNotAPlainFileName() throws Exception {
        for (String bad :
                new String[] {
                    "../escape.txt", "..", ".", "a/b.txt", "a\\b.txt", "/abs.txt", "", "  ", "x\0y"
                }) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.io.IOException.class,
                    () -> files.saveUpload("Chat uploads", bad, "x".getBytes()),
                    bad);
        }
        org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class,
                () -> files.saveUpload("Chat uploads", null, "x".getBytes()));
        assertThat(root.resolve("escape.txt")).doesNotExist();
        assertThat(files.saveUpload("Chat uploads", "fine (1).txt", "ok".getBytes()))
                .isEqualTo("Chat uploads/fine (1).txt");
    }
}
