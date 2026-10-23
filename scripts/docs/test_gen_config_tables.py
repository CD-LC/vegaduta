#!/usr/bin/env python3
"""Fixture tests for gen-config-tables.py (standard library only): python3 scripts/docs/test_gen_config_tables.py"""
import importlib.util
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("gen_config_tables", HERE / "gen-config-tables.py")
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)


class FakeCode:
    def __init__(self, yml=None, java=None):
        self.yml = yml or {}
        self.java = java or {}
        self.prefixes = set()

    candidates = g.Code.candidates
    preferred = g.Code.preferred


class Placeholders(unittest.TestCase):
    def test_env_and_default(self):
        self.assertEqual(g.split_placeholder("${APP_X:30s}"), ("APP_X", "30s"))

    def test_nested_placeholder_keeps_the_inner_default(self):
        self.assertEqual(g.split_placeholder("${A:${B:}}"), ("A", ""))
        self.assertEqual(g.split_placeholder("${A:${B:7}}"), ("A", "7"))

    def test_plain_value(self):
        self.assertEqual(g.split_placeholder("true"), (None, "true"))


class Literals(unittest.TestCase):
    def test_string_number_boolean_and_concatenation(self):
        self.assertEqual(g.literal('"a,b"'), "a,b")
        self.assertEqual(g.literal("1_000L"), "1000")
        self.assertEqual(g.literal("0.95"), "0.95")
        self.assertEqual(g.literal("true"), "true")
        self.assertEqual(g.literal('"a," + "b"'), "a,b")

    def test_expressions_are_not_literals(self):
        self.assertIsNone(g.literal("Duration.ofSeconds(30)"))
        self.assertIsNone(g.literal("FOO"))


class ValueAnnotation(unittest.TestCase):
    def test_an_ellipsis_in_a_comment_is_not_a_key(self):
        self.assertIsNone(g.VALUE_RE.search('{@code @Value("${...}")}'))
        self.assertEqual(g.VALUE_RE.search('@Value("${app.a.b:7}")').group(1), "app.a.b")


class Tokens(unittest.TestCase):
    def test_a_family_row_names_its_ancestor(self):
        toks = g.tokens_of("| `app.models.defaults.<provider>` | x |\n| `app.llm.endpoints.<p>.base-url` | y |")
        self.assertIn(g.norm("app.models.defaults"), toks)
        self.assertIn(g.norm("app.llm.endpoints"), toks)

    def test_an_ancestor_counts_for_a_key_beneath_it(self):
        toks = g.tokens_of("`app.models.defaults.<provider>`")
        key = g.norm("app.models.defaults.anthropic")
        parts = key.split(".")
        self.assertTrue(any(".".join(parts[:i]) in toks for i in range(2, len(parts))))


class Tables(unittest.TestCase):
    LINES = [
        "## Appendix: configuration property reference by module\n",
        "| Property | Default | Applies to | Meaning |\n",
        "|---|---|---|---|\n",
        '| `app.a.ttl` | `" + DEFAULT_TTL + "` | all shapes | the ttl |\n',
        "| `app.a.size` | `9` | all shapes | the size, with a `|` in code |\n",
        "| `app.a.name` |  | all shapes | no default |\n",
        "| `app.a.api-key` | `(set in source)` | all shapes | credential |\n",
        "| `app.a.nowhere` | `(set in source)` | all shapes | code unknown |\n",
    ]

    def code(self):
        return FakeCode(
            yml={"app.a.name": ["${APP_A_NAME:x}"], "app.a.api-key": ["${K:secret-value}"]},
            java={"app.a.ttl": ["6"], "app.a.size": ["10"]},
        )

    def test_broken_drifted_and_empty_are_found(self):
        found = g.analyse(self.LINES, self.code())
        self.assertEqual([(f[1], f[3]) for f in found["broken"]], [("app.a.ttl", "6")])
        self.assertEqual([(f[1], f[3]) for f in found["drift"]], [("app.a.size", "10")])
        self.assertEqual([f[1] for f in found["empty"]], ["app.a.name"])

    def test_a_credential_default_is_never_reproduced_and_an_unknown_key_is_left_alone(self):
        found = g.analyse(self.LINES, self.code())
        keys = [f[1] for label in ("broken", "drift", "empty") for f in found[label]]
        self.assertNotIn("app.a.api-key", keys)
        self.assertNotIn("app.a.nowhere", keys)

    def test_a_truncated_default_is_not_drift(self):
        lines = self.LINES[:3] + ["| `app.a.size` | `10...` | all shapes | long |\n"]
        self.assertEqual(g.analyse(lines, self.code())["drift"], [])

    def test_edits_touch_only_the_default_cell(self):
        found = g.analyse(self.LINES, self.code())
        out = g.apply_edits(self.LINES, found["edits"])
        self.assertEqual(out[3], "| `app.a.ttl` | `6` | all shapes | the ttl |\n")
        self.assertEqual(out[4], "| `app.a.size` | `10` | all shapes | the size, with a `|` in code |\n")
        self.assertEqual([o for i, o in enumerate(out) if i not in (3, 4)], [l for i, l in enumerate(self.LINES) if i not in (3, 4)])

    def test_cells_round_trip_unchanged(self):
        for line in self.LINES[3:]:
            self.assertEqual("|" + "|".join(g.split_cells(line)) + "|\n", line)


class RealDocument(unittest.TestCase):
    def test_every_key_row_of_the_real_doc_round_trips(self):
        lines = g.DOC.read_text(encoding="utf-8").splitlines(keepends=True)
        bad = [i + 1 for i, k, c, _ in g.doc_rows(lines) if "|" + "|".join(c) + "|\n" != lines[i] and lines[i].endswith("|\n")]
        self.assertEqual(bad, [], "rows that the cell writer would change even without an edit")


if __name__ == "__main__":
    unittest.main()
