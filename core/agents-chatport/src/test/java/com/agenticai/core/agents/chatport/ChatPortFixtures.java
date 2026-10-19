package com.agenticai.core.agents.chatport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Small hand-written samples shaped like the two real exports. Text blocks: a JSON string newline is written
 * {@code \\n} so the block carries a backslash-n, not a raw line break.
 */
final class ChatPortFixtures {

    private ChatPortFixtures() {
    }

    /**
     * One ChatGPT conversation. Two branches hang off n2 ("n3b" is the abandoned one); the live path is
     * root > n1(hidden system) > n2 > n3 > n3a(python call) > n3t(tool result) > n4 = current_node.
     */
    static final String CHATGPT_ONE = """
        [{
          "title": "Trip planning",
          "create_time": 1700000000.5,
          "update_time": 1700000100.0,
          "conversation_id": "conv-gpt-1",
          "current_node": "n4",
          "mapping": {
            "root": {"id": "root", "message": null, "parent": null, "children": ["n1"]},
            "n1": {"id": "n1", "parent": "root", "children": ["n2"],
              "message": {"id": "n1", "author": {"role": "system"}, "create_time": null,
                "content": {"content_type": "text", "parts": [""]},
                "metadata": {"is_visually_hidden_from_conversation": true}}},
            "n2": {"id": "n2", "parent": "n1", "children": ["n3", "n3b"],
              "message": {"id": "n2", "author": {"role": "user"}, "create_time": 1700000001.0,
                "content": {"content_type": "text", "parts": ["Plan a 3-day trip to Kyoto."]},
                "metadata": {}}},
            "n3b": {"id": "n3b", "parent": "n2", "children": [],
              "message": {"id": "n3b", "author": {"role": "assistant"}, "create_time": 1700000002.5,
                "content": {"content_type": "text", "parts": ["Branch not taken."]}, "recipient": "all"}},
            "n3": {"id": "n3", "parent": "n2", "children": ["n3a"],
              "message": {"id": "n3", "author": {"role": "assistant"}, "create_time": 1700000002.0,
                "content": {"content_type": "text", "parts": ["Day 1: Fushimi Inari.", "Day 2: Arashiyama."]},
                "recipient": "all"}},
            "n3a": {"id": "n3a", "parent": "n3", "children": ["n3t"],
              "message": {"id": "n3a", "author": {"role": "assistant"}, "create_time": 1700000003.0,
                "content": {"content_type": "code", "language": "unknown", "text": "print(1)"},
                "recipient": "python"}},
            "n3t": {"id": "n3t", "parent": "n3a", "children": ["n4"],
              "message": {"id": "n3t", "author": {"role": "tool", "name": "python"}, "create_time": 1700000004.0,
                "content": {"content_type": "execution_output", "text": "1"}}},
            "n4": {"id": "n4", "parent": "n3t", "children": [],
              "message": {"id": "n4", "author": {"role": "user"}, "create_time": 1700000005.0,
                "content": {"content_type": "multimodal_text", "parts": [
                  {"content_type": "image_asset_pointer", "asset_pointer": "file-service://file-abc", "width": 10},
                  "Thanks! Add food spots."]},
                "metadata": {"attachments": [{"id": "file-abc", "name": "map.png", "mime_type": "image/png"}]}}}
          }
        }]
        """;

    /** A second, shorter ChatGPT conversation (id conv-gpt-2) to test selection. */
    static final String CHATGPT_TWO_CONVERSATIONS = """
        [
          {"title": "First", "create_time": 1700000000, "conversation_id": "conv-a", "current_node": "a2",
           "mapping": {
             "a1": {"id": "a1", "parent": null, "children": ["a2"],
               "message": {"author": {"role": "user"}, "content": {"content_type": "text", "parts": ["Hello A"]}}},
             "a2": {"id": "a2", "parent": "a1", "children": [],
               "message": {"author": {"role": "assistant"}, "content": {"content_type": "text", "parts": ["Hi A"]}, "recipient": "all"}}}},
          {"title": "Second", "create_time": 1700001000, "conversation_id": "conv-b", "current_node": "b2",
           "mapping": {
             "b1": {"id": "b1", "parent": null, "children": ["b2"],
               "message": {"author": {"role": "user"}, "content": {"content_type": "text", "parts": ["Hello B"]}}},
             "b2": {"id": "b2", "parent": "b1", "children": [],
               "message": {"author": {"role": "assistant"}, "content": {"content_type": "text", "parts": ["Hi B"]}, "recipient": "all"}}}}
        ]
        """;

    /** Parent chain that loops: a's parent is b, b's parent is a. */
    static final String CHATGPT_CYCLE = """
        [{"title": "Loop", "conversation_id": "conv-cycle", "current_node": "a",
          "mapping": {
            "a": {"id": "a", "parent": "b", "children": [],
              "message": {"author": {"role": "user"}, "content": {"content_type": "text", "parts": ["hello"]}}},
            "b": {"id": "b", "parent": "a", "children": ["a"],
              "message": {"author": {"role": "assistant"}, "content": {"content_type": "text", "parts": ["hi"]}, "recipient": "all"}}}}]
        """;

    /** No current_node: the leaf with the newest message is the live end. */
    static final String CHATGPT_NO_CURRENT_NODE = """
        [{"title": "No pointer", "conversation_id": "conv-nc",
          "mapping": {
            "u1": {"id": "u1", "parent": null, "children": ["a1"],
              "message": {"author": {"role": "user"}, "create_time": 10, "content": {"content_type": "text", "parts": ["question"]}}},
            "a1": {"id": "a1", "parent": "u1", "children": [],
              "message": {"author": {"role": "assistant"}, "create_time": 20, "content": {"content_type": "text", "parts": ["answer"]}, "recipient": "all"}}}}]
        """;

    /** One Claude conversation: a thinking block and a tool_use block must not leak, attachment content never read. */
    static final String CLAUDE_ONE = """
        [{
          "uuid": "c-claude-1",
          "name": "Resume help",
          "created_at": "2024-03-01T10:00:00.000000Z",
          "updated_at": "2024-03-01T10:05:00.000000Z",
          "account": {"uuid": "acc-1"},
          "chat_messages": [
            {"uuid": "m1", "sender": "human", "text": "Can you review my resume?",
             "content": [{"type": "text", "text": "Can you review my resume?"}],
             "created_at": "2024-03-01T10:00:01.000000Z",
             "attachments": [{"file_name": "resume.pdf", "file_size": 1234, "file_type": "pdf", "extracted_content": "EXTRACTED-BODY-MUST-NOT-APPEAR"}],
             "files": [{"file_name": "photo.png"}]},
            {"uuid": "m2", "sender": "assistant", "text": "Sure - happy to.",
             "content": [{"type": "thinking", "thinking": "hidden reasoning"},
                         {"type": "text", "text": "Sure - happy to."},
                         {"type": "tool_use", "name": "search", "input": {"q": "x"}}],
             "created_at": "2024-03-01T10:00:05.000000Z", "attachments": [], "files": []},
            {"uuid": "m3", "sender": "system", "text": "ignore me"}
          ]
        }]
        """;

    /** Builds a zip in memory: name -> bytes, in insertion order. */
    static byte[] zip(Map<String, byte[]> entries) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                    zos.putNextEntry(new ZipEntry(e.getKey()));
                    zos.write(e.getValue());
                    zos.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] zipOf(String name, String content) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put(name, content.getBytes(StandardCharsets.UTF_8));
        return zip(m);
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
