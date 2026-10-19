"""Stdlib unittest for runner.py's data-analysis helpers and the full /run path.

Run: python -m unittest core/sdlc/e2b-template/test_runner.py   (no pip deps needed)
"""
import base64
import importlib.util
import os
import tempfile
import threading
import unittest
import json
import sys
import urllib.request
from http.server import HTTPServer

_spec = importlib.util.spec_from_file_location("runner", os.path.join(os.path.dirname(__file__), "runner.py"))
runner = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(runner)


class SafeNameTest(unittest.TestCase):
    def test_strips_directories_and_rejects_dotfiles(self):
        self.assertEqual(runner.safe_name("../../etc/passwd"), "passwd")
        self.assertEqual(runner.safe_name("C:\\x\\data.csv"), "data.csv")
        self.assertIsNone(runner.safe_name(".env"))
        self.assertIsNone(runner.safe_name(""))
        self.assertIsNone(runner.safe_name(None))


class CollectArtifactsTest(unittest.TestCase):
    def test_returns_only_new_or_changed_allow_listed_files(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "data.csv"), "w") as f:
                f.write("a,b\n1,2\n")
            before = runner.snapshot(d)
            with open(os.path.join(d, "chart.png"), "wb") as f:
                f.write(b"\x89PNG")
            with open(os.path.join(d, "notes.exe"), "wb") as f:
                f.write(b"MZ")
            with open(os.path.join(d, "empty.csv"), "wb"):
                pass
            names = [a["name"] for a in runner.collect_artifacts(d, before)]
            self.assertEqual(names, ["chart.png"])  # unchanged input, .exe and empty file are skipped

    def test_caps_oversized_files_and_artifact_count(self):
        with tempfile.TemporaryDirectory() as d:
            before = runner.snapshot(d)
            with open(os.path.join(d, "big.png"), "wb") as f:
                f.write(b"x" * (runner.MAX_ARTIFACT_BYTES + 1))
            for i in range(runner.MAX_ARTIFACTS + 3):
                with open(os.path.join(d, "f%02d.txt" % i), "wb") as f:
                    f.write(b"hi")
            arts = runner.collect_artifacts(d, before)
            self.assertEqual(len(arts), runner.MAX_ARTIFACTS)
            self.assertNotIn("big.png", [a["name"] for a in arts])


class RunEndpointTest(unittest.TestCase):
    def test_mounts_files_and_returns_what_the_script_saves(self):
        runner.INTERPRETERS["python"] = [sys.executable]  # python3 may be absent (e.g. Windows)
        server = HTTPServer(("127.0.0.1", 0), runner.Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            code = "d = open('data.csv').read().upper()\nopen('out.csv','w').write(d)\nprint('done')"
            body = json.dumps({
                "code": code, "language": "python", "timeoutSeconds": 20,
                "files": [{"name": "../data.csv", "contentBase64": base64.b64encode(b"a,b\n1,2\n").decode()}],
            }).encode()
            req = urllib.request.Request(
                "http://127.0.0.1:%d/run" % server.server_port, data=body, headers={"Content-Type": "application/json"})
            res = json.loads(urllib.request.urlopen(req, timeout=30).read())
        finally:
            server.shutdown()
        self.assertEqual(res["exitCode"], 0, res)
        self.assertIn("done", res["stdout"])
        self.assertEqual([a["name"] for a in res["artifacts"]], ["out.csv"])
        content = base64.b64decode(res["artifacts"][0]["contentBase64"]).replace(b"\r\n", b"\n")  # Windows text mode
        self.assertEqual(content, b"A,B\n1,2\n")


if __name__ == "__main__":
    unittest.main()
