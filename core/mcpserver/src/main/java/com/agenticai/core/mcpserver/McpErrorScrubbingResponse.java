package com.agenticai.core.mcpserver;

import com.agenticai.core.json.JsonMappers;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps a Java exception from reaching an MCP client (B11). The MCP transport answers some client
 * errors (a missing {@code Mcp-Session-Id}, for one) by serializing a Java exception object
 * straight into the 400 body, which put the whole {@code stackTrace} - class names, file names,
 * line numbers - on the wire. Responses with a status below 400 (including the long-lived SSE
 * stream) are passed through untouched and unbuffered; only an error response is held back, and if
 * its body is such a serialized exception it is replaced by a plain JSON-RPC error carrying just
 * the message. Any other error body is forwarded as it was.
 */
final class McpErrorScrubbingResponse extends HttpServletResponseWrapper {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final Pattern QUALIFIED_CLASS =
            Pattern.compile("\\b[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+\\.[A-Z]\\w*");

    private ByteArrayOutputStream buffer;
    private ServletOutputStream bufferedStream;
    private PrintWriter bufferedWriter;

    McpErrorScrubbingResponse(HttpServletResponse response) {
        super(response);
    }

    private boolean isError() {
        return getStatus() >= 400;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (!isError()) {
            return super.getOutputStream();
        }
        if (bufferedStream == null) {
            buffer = new ByteArrayOutputStream();
            bufferedStream =
                    new ServletOutputStream() {
                        @Override
                        public boolean isReady() {
                            return true;
                        }

                        @Override
                        public void setWriteListener(WriteListener writeListener) {
                            // synchronous buffer: nothing to signal
                        }

                        @Override
                        public void write(int b) {
                            buffer.write(b);
                        }

                        @Override
                        public void write(byte[] b, int off, int len) {
                            buffer.write(b, off, len);
                        }
                    };
        }
        return bufferedStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (!isError()) {
            return super.getWriter();
        }
        if (bufferedWriter == null) {
            bufferedWriter =
                    new PrintWriter(
                            new OutputStreamWriter(getOutputStream(), StandardCharsets.UTF_8),
                            true);
        }
        return bufferedWriter;
    }

    @Override
    public void setContentLength(int len) {
        if (!isError()) {
            super.setContentLength(len);
        }
    }

    @Override
    public void setContentLengthLong(long len) {
        if (!isError()) {
            super.setContentLengthLong(len);
        }
    }

    @Override
    public void flushBuffer() throws IOException {
        if (bufferedStream == null) {
            super.flushBuffer();
        }
    }

    /** Writes the held-back error body (scrubbed). Call once after the filter chain returns. */
    void finish() throws IOException {
        if (bufferedStream == null) {
            return;
        }
        if (bufferedWriter != null) {
            bufferedWriter.flush();
        }
        byte[] original = buffer.toByteArray();
        byte[] out = scrub(original, getStatus());
        HttpServletResponse real = (HttpServletResponse) getResponse();
        real.setContentLength(out.length);
        real.getOutputStream().write(out);
        real.getOutputStream().flush();
    }

    /**
     * Package-private for tests: the body to send for an error response with this status and
     * original body.
     */
    static byte[] scrub(byte[] body, int status) {
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (IOException notJson) {
            return body;
        }
        if (node == null || !node.isObject() || !node.has("stackTrace")) {
            return body;
        }
        String message = status >= 500 ? "Internal error" : safeMessage(node.path("message"));
        ObjectNode error = MAPPER.createObjectNode();
        error.put("code", status >= 500 ? -32603 : -32600);
        error.put("message", message);
        ObjectNode reply = MAPPER.createObjectNode();
        reply.put("jsonrpc", "2.0");
        reply.putNull("id");
        reply.set("error", error);
        try {
            return MAPPER.writeValueAsBytes(reply);
        } catch (IOException e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Bad request\"}}"
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String safeMessage(JsonNode message) {
        if (!message.isString()) {
            return "Bad request";
        }
        String text = message.asString().strip();
        if (text.isEmpty()
                || text.length() > 200
                || QUALIFIED_CLASS.matcher(text).find()
                || text.contains("\n")) {
            return "Bad request";
        }
        return text;
    }
}
