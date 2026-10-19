#!/usr/bin/env python3
"""Fixture tests for gen-shapes.py (standard library only): python3 scripts/docs/test_gen_shapes.py"""
import importlib.util
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("gen_shapes", HERE / "gen-shapes.py")
gs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gs)

HEAD = "app:\n  scheduling:\n"


def lists(body):
    return gs.parse_lists((HEAD + body).splitlines())


class YamlReader(unittest.TestCase):
    def test_block_list(self):
        self.assertEqual(lists("    skip-bean-classes:\n      - a.B\n      - c.D\n")["skip-bean-classes"], ["a.B", "c.D"])

    def test_trailing_comment_on_key(self):
        self.assertEqual(lists("    skip-bean-classes:  # note\n      - a.B\n")["skip-bean-classes"], ["a.B"])

    def test_trailing_comment_on_item_and_comment_lines(self):
        got = lists("    skip-bean-classes:\n      # why\n      - a.B  # because\n")["skip-bean-classes"]
        self.assertEqual(got, ["a.B"])

    def test_flow_list(self):
        self.assertEqual(lists("    skip-bean-classes: [a.B, c.D]\n")["skip-bean-classes"], ["a.B", "c.D"])

    def test_comma_string(self):
        self.assertEqual(lists("    skip-bean-classes: a.B,c.D\n")["skip-bean-classes"], ["a.B", "c.D"])

    def test_quoted_items_lose_their_quotes(self):
        got = lists("    skip-bean-classes:\n      - \"a.B\"\n      - 'c.D'\n")["skip-bean-classes"]
        self.assertEqual(got, ["a.B", "c.D"])

    def test_prefix_pattern_and_keep_list(self):
        got = lists("    skip-bean-classes:\n      - com.x.*\n    keep-bean-classes:\n      - com.x.Y\n")
        self.assertEqual((got["skip-bean-classes"], got["keep-bean-classes"]), (["com.x.*"], ["com.x.Y"]))

    def test_other_keys_are_ignored_but_do_not_leak(self):
        got = lists("    skip-listener-classes:\n      - a.Listener\n    skip-bean-classes:\n      - a.B\n")
        self.assertEqual(got["skip-bean-classes"], ["a.B"])

    def test_scheduling_ends_at_dedent(self):
        got = lists("    skip-bean-classes:\n      - a.B\n  agent-jobs:\n    run-now-mode: enqueue\n")
        self.assertEqual(got["skip-bean-classes"], ["a.B"])

    def test_unparseable_input_raises_instead_of_returning_empty(self):
        for bad in (
            "    skip-bean-classes: [a.B,\n      c.D]\n",          # multi-line flow list
            "    skip-bean-classes: &anchor\n      - a.B\n",        # anchor
            "    skip-bean-classes: |\n      a.B\n",               # block scalar
            "    skip-bean-classes:\n      key: value\n",          # map where a list is expected
            "    skip-bean-classes:\n      - \"a.B\n",             # unterminated quote
            "    skip-bean-classes:\n      - a.B: x\n",            # not a class name
            "    skip-bean-classes:\n      - a.B\n    skip-bean-classes:\n      - c.D\n",  # duplicate key
            "    ??? weird\n",                                      # unclassifiable line
            "    skip-bean-classes:\n\t- a.B\n",                    # tab indentation
        ):
            with self.subTest(bad=bad):
                with self.assertRaises(gs.YamlError):
                    lists(bad)

    def test_missing_scheduling_block_raises(self):
        with self.assertRaises(gs.YamlError):
            gs.parse_lists("app:\n  other: 1\n".splitlines())

    def test_load_lists_exits_with_file_name(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "application-shape-x.yml"
            p.write_text(HEAD + "    skip-bean-classes: [a.B,\n      c.D]\n")
            with self.assertRaises(SystemExit) as cm:
                gs.load_lists(p)
            self.assertIn("application-shape-x.yml", str(cm.exception))

    def test_real_profiles_parse_non_empty(self):
        for n in ("api", "jobs", "jobs-core", "jobs-ingest"):
            got = gs.load_lists(gs.CFG / ("application-shape-%s.yml" % n))
            self.assertTrue(got["skip-bean-classes"] or got["keep-bean-classes"], n)
        jobs = gs.load_lists(gs.CFG / "application-shape-jobs.yml")
        self.assertTrue(jobs["pools"] and all(jobs["pools"].values()))
        self.assertTrue(set(jobs["pool-lifecycle"]) <= set(jobs["pools"]))

    def test_pools_map_with_block_flow_and_quoted_lists(self):
        got = lists("    pools:\n      # why\n      a:\n        - x.A  # c\n        - \"x.B\"\n"
                    "      b: [x.C, 'x.D']\n      c: x.E,x.F\n    skip-bean-classes:\n      - z.Z\n")
        self.assertEqual(got["pools"], {"a": ["x.A", "x.B"], "b": ["x.C", "x.D"], "c": ["x.E", "x.F"]})
        self.assertEqual(got["skip-bean-classes"], ["z.Z"])

    def test_pool_lifecycle_is_a_separate_map(self):
        got = lists("    pools:\n      a:\n        - x.A\n    pool-lifecycle:\n      a:\n        - x.L\n")
        self.assertEqual((got["pools"], got["pool-lifecycle"]), ({"a": ["x.A"]}, {"a": ["x.L"]}))

    def test_unparseable_pools_raise(self):
        for bad in (
            "    pools: [a.B]\n",                                   # a list where a map is expected
            "    pools:\n      a:\n        key: value\n",          # map item inside a pool
            "    pools:\n      a:\n        - x.A\n      a:\n        - x.B\n",  # duplicate pool
            "    pools:\n      - x.A\n",                          # list where a map is expected
            "    pools:\n      a: [x.A,\n        x.B]\n",          # multi-line flow list
            "    pools:\n      a:\n        - x.A\n    pools:\n      b:\n        - x.B\n",  # duplicate key
        ):
            with self.subTest(bad=bad):
                with self.assertRaises(gs.YamlError):
                    lists(bad)


class Matching(unittest.TestCase):
    def test_exact_prefix_and_keep_beats_skip(self):
        self.assertTrue(gs.matches("a.b.C", ["a.b.C"]))
        self.assertTrue(gs.matches("a.b.C", ["a.*"]))
        self.assertTrue(gs.matches("a.b.C", ["a.b*"]))
        self.assertFalse(gs.matches("a.bc.C", ["a.b.*"]))
        lists_ = {"skip-bean-classes": ["a.*"], "keep-bean-classes": ["a.K"]}
        self.assertTrue(gs.runs("a.K", lists_))
        self.assertFalse(gs.runs("a.Other", lists_))


class AllowReason(unittest.TestCase):
    def reason(self, text):
        return gs.allow_reason('@AllowUnlockedSchedule(reason = "%s")' % text)

    def test_word_see_inside_a_reason_is_kept(self):
        self.assertEqual(self.reason("Every replica must see its own queue; no shared state"),
                         "Every replica must see its own queue; no shared state.")
        self.assertEqual(self.reason("Per-JVM state, see the javadoc above."), "Per-JVM state.")

    def test_known_cross_references_are_stripped(self):
        self.assertEqual(self.reason("Pings own clients; see the javadoc above."), "Pings own clients.")
        self.assertEqual(self.reason("Drains own queue. See docs/plans/CORE-STATE-AUDIT.md."), "Drains own queue.")

    def test_concatenated_literals(self):
        got = gs.allow_reason('@AllowUnlockedSchedule(reason = "one " + "two.")')
        self.assertEqual(got, "one two.")


class ScanClass(unittest.TestCase):
    def scan(self, src, name="Foo"):
        with tempfile.TemporaryDirectory() as d:
            core = Path(d) / "mod" / "src" / "main" / "java"
            core.mkdir(parents=True)
            f = core / (name + ".java")
            f.write_text(src)
            old = gs.CORE
            gs.CORE = Path(d)
            try:
                return gs.scan_class(f)
            finally:
                gs.CORE = old

    def test_class_level_allow_after_the_word_class_in_javadoc(self):
        src = ('package p;\n/** this class is per-JVM. */\n'
               '@AllowUnlockedSchedule(reason = "Own state.")\npublic class Foo {\n'
               '  @Scheduled(fixedRate = 1)\n  void tick() {}\n}\n')
        info = self.scan(src)
        self.assertEqual(info["methods"], [(False, "Own state.")])


class Render(unittest.TestCase):
    API = {"skip-bean-classes": ["p.OnlyJobs"], "keep-bean-classes": [], "pools": {}, "pool-lifecycle": {}}

    def render(self, classes, pools, lifecycle=None, skip=None):
        jobs = {"skip-bean-classes": skip or ["p.Unrelated"], "keep-bean-classes": [],
                "pools": pools, "pool-lifecycle": lifecycle or {}}
        orig = gs.load_lists
        gs.load_lists = lambda path: self.API if "shape-api" in path.name else jobs
        try:
            return gs.render(classes)
        finally:
            gs.load_lists = orig

    @staticmethod
    def cls(name, methods=((True, None),), pkg="p"):
        return {"fqcn": pkg + "." + name, "name": name, "module": "m", "methods": list(methods)}

    def test_mixed_locked_and_unlocked_is_not_reported_as_plainly_locked(self):
        out = self.render([self.cls("Foo", [(True, None), (False, "Own state.")])], {"a": ["p.Foo"]})
        self.assertIn("advisory lock (+ 1 per-JVM method(s), see source)", out["also"])
        self.assertEqual(out["alsolocked"], "0 of 1")

    def test_pool_columns(self):
        classes = [self.cls("OnlyJobs"), self.cls("Pooled"), self.cls("Plain"), self.cls("ApiOnly")]
        out = self.render(classes, {"webhooks": ["p.Pooled"]}, skip=["p.ApiOnly"])
        self.assertIn("| `Pooled` | m | yes | yes, unless its pool is on | webhooks | advisory lock |", out["also"])
        self.assertIn("| `Plain` | m | yes | yes | - | advisory lock |", out["also"])
        self.assertIn("| `ApiOnly` | m | yes | - | - | advisory lock |", out["also"])
        self.assertIn("| `OnlyJobs` | m | - | yes | - | advisory lock |", out["owned"])
        self.assertTrue(out["owned"].startswith("| Class | Module | API shape | Main jobs pod | Own pool, when enabled |"))
        self.assertEqual(out["poolnames"], ["webhooks"])

    def test_pool_names_come_from_the_profile(self):
        classes = [self.cls("A"), self.cls("B"), self.cls("C")]
        out = self.render(classes, {"zeta": ["p.A"], "alpha": ["p.B"]})
        self.assertEqual(out["poolnames"], ["zeta", "alpha"])
        self.assertIn("| zeta |", out["also"])
        self.assertIn("| alpha |", out["also"])

    def test_per_jvm_pool_sentences(self):
        classes = [self.cls("A"), self.cls("B"), self.cls("Keep1", [(False, "r1.")]),
                   self.cls("Keep2", [(False, "r2.")]), self.cls("Skip", [(False, "r3.")])]
        out = self.render(classes, {"x": ["p.A"], "y": ["p.B"]}, {"y": ["p.Keep1", "p.Keep2"]})
        self.assertEqual(out["perjvmpools"],
                         "The `x` pool skips all of these: their jobs hold none of this state. "
                         "The `y` pool keeps 2 of them (every row except `Skip`).")

    def test_a_class_in_two_pools_is_refused(self):
        with self.assertRaises(SystemExit) as cm:
            self.render([self.cls("A")], {"x": ["p.A"], "y": ["p.A"]})
        self.assertIn("more than one pool", str(cm.exception))

    def test_a_pool_entry_that_matches_no_class_is_refused(self):
        with self.assertRaises(SystemExit) as cm:
            self.render([self.cls("A")], {"x": ["p.Gone"]})
        self.assertIn("p.Gone", str(cm.exception))

    def test_lifecycle_for_an_unknown_pool_or_a_non_per_jvm_class_is_refused(self):
        with self.assertRaises(SystemExit):
            self.render([self.cls("A")], {"x": ["p.A"]}, {"nope": ["p.A"]})
        with self.assertRaises(SystemExit):
            self.render([self.cls("A")], {"x": ["p.A"]}, {"x": ["p.A"]})

    def test_render_refuses_empty_profile_and_missing_pools(self):
        orig = gs.load_lists
        gs.load_lists = lambda p: {"skip-bean-classes": [], "keep-bean-classes": [], "pools": {}, "pool-lifecycle": {}}
        try:
            with self.assertRaises(SystemExit):
                gs.render([])
        finally:
            gs.load_lists = orig
        with self.assertRaises(SystemExit):
            self.render([self.cls("A")], {})

    def test_lock_note_must_still_be_in_the_source(self):
        c = self.cls("KnowledgeIngestionJobRunner", pkg="com.agenticai.core.knowledge")
        self.assertIn("per-job claiming", gs.lock_note(c, []))
        orig = gs.LOCK_NOTES
        gs.LOCK_NOTES = {c["fqcn"]: ("no-such-needle-in-the-source", "x")}
        try:
            with self.assertRaises(SystemExit):
                gs.lock_note(c, [])
        finally:
            gs.LOCK_NOTES = orig


class Splice(unittest.TestCase):
    DOC = ("**Jobs, `x` pool**\n<!-- gen-shapes:begin a -->\nold\n<!-- gen-shapes:end a -->\n"
           "n <!-- gen-shapes:begin alsolocked -->0<!-- gen-shapes:end alsolocked --> t\n")

    def test_block_and_inline_regions_are_replaced(self):
        got = gs.splice(self.DOC, {"a": "NEW", "alsolocked": "All 3", "poolnames": ["x"]})
        self.assertIn("<!-- gen-shapes:begin a -->\nNEW\n<!-- gen-shapes:end a -->", got)
        self.assertIn("begin alsolocked -->All 3<!--", got)

    def test_missing_marker_exits(self):
        with self.assertRaises(SystemExit):
            gs.splice(self.DOC, {"b": "x"})

    def test_pool_without_a_shapes_table_row_exits(self):
        with self.assertRaises(SystemExit) as cm:
            gs.splice(self.DOC, {"poolnames": ["x", "missing"]})
        self.assertIn("missing", str(cm.exception))


if __name__ == "__main__":
    unittest.main()
