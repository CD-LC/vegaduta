// Minimal MCP server over newline-delimited JSON-RPC 2.0 on stdio.
//
// Hand-rolled on purpose (same approach as mcp-servers/home-assistant/index.js):
// this host bundles no third-party code, and the tool-only subset of the
// protocol is small - initialize, notifications/initialized, ping, tools/list,
// tools/call. Spec: https://modelcontextprotocol.io/specification/2025-06-18/basic/transports
// (stdio: messages are UTF-8 JSON delimited by newlines, no embedded newlines;
// stdout carries ONLY protocol messages, logs go to stderr).

export const SUPPORTED_PROTOCOL_VERSIONS = Object.freeze(['2025-06-18', '2025-03-26', '2024-11-05']);

const PARSE_ERROR = -32700;
const INVALID_REQUEST = -32600;
const METHOD_NOT_FOUND = -32601;
const INVALID_PARAMS = -32602;
const INTERNAL_ERROR = -32603;

function rpcError(id, code, message) {
  return { jsonrpc: '2.0', id: id === undefined ? null : id, error: { code, message } };
}

function isPlainObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

/**
 * @param serverInfo { name, version }
 * @param listTools  () => [{ name, description, inputSchema }]
 * @param callTool   async (name, args) => MCP tool result
 */
export function createMcpHandler({ serverInfo, listTools, callTool, log = () => {} }) {
  async function handleRequest(msg) {
    const { id, method } = msg;
    const params = isPlainObject(msg.params) ? msg.params : {};
    switch (method) {
      case 'initialize': {
        const requested = typeof params.protocolVersion === 'string' ? params.protocolVersion : '';
        const protocolVersion = SUPPORTED_PROTOCOL_VERSIONS.includes(requested) ? requested : SUPPORTED_PROTOCOL_VERSIONS[0];
        return {
          jsonrpc: '2.0',
          id,
          result: { protocolVersion, capabilities: { tools: { listChanged: false } }, serverInfo },
        };
      }
      case 'ping':
        return { jsonrpc: '2.0', id, result: {} };
      case 'tools/list':
        return { jsonrpc: '2.0', id, result: { tools: listTools() } };
      case 'tools/call': {
        if (typeof params.name !== 'string' || params.name === '') {
          return rpcError(id, INVALID_PARAMS, 'tools/call requires a tool name');
        }
        if (params.arguments !== undefined && !isPlainObject(params.arguments)) {
          return rpcError(id, INVALID_PARAMS, 'tools/call arguments must be an object');
        }
        try {
          const result = await callTool(params.name, params.arguments);
          return { jsonrpc: '2.0', id, result };
        } catch (err) {
          log('error', 'tool_dispatch_failed', { tool: params.name, error: String((err && err.message) || err).slice(0, 200) });
          return rpcError(id, INTERNAL_ERROR, 'internal error while running the tool');
        }
      }
      default:
        return rpcError(id, METHOD_NOT_FOUND, `method not found: ${String(method).slice(0, 100)}`);
    }
  }

  /** Handles one decoded JSON-RPC message; resolves to a response object, or null when none is due. */
  async function handleMessage(msg) {
    if (!isPlainObject(msg) || msg.jsonrpc !== '2.0') {
      return rpcError(isPlainObject(msg) ? msg.id : null, INVALID_REQUEST, 'invalid JSON-RPC request');
    }
    const isRequest = typeof msg.method === 'string';
    if (!isRequest) return null; // a response from the client: nothing to do
    if (!('id' in msg)) return null; // notification (initialized, cancelled, ...): never answered
    return handleRequest(msg);
  }

  return { handleMessage };
}

/**
 * Reads newline-delimited JSON from `input`, dispatches to `handler`, writes
 * newline-delimited JSON to `output`. Returns a promise that resolves once
 * input has ended AND every in-flight request has been answered.
 */
export function serveStdio({ input, output, handler, log = () => {}, maxLineBytes = 8 * 1024 * 1024 }) {
  return new Promise((resolve) => {
    const pending = new Set();
    let buffer = '';
    let ended = false;

    const write = (obj) => {
      output.write(`${JSON.stringify(obj)}\n`);
    };

    const finishIfDone = () => {
      if (ended && pending.size === 0) resolve();
    };

    const dispatch = (parsed) => {
      const p = (async () => {
        if (Array.isArray(parsed)) {
          if (parsed.length === 0) {
            write(rpcError(null, INVALID_REQUEST, 'empty batch'));
            return;
          }
          const responses = (await Promise.all(parsed.map((m) => handler.handleMessage(m)))).filter((r) => r !== null);
          if (responses.length > 0) write(responses);
          return;
        }
        const response = await handler.handleMessage(parsed);
        if (response !== null) write(response);
      })()
        .catch((err) => {
          log('error', 'dispatch_failed', { error: String((err && err.message) || err).slice(0, 200) });
        })
        .finally(() => {
          pending.delete(p);
          finishIfDone();
        });
      pending.add(p);
    };

    const processLine = (line) => {
      const trimmed = line.trim();
      if (!trimmed) return;
      let parsed;
      try {
        parsed = JSON.parse(trimmed);
      } catch {
        write(rpcError(null, PARSE_ERROR, 'parse error'));
        return;
      }
      dispatch(parsed);
    };

    input.setEncoding('utf8');
    input.on('data', (chunk) => {
      buffer += chunk;
      let idx = buffer.indexOf('\n');
      while (idx >= 0) {
        const line = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 1);
        processLine(line);
        idx = buffer.indexOf('\n');
      }
      if (buffer.length > maxLineBytes) {
        buffer = '';
        write(rpcError(null, INVALID_REQUEST, 'message too large'));
      }
    });
    input.on('end', () => {
      if (buffer.trim()) processLine(buffer);
      buffer = '';
      ended = true;
      finishIfDone();
    });
    input.on('error', (err) => {
      log('error', 'stdin_error', { error: String((err && err.message) || err).slice(0, 200) });
      ended = true;
      finishIfDone();
    });
  });
}
