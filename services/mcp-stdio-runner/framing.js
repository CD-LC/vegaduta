'use strict';

// Length-prefixed frame codec shared by the sidecar (server.js) and the relay
// that runs inside `core` (core/mcp-stdio-proxy/proxy.js). Deliberately
// duplicated in both places rather than imported across a package boundary:
// the two files ship in separate Docker build contexts (`../mcp-stdio-runner`
// vs `../core`), so there is no single node_modules both can reach at build
// time. Keep the two copies identical - see either file's header comment.
//
// Frame shape on the wire: [1-byte type][4-byte big-endian length][payload].
//   TYPE_CONTROL - JSON payload, either direction. First frame the relay
//     sends is always a control frame (the spawn request); after that, control
//     frames carry {type:"spawned"|"error"|"stderr"|"exit"} from the sidecar,
//     or {type:"kill"} from the relay.
//   TYPE_STDOUT  - sidecar -> relay: raw bytes read from the child's stdout,
//     forwarded byte-for-byte (preserves the newline-delimited JSON-RPC
//     framing the MCP SDK's StdioClientTransport expects on the other end).
//   TYPE_STDIN   - relay -> sidecar: raw bytes to write to the child's stdin.

const TYPE_CONTROL = 1;
const TYPE_STDOUT = 2;
const TYPE_STDIN = 3;

const HEADER_BYTES = 5;
const MAX_FRAME_BYTES = 16 * 1024 * 1024;

function encodeFrame(type, payload) {
    const body = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
    const header = Buffer.alloc(HEADER_BYTES);
    header.writeUInt8(type, 0);
    header.writeUInt32BE(body.length, 1);
    return Buffer.concat([header, body]);
}

function encodeControl(obj) {
    return encodeFrame(TYPE_CONTROL, JSON.stringify(obj));
}

/** Incremental frame reader for a byte stream (TCP socket 'data' events). */
class FrameReader {
    constructor(onFrame, onError) {
        this._buf = Buffer.alloc(0);
        this._onFrame = onFrame;
        this._onError = onError;
    }

    push(chunk) {
        this._buf = this._buf.length ? Buffer.concat([this._buf, chunk]) : chunk;
        for (;;) {
            if (this._buf.length < HEADER_BYTES) return;
            const type = this._buf.readUInt8(0);
            const length = this._buf.readUInt32BE(1);
            if (length > MAX_FRAME_BYTES) {
                this._onError(new Error(`frame too large: ${length} bytes`));
                return;
            }
            if (this._buf.length < HEADER_BYTES + length) return;
            const payload = this._buf.subarray(HEADER_BYTES, HEADER_BYTES + length);
            this._buf = this._buf.subarray(HEADER_BYTES + length);
            this._onFrame(type, payload);
        }
    }
}

module.exports = {
    TYPE_CONTROL,
    TYPE_STDOUT,
    TYPE_STDIN,
    HEADER_BYTES,
    MAX_FRAME_BYTES,
    encodeFrame,
    encodeControl,
    FrameReader,
};
