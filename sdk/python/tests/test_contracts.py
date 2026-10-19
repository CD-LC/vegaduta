"""Consumer-contract tests for the generated Python SDK (consumer "sdk-python").

Run: python -m unittest discover -s tests   (from sdk/python; pytest also collects it)

contracts/consumers/sdk-python/*.contract.json lists the core endpoints the SDK's documented usage
(the Python blocks of sdk/README.md, which docs/sdk/clients.md embeds) relies on. These tests fail
when a contract is malformed or names an endpoint/field docs/api/openapi.json does not declare, and
when running the README examples against the real generated client sends a request that is not in
the contract, or omits a field the contract says it sends. The provider side (core really answers
with those fields) is verified in core/app.
"""

import copy
import contextlib
import io
import os
import re
import sys
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve()
sys.path.insert(0, str(HERE.parents[1]))
sys.path.insert(0, str(HERE.parents[3] / "contracts" / "consumers" / "tools"))

import consumer_contract as cc  # noqa: E402
from vegaduta_sdk import runtime  # noqa: E402

CONSUMER = "sdk-python"
SDK_DIR = HERE.parents[1]
README = (HERE.parents[2] / "README.md").read_text(encoding="utf-8")
FAKE_ID = "00000000-0000-0000-0000-000000000001"


class ConsumerContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.contracts = cc.load_contracts(CONSUMER)
        cls.interactions = cc.interactions_of(CONSUMER)

    def test_contracts_follow_the_shared_format(self):
        self.assertTrue(self.contracts, "no contracts/consumers/sdk-python/*.contract.json")
        for path, doc in self.contracts:
            self.assertEqual(cc.shape_errors(doc, CONSUMER), [], str(path))
        ids = [it["id"] for it in self.interactions]
        self.assertEqual(len(ids), len(set(ids)), f"interaction ids repeat across files: {ids}")

    def test_every_interaction_is_declared_in_the_openapi_document(self):
        spec = cc.load_spec()
        for it in self.interactions:
            problems, _untyped = cc.check_against_spec(spec, it)
            # untyped (`{}`) schemas cannot vouch for a field; the provider verifier still checks it
            self.assertEqual(problems, [], f"{it['id']}: {it['method']} {it['path']}")

    def test_known_spec_status_drift_is_still_real(self):
        self.assertEqual(cc.status_drift_problems(cc.load_spec()), [])

    def test_undocumented_status_fails_unless_it_is_a_recorded_drift(self):
        spec = cc.load_spec()
        create = next(it for it in self.interactions if it["id"] == "agent-create")
        self.assertEqual(create["status"], 201, "core answers POST /api/agents with 201")
        # the spec documents 201 now (@ResponseStatus on AgentController.create), so no drift entry is needed
        self.assertEqual(cc.check_against_spec(spec, create, drift=[])[0], [])
        # simulate the old drift: the spec says 200 where core sends 201
        stale = copy.deepcopy(spec)
        responses = stale["paths"]["/api/agents"]["post"]["responses"]
        responses["200"] = responses.pop("201")
        self.assertEqual(
            cc.check_against_spec(stale, create, drift=[])[0][0], "status 201 is not a documented response"
        )
        entry = {"method": "POST", "path": "/api/agents", "coreStatus": 201, "specStatus": 200}
        self.assertEqual(cc.check_against_spec(stale, create, drift=[entry])[0], [])
        wrong = {**create, "status": 202}
        self.assertEqual(cc.check_against_spec(spec, wrong)[0][0], "status 202 is not a documented response")

    def test_readme_examples_only_hit_contracted_endpoints(self):
        try:
            from vegaduta_sdk import VegadutaClient
        except ImportError as exc:  # pragma: no cover - only before the first generation
            self.skipTest(str(exc))
        calls = []

        def fake_request(_self, method, path, *, body=None, **_kw):
            calls.append((method, path, body))
            return {"id": FAKE_ID, "reply": "ok"}

        def fake_stream(_self, method, path, *, body=None, **_kw):
            calls.append((method, path, body))
            yield "ok"

        blocks = cc.code_blocks(README, ["python", "py"])
        self.assertTrue(blocks, "found no Python blocks in sdk/README.md")
        env = {
            "VEGADUTA_BASE_URL": "https://core.contract.test",
            "VEGADUTA_TOKEN": "contract-token",
            "VEGADUTA_GRANT_KEY": "contract-grant",
        }
        with (
            mock.patch.dict(os.environ, env),
            mock.patch.object(runtime.HttpClient, "request", fake_request),
            mock.patch.object(runtime.HttpClient, "stream", fake_stream),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            for i, block in enumerate(blocks):
                before = len(calls)
                ns = {
                    "__name__": f"readme_block_{i}",
                    "os": os,
                    "VegadutaClient": VegadutaClient,
                    "agent_id": FAKE_ID,
                }
                if not re.search(r"^client\s*=", block, re.M):
                    ns["client"] = VegadutaClient()
                exec(compile(block, f"sdk/README.md python block {i}", "exec"), ns)  # noqa: S102 - our own docs
                self.assertGreater(len(calls), before, f"README Python block {i} made no request:\n{block}")

        for method, path, body in calls:
            it = cc.find_interaction(self.interactions, method, path)
            self.assertIsNotNone(
                it, f"the SDK called {method} {path}, which is not in contracts/consumers/{CONSUMER}"
            )
            for f in it["request"]["requiredFields"]:
                self.assertTrue(
                    cc.has_field(body, f), f"{it['id']}: the SDK did not send {f} (body {body!r})"
                )

    def test_hand_written_runtime_hard_codes_no_paths_outside_the_contract(self):
        src = (SDK_DIR / "vegaduta_sdk" / "runtime.py").read_text(encoding="utf-8")
        for m in re.finditer(r"[\"'](/(?:api|v1|\.well-known)/[^\"'\s]*)[\"']", src):
            self.assertTrue(
                any(it["path"] == m.group(1) for it in self.interactions),
                f"runtime.py references {m.group(1)}, which is not in contracts/consumers/{CONSUMER}",
            )


if __name__ == "__main__":
    unittest.main()
