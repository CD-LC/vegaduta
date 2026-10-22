package com.agenticai.core.agents.tools.workspacefiles;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The Assistant's file tools over the person's projects folder ({@link WorkspaceFiles}): list,
 * read, write a whole file, and replace one exact snippet. They let a model build a project without
 * pushing every file through a shell heredoc (each of which would need an approval).
 *
 * <p>Writes do not ask first: they can only touch the projects folder, which the person chose to
 * attach. Commands, which can do anything inside the build container, still go through {@code
 * local_shell}'s approval.
 */
final class WorkspaceFileToolCallback implements ToolCallback {

    enum Kind {
        LIST("workspace_list"),
        READ("workspace_read"),
        SEARCH("workspace_search"),
        WRITE("workspace_write"),
        EDIT("workspace_edit");

        final String toolName;

        Kind(String toolName) {
            this.toolName = toolName;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(WorkspaceFileToolCallback.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final WorkspaceFiles files;
    private final Kind kind;
    private final String rootLabel;

    WorkspaceFileToolCallback(WorkspaceFiles files, Kind kind, String rootLabel) {
        this.files = files;
        this.kind = kind;
        this.rootLabel = rootLabel;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        String where =
                " Paths are relative to the person's projects folder ("
                        + rootLabel
                        + "); start them with the "
                        + "folder the person attached. Nothing outside it can be reached.";
        return switch (kind) {
            case LIST ->
                    def(
                            "Lists the files and folders in a workspace folder (dependency and build output folders such as "
                                    + "node_modules, target and .git are skipped)."
                                    + where,
                            """
                    {"type":"object","properties":{
                      "path":{"type":"string","description":"Folder to list; empty for the projects folder itself"},
                      "recursive":{"type":"boolean","description":"Include sub-folders (up to 6 levels, 500 entries)"}}}
                    """);
            case READ ->
                    def(
                            "Reads a text file from the workspace, with line numbers. Use offset/limit for long files."
                                    + where,
                            """
                    {"type":"object","properties":{
                      "path":{"type":"string","description":"File to read"},
                      "offset":{"type":"integer","description":"First line (1-based); default 1"},
                      "limit":{"type":"integer","description":"Number of lines; default all (output is capped)"}},
                     "required":["path"]}
                    """);
            case SEARCH ->
                    def(
                            "Searches the workspace like a find-in-files: file names that contain the query, then lines of "
                                    + "text files that contain every word of it (case-insensitive). Use it to find a document "
                                    + "or a passage the person attached or mentioned, then workspace_read the file. PDFs the "
                                    + "person attached have their text in a sibling '<name>.pdf.txt' file."
                                    + where,
                            """
                    {"type":"object","properties":{
                      "query":{"type":"string","description":"Words to look for; every word must appear"},
                      "path":{"type":"string","description":"Folder to search in; empty for the whole projects folder"}},
                     "required":["query"]}
                    """);
            case WRITE ->
                    def(
                            "Creates or replaces a whole text file in the workspace (missing folders are created). Prefer "
                                    + "workspace_edit for a small change to an existing file."
                                    + where,
                            """
                    {"type":"object","properties":{
                      "path":{"type":"string","description":"File to write"},
                      "content":{"type":"string","description":"The complete new content"}},
                     "required":["path","content"]}
                    """);
            case EDIT ->
                    def(
                            "Replaces one exact snippet in a workspace file. old_text must appear exactly once (include "
                                    + "enough surrounding lines); read the file first."
                                    + where,
                            """
                    {"type":"object","properties":{
                      "path":{"type":"string","description":"File to change"},
                      "old_text":{"type":"string","description":"Exact text to replace (must occur exactly once)"},
                      "new_text":{"type":"string","description":"Replacement text"}},
                     "required":["path","old_text","new_text"]}
                    """);
        };
    }

    private ToolDefinition def(String description, String schema) {
        return ToolDefinition.builder()
                .name(kind.toolName)
                .description(description)
                .inputSchema(schema)
                .build();
    }

    @Override
    public String call(String toolInput) {
        try {
            JsonNode a =
                    MAPPER.readTree(toolInput == null || toolInput.isBlank() ? "{}" : toolInput);
            String path = a.path("path").asText("");
            ObjectNode out = MAPPER.createObjectNode();
            switch (kind) {
                case LIST -> {
                    ArrayNode entries = out.putArray("entries");
                    for (WorkspaceFiles.Entry e :
                            files.list(path, a.path("recursive").asBoolean(false))) {
                        ObjectNode n =
                                entries.addObject()
                                        .put("path", e.path())
                                        .put("type", e.directory() ? "folder" : "file");
                        if (!e.directory()) {
                            n.put("size", e.size());
                        }
                    }
                    if (entries.size() >= WorkspaceFiles.MAX_LIST_ENTRIES) {
                        out.put(
                                "note",
                                "Stopped at "
                                        + WorkspaceFiles.MAX_LIST_ENTRIES
                                        + " entries; list a sub-folder.");
                    }
                }
                case SEARCH -> {
                    ArrayNode hits = out.putArray("matches");
                    for (WorkspaceFiles.Hit h : files.search(path, a.path("query").asText(""))) {
                        ObjectNode n = hits.addObject().put("path", h.path());
                        if (h.line() > 0) {
                            n.put("line", h.line()).put("text", h.text());
                        } else {
                            n.put("match", "file name");
                        }
                    }
                    if (hits.isEmpty()) {
                        out.put(
                                "note",
                                "No file name or line contains all of those words; try fewer or different words.");
                    } else if (hits.size() >= WorkspaceFiles.MAX_SEARCH_HITS) {
                        out.put(
                                "note",
                                "Showing the first "
                                        + WorkspaceFiles.MAX_SEARCH_HITS
                                        + " matches; narrow with path or more words.");
                    }
                }
                case READ ->
                        readInto(out, path, a.path("offset").asInt(1), a.path("limit").asInt(0));
                case WRITE -> {
                    if (!a.has("content")) {
                        return error("content is required");
                    }
                    String content = a.path("content").asText();
                    boolean existed = files.write(path, content);
                    out.put("path", path)
                            .put(existed ? "replaced" : "created", true)
                            .put("chars", content.length());
                }
                case EDIT -> {
                    String oldText = a.path("old_text").asText("");
                    if (oldText.isEmpty()) {
                        return error("old_text is required");
                    }
                    String current = files.read(path);
                    int first = current.indexOf(oldText);
                    if (first < 0) {
                        return error(
                                "old_text was not found in "
                                        + path
                                        + "; read the file and copy the text exactly.");
                    }
                    if (current.indexOf(oldText, first + 1) >= 0) {
                        return error(
                                "old_text occurs more than once in "
                                        + path
                                        + "; include more surrounding lines.");
                    }
                    String updated =
                            current.substring(0, first)
                                    + a.path("new_text").asText("")
                                    + current.substring(first + oldText.length());
                    files.write(path, updated);
                    out.put("path", path).put("edited", true);
                }
            }
            return MAPPER.writeValueAsString(out);
        } catch (IOException e) {
            return error(
                    e.getMessage() == null ? "The file could not be accessed." : e.getMessage());
        } catch (RuntimeException e) {
            log.warn("{} failed", kind.toolName, e);
            return error("The file tool failed: " + e.getClass().getSimpleName());
        }
    }

    private void readInto(ObjectNode out, String path, int offset, int limit) throws IOException {
        String[] lines = files.read(path).split("\n", -1);
        int from = Math.max(1, offset);
        int to = limit > 0 ? Math.min(lines.length, from - 1 + limit) : lines.length;
        StringBuilder sb = new StringBuilder();
        int i = from;
        for (; i <= to; i++) {
            String line = i + "\t" + lines[i - 1] + "\n";
            if (sb.length() + line.length() > WorkspaceFiles.MAX_READ_CHARS) {
                break;
            }
            sb.append(line);
        }
        out.put("path", path).put("total_lines", lines.length).put("content", sb.toString());
        if (i <= to) {
            out.put("note", "Output cut at line " + (i - 1) + "; read on with offset=" + i + ".");
        }
    }

    private static String error(String message) {
        try {
            return MAPPER.writeValueAsString(MAPPER.createObjectNode().put("error", message));
        } catch (IOException e) {
            return "{\"error\":\"error\"}";
        }
    }
}
