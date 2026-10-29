package com.agenticai.core.agents.tools.workspacefiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Root-confined file access: resolution, read/write, listing, search and uploads. */
class WorkspaceFilesTest {

    @TempDir Path root;

    private WorkspaceFiles files;

    @BeforeEach
    void setUp() {
        files = new WorkspaceFiles(root);
    }

    @Test
    void availableOnlyForARealDirectory(@TempDir Path other) throws IOException {
        assertTrue(files.available());
        assertFalse(new WorkspaceFiles(other.resolve("missing")).available());
        Path file = other.resolve("a-file");
        Files.writeString(file, "x");
        assertFalse(new WorkspaceFiles(file).available());
    }

    @Test
    void resolveRefusesPathsThatEscapeTheRoot() throws IOException {
        assertThrows(IOException.class, () -> files.resolve("../outside"));
        assertThrows(IOException.class, () -> files.resolve("a/../../b"));
        assertThrows(IOException.class, () -> files.resolve("/../../etc/passwd"));
        // resolve rejects any name containing a NUL character.
        String withNul = "a" + Character.toString(0) + "b";
        assertThrows(IOException.class, () -> files.resolve(withNul));

        Path inside = files.resolve("sub/dir/file.txt");
        assertTrue(inside.startsWith(root.toRealPath()));
        // A symlink inside the root pointing out is refused too.
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, Path.of("/"));
        assertThrows(IOException.class, () -> files.resolve("link/anything"));
    }

    @Test
    void writeCreatesParentsAndReportsWhetherTheFileExisted() throws IOException {
        assertFalse(files.write("deep/nested/f.txt", "hello"));
        assertEquals("hello", Files.readString(root.resolve("deep/nested/f.txt")));
        assertTrue(files.write("deep/nested/f.txt", "again"));
        assertEquals("again", Files.readString(root.resolve("deep/nested/f.txt")));

        assertThrows(IOException.class, () -> files.write("deep", "x"), "writing over a folder fails");
    }

    @Test
    void readReturnsTextAndRefusesMissingOrBinaryFiles() throws IOException {
        Files.writeString(root.resolve("note.txt"), "content");
        assertEquals("content", files.read("note.txt"));

        assertThrows(NoSuchFileException.class, () -> files.read("nope.txt"));
        Files.createDirectory(root.resolve("dir"));
        assertThrows(NoSuchFileException.class, () -> files.read("dir"));

        Files.write(root.resolve("bin.dat"), new byte[] {(byte) 0xff, (byte) 0xfe, 0x01});
        IOException e = assertThrows(IOException.class, () -> files.read("bin.dat"));
        assertTrue(e.getMessage().contains("not a text file"), e.getMessage());
    }

    @Test
    void listSkipsDependencyFoldersAndCanRecurse() throws IOException {
        Files.createDirectories(root.resolve("src/main"));
        Files.createDirectories(root.resolve("node_modules/pkg"));
        Files.writeString(root.resolve("README.md"), "r");
        Files.writeString(root.resolve("src/main/App.java"), "class App {}");
        Files.writeString(root.resolve("node_modules/pkg/index.js"), "x");

        List<WorkspaceFiles.Entry> flat = files.list("", false);
        assertEquals(2, flat.size(), "root listing: README.md and src only");
        assertTrue(flat.stream().anyMatch(e -> e.path().equals("src") && e.directory()));

        List<WorkspaceFiles.Entry> deep = files.list("", true);
        assertTrue(deep.stream().anyMatch(e -> e.path().equals("src/main/App.java")));
        assertTrue(deep.stream().noneMatch(e -> e.path().contains("node_modules")));

        assertThrows(NoSuchFileException.class, () -> files.list("ghost", false));
    }

    @Test
    void searchMatchesNamesThenLines() throws IOException {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/todo.txt"), "first\nbuy milk and eggs\nlast");
        Files.writeString(root.resolve("docs/milk-notes.md"), "no hits here");
        Files.writeString(root.resolve("other.txt"), "nothing");

        List<WorkspaceFiles.Hit> hits = files.search("", "milk");
        // "docs/milk-notes.md" matches by name first; "docs/todo.txt" line 2 matches its content.
        assertEquals("docs/milk-notes.md", hits.get(0).path());
        assertEquals(0, hits.get(0).line());
        assertTrue(
                hits.stream()
                        .anyMatch(h -> h.path().equals("docs/todo.txt") && h.line() == 2));

        assertThrows(IOException.class, () -> files.search("", "  "));
        assertThrows(NoSuchFileException.class, () -> files.search("ghost", "milk"));
    }

    @Test
    void saveUploadNeverOverwritesAndNamesCollisions() throws IOException {
        String first = files.saveUpload("", "a.txt", new byte[] {1});
        assertEquals("a.txt", first);
        String second = files.saveUpload("", "a.txt", new byte[] {2});
        assertEquals("a (2).txt", second);
        String third = files.saveUpload("", "a.txt", new byte[] {3});
        assertEquals("a (3).txt", third);
        assertEquals(3, Files.list(root).count(), "three files, none replaced");

        assertThrows(IOException.class, () -> files.saveUpload("", "..", new byte[] {1}));
        assertThrows(IOException.class, () -> files.saveUpload("", "a/b", new byte[] {1}));
        assertThrows(IOException.class, () -> files.saveUpload("", "a\\b", new byte[] {1}));
        assertThrows(IOException.class, () -> files.saveUpload("", "", new byte[] {1}));
    }
}
