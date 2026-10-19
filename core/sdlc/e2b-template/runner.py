#!/usr/bin/env python3
"""
Master Roadmap 2026 item #14 (E2B code execution completion, §B).

Stdlib-only HTTP server (no pip dependencies - keeps the template image
small and avoids adding an undocumented supply-chain surface to something
that already runs arbitrary tenant code). Exposes exactly one contract,
defined and owned by this repo (see E2bCodeExecutionClient.java's javadoc
for why this exists instead of talking to E2B's own envd Process API):

    POST /run
      body: {"code": "...", "language": "python"|"node"|"bash", "timeoutSeconds": 30,
             "files": [{"name": "data.csv", "contentBase64": "..."}]}      # files optional
      200:  {"stdout": "...", "stderr": "...", "exitCode": 0, "timedOut": false,
             "artifacts": [{"name": "chart.png", "mimeType": "image/png", "contentBase64": "..."}]}

    GET /health
      200: {"status": "ok"}

Data-analysis sandbox (gap-analysis item 9): "files" are written into the
run's working directory before the code starts (so a script can simply
pd.read_csv("data.csv")), and any NEW or CHANGED file left in that directory
with an allow-listed extension (png/svg/xlsx/csv/py/...) is returned as an
artifact. Both directions are size-capped.

The submitted code is written to a temp file and run via subprocess with a
hard wall-clock timeout - it does NOT re-sandbox the code any further than
the E2B sandbox itself already does (that isolation is the whole point of
running this inside an E2B sandbox in the first place, not something this
script needs to reimplement).
"""
import base64
import json
import os
import shutil
import subprocess
import tempfile
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8080

INTERPRETERS = {
    "python": ["python3"],
    "node": ["node"],
    "bash": ["bash"],
}

EXTENSIONS = {
    "python": ".py",
    "node": ".js",
    "bash": ".sh",
}

ARTIFACT_MIME = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".svg": "image/svg+xml",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".csv": "text/csv",
    ".json": "application/json",
    ".md": "text/markdown",
    ".txt": "text/plain",
    ".py": "text/x-python",
    ".pdf": "application/pdf",
}
MAX_ARTIFACTS = 8
MAX_ARTIFACT_BYTES = 5 * 1024 * 1024
MAX_ARTIFACT_TOTAL_BYTES = 12 * 1024 * 1024


def safe_name(name):
    """A plain filename only - no directories, no traversal, no dotfiles."""
    if not isinstance(name, str):
        return None
    base = os.path.basename(name.replace("\\", "/"))
    if not base or base.startswith("."):
        return None
    return base


def snapshot(workdir):
    state = {}
    for entry in os.listdir(workdir):
        path = os.path.join(workdir, entry)
        if os.path.isfile(path):
            st = os.stat(path)
            state[entry] = (st.st_size, st.st_mtime_ns)
    return state


def collect_artifacts(workdir, before):
    artifacts = []
    total = 0
    for entry in sorted(os.listdir(workdir)):
        path = os.path.join(workdir, entry)
        if not os.path.isfile(path):
            continue
        mime = ARTIFACT_MIME.get(os.path.splitext(entry)[1].lower())
        if mime is None:
            continue
        st = os.stat(path)
        if before.get(entry) == (st.st_size, st.st_mtime_ns):
            continue  # an unchanged mounted input, not an output
        if st.st_size == 0 or st.st_size > MAX_ARTIFACT_BYTES:
            continue
        if len(artifacts) >= MAX_ARTIFACTS or total + st.st_size > MAX_ARTIFACT_TOTAL_BYTES:
            break
        with open(path, "rb") as f:
            data = f.read()
        total += len(data)
        artifacts.append({
            "name": entry,
            "mimeType": mime,
            "contentBase64": base64.b64encode(data).decode("ascii"),
        })
    return artifacts


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, {"status": "ok"})
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/run":
            self._send_json(404, {"error": "not found"})
            return

        try:
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length)
            request = json.loads(raw)
        except Exception as e:
            self._send_json(400, {"error": f"invalid request body: {e}"})
            return

        code = request.get("code")
        language = request.get("language")
        timeout_seconds = request.get("timeoutSeconds") or 30

        if not code or not isinstance(code, str):
            self._send_json(400, {"error": "code is required"})
            return
        if language not in INTERPRETERS:
            self._send_json(400, {"error": f"unsupported language '{language}' - must be one of {list(INTERPRETERS)}"})
            return

        interpreter = INTERPRETERS[language]
        suffix = EXTENSIONS[language]

        workdir = tempfile.mkdtemp(prefix="run-")
        artifacts = []
        try:
            for item in request.get("files") or []:
                name = safe_name(item.get("name")) if isinstance(item, dict) else None
                if name is None:
                    continue
                try:
                    data = base64.b64decode(item.get("contentBase64") or "", validate=True)
                except Exception:
                    continue
                with open(os.path.join(workdir, name), "wb") as out:
                    out.write(data)

            # The script lives OUTSIDE the working dir so it is never mistaken for an output.
            with tempfile.NamedTemporaryFile(mode="w", suffix=suffix, delete=False) as f:
                f.write(code)
                script_path = f.name

            before = snapshot(workdir)
            timed_out = False
            try:
                env = dict(os.environ)
                env.setdefault("MPLBACKEND", "Agg")  # headless matplotlib: savefig works
                result = subprocess.run(
                    interpreter + [script_path],
                    capture_output=True,
                    text=True,
                    timeout=timeout_seconds,
                    cwd=workdir,
                    env=env,
                )
                stdout, stderr, exit_code = result.stdout, result.stderr, result.returncode
            except subprocess.TimeoutExpired as e:
                timed_out = True
                stdout = e.stdout or ""
                stderr = (e.stderr or "") + "\n[runner] process killed after exceeding timeoutSeconds"
                exit_code = None
            except Exception as e:
                stdout = ""
                stderr = f"[runner] failed to execute: {e}"
                exit_code = None
            finally:
                try:
                    os.unlink(script_path)
                except OSError:
                    pass

            artifacts = collect_artifacts(workdir, before)
        finally:
            shutil.rmtree(workdir, ignore_errors=True)

        self._send_json(200, {
            "stdout": stdout,
            "stderr": stderr,
            "exitCode": exit_code,
            "timedOut": timed_out,
            "artifacts": artifacts,
        })

    def log_message(self, format, *args):
        # Default BaseHTTPRequestHandler logging goes to stderr on every
        # request - fine for E2B's own sandbox logs, left as-is deliberately
        # (no need for a heavier logging setup in a single-purpose runner).
        pass


if __name__ == "__main__":
    # Plain HTTP on all interfaces is deliberate (python:S5332). This server
    # only ever runs inside a disposable, single-tenant E2B microVM: E2B's
    # proxy terminates TLS for https://8080-{sandboxId}.e2b.app (what
    # E2bCodeExecutionClient calls) and forwards to this port over the
    # sandbox's internal interface as plain HTTP - the only mode for a
    # sandbox created without httpsPorts, which the Java client never sets.
    # Binding loopback would make the runner unreachable from that proxy
    # (ready_cmd in e2b.toml probes localhost, which 0.0.0.0 also serves),
    # and TLS here would be TLS-inside-TLS with no certificate the proxy
    # verifies. Nothing else runs in the VM, so there is no local peer to
    # protect against either.
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"[runner] listening on :{PORT}")
    server.serve_forever()
