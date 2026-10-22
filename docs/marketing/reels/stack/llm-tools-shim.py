"""Local-model adapter for this demo: an OpenAI-compatible pass-through in front of llama-server
that drops the `tools` / `tool_choice` fields. Llama 3.2 3B's tool-call output does not satisfy
llama.cpp's tool-call parser (HTTP 500), and the platform's probe treats tool support as advisory,
so the agent gets plain text answers instead - knowledge context is injected by core either way."""
import json, sys, http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
UP_HOST, UP_PORT = '127.0.0.1', int(sys.argv[2]) if len(sys.argv) > 2 else 8091
class H(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def _fwd(self):
        n = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(n) if n else b''
        if self.command == 'POST' and self.path.rstrip('/').endswith('/chat/completions') and body:
            try:
                d = json.loads(body)
                if d.pop('tools', None) is not None or d.pop('tool_choice', None) is not None:
                    d.pop('parallel_tool_calls', None)
                    body = json.dumps(d).encode()
            except Exception:
                pass
        c = http.client.HTTPConnection(UP_HOST, UP_PORT, timeout=1800)
        hdrs = {k: v for k, v in self.headers.items() if k.lower() not in ('host', 'content-length', 'transfer-encoding', 'connection')}
        hdrs['Content-Length'] = str(len(body))
        c.request(self.command, self.path, body=body if body else None, headers=hdrs)
        r = c.getresponse()
        self.send_response(r.status)
        for k, v in r.getheaders():
            if k.lower() in ('transfer-encoding', 'content-length', 'connection'): continue
            self.send_header(k, v)
        self.send_header('Connection', 'close'); self.end_headers()
        while True:
            chunk = r.read1(8192) if hasattr(r, 'read1') else r.read(8192)
            if not chunk: break
            try: self.wfile.write(chunk); self.wfile.flush()
            except BrokenPipeError: break
        self.close_connection = True
    do_GET = do_POST = _fwd
    def log_message(self, *a): pass
ThreadingHTTPServer(('127.0.0.1', int(sys.argv[1]) if len(sys.argv) > 1 else 8089), H).serve_forever()
