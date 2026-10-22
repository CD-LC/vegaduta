package com.agenticai.core.agents.tools.workspacefiles;

import com.agenticai.core.agents.tools.localshell.LocalShellToolSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Assistant's folder picker: which project folders exist in the person's projects folder, and
 * a way to start a new one. "Attaching" a folder is the console telling the model which folder to
 * work in; the tools themselves confine every path to the projects folder.
 *
 * <p>Only answers on an install where the file tools are offered (one-person install, projects
 * folder present); elsewhere {@code available} is false and creating a folder is a 404.
 */
@RestController
@RequestMapping("/api/assistant/workspace")
public class WorkspaceFolderController {

    /** A plain folder name: no separators, no leading dot, nothing a shell or path would read twice. */
    private static final Pattern FOLDER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,79}");
    private static final int MAX_FOLDERS = 200;

    public record Folder(String name, Instant modified) {}

    public record WorkspaceResponse(
            boolean available, boolean terminal, String root, String hostPath, List<Folder> folders) {}

    public record CreateFolderRequest(String name) {}

    /** One saved upload: {@code path} in the projects folder; {@code textPath} is its extracted text (PDFs), else null. */
    public record SavedFile(String name, String path, long size, String textPath) {}

    public record UploadResponse(String folder, List<SavedFile> files) {}

    /** Where attachments go when no project folder is attached. */
    static final String DEFAULT_UPLOAD_FOLDER = "Chat uploads";
    static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;
    private static final int MAX_UPLOAD_FILES = 10;
    private static final int MAX_EXTRACTED_CHARS = 2_000_000;

    private final WorkspaceFileToolSource source;
    private final LocalShellToolSource terminal;
    private final String hostPath;

    public WorkspaceFolderController(
            WorkspaceFileToolSource source,
            LocalShellToolSource terminal,
            @Value("${app.assistant.local-shell.workspace-host-path:}") String hostPath) {
        this.source = source;
        this.terminal = terminal;
        this.hostPath = hostPath;
    }

    @GetMapping
    public WorkspaceResponse workspace() throws IOException {
        if (!source.offered()) {
            return new WorkspaceResponse(false, false, null, null, List.of());
        }
        Path root = source.files().root();
        List<Folder> folders = new ArrayList<>();
        try (Stream<Path> list = Files.list(root)) {
            for (Path p : (Iterable<Path>) list.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(WorkspaceFolderController::modified).reversed())
                    .limit(MAX_FOLDERS)::iterator) {
                folders.add(new Folder(p.getFileName().toString(), modified(p)));
            }
        }
        return new WorkspaceResponse(
                true, terminal.offered(), root.toString(), hostPath.isBlank() ? null : hostPath, folders);
    }

    /** A dev server the Assistant started; {@code port} is on this computer's loopback (the Preview pane). */
    public record PreviewEntry(int port, String folder, String command, boolean running) {}

    /** Servers the Assistant is running for the Preview pane; empty when there is no runner or none started. */
    @GetMapping("/previews")
    public List<PreviewEntry> previews(
            @RequestParam(value = "limit", required = false) Integer limit) throws IOException {
        java.util.stream.Stream<PreviewEntry> out = terminal.previews().stream()
                .map(p -> new PreviewEntry(p.port(), p.folder(), p.command(), p.running()));
        return (limit == null ? out : out.limit(Math.max(0, limit))).toList();
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/previews/{port}")
    public ResponseEntity<Void> stopPreview(@org.springframework.web.bind.annotation.PathVariable int port) throws IOException {
        return terminal.stopPreview(port) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/folders")
    public ResponseEntity<?> createFolder(@RequestBody CreateFolderRequest request) throws IOException {
        if (!source.offered()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "The projects folder is not set up on this install."));
        }
        String name = request == null || request.name() == null ? "" : request.name().strip();
        if (!FOLDER_NAME.matcher(name).matches()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Use letters, digits, spaces, dots, dashes or underscores (up to 80), starting with a letter or digit."));
        }
        Path dir = source.files().resolve(name);
        boolean existed = Files.isDirectory(dir);
        Files.createDirectories(dir);
        return ResponseEntity.status(existed ? HttpStatus.OK : HttpStatus.CREATED)
                .body(new Folder(name, modified(dir)));
    }

    /**
     * Attach files in the chat: saved into a folder of the projects folder (default "Chat uploads") so the
     * Assistant can list, search and read them with the workspace tools. A PDF also gets its text saved next
     * to it as "&lt;name&gt;.pdf.txt", which is what workspace_search can look inside.
     */
    @PostMapping("/files")
    public ResponseEntity<?> upload(
            @RequestParam(value = "folder", required = false) String folderParam,
            @RequestParam("file") List<MultipartFile> uploads)
            throws IOException {
        if (!source.offered()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "The projects folder is not set up on this install."));
        }
        String folder = folderParam == null || folderParam.isBlank() ? DEFAULT_UPLOAD_FOLDER : folderParam.strip();
        if (!FOLDER_NAME.matcher(folder).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "That folder name is not allowed."));
        }
        if (uploads.isEmpty() || uploads.size() > MAX_UPLOAD_FILES) {
            return ResponseEntity.badRequest().body(Map.of("error", "Send between 1 and " + MAX_UPLOAD_FILES + " files at a time."));
        }
        WorkspaceFiles files = source.files();
        List<SavedFile> saved = new ArrayList<>();
        for (MultipartFile upload : uploads) {
            if (upload.getSize() > MAX_UPLOAD_BYTES) {
                return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                        .body(Map.of("error", "\"" + upload.getOriginalFilename() + "\" is over " + (MAX_UPLOAD_BYTES / 1024 / 1024) + " MB."));
            }
            String name = safeName(upload.getOriginalFilename());
            byte[] bytes = upload.getBytes();
            String path = files.saveUpload(folder, name, bytes);
            saved.add(new SavedFile(name, path, bytes.length, extractPdfText(files, path, bytes)));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new UploadResponse(folder, saved));
    }

    /** The bare file name: no directories, no control characters, never hidden or empty. */
    static String safeName(String original) {
        String n = original == null ? "" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        while (n.startsWith(".")) {
            n = n.substring(1);
        }
        if (n.length() > 120) {
            n = n.substring(n.length() - 120);
        }
        return n.isEmpty() ? "attachment" : n;
    }

    private static String extractPdfText(WorkspaceFiles files, String path, byte[] bytes) {
        if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            return null;
        }
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc).strip();
            if (text.isEmpty()) {
                return null; // a scan: nothing to search without OCR
            }
            String clipped = text.length() > MAX_EXTRACTED_CHARS ? text.substring(0, MAX_EXTRACTED_CHARS) : text;
            return files.saveUpload(parentOf(path), fileNameOf(path) + ".txt", clipped.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null; // encrypted or damaged: the PDF itself is still saved
        }
    }

    private static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    private static String fileNameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static Instant modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }
}
