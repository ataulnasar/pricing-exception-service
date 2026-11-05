"""Unit tests for agents/review.py. Run: python3 -m unittest discover -s agents/tests -v"""
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import review  # noqa: E402

DIFF = """diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java
index 1111111..2222222 100644
--- a/src/main/java/Foo.java
+++ b/src/main/java/Foo.java
@@ -10,4 +10,5 @@ class Foo {
     int a = 1;
-    int b = 2;
+    int b = 3;
+    int c = 4;
     return a;
 }
@@ -40,2 +41,2 @@ class Foo {
-    old();
+    fresh();
     done();
diff --git a/src/test/java/Gone.java b/src/test/java/Gone.java
deleted file mode 100644
--- a/src/test/java/Gone.java
+++ /dev/null
@@ -1,2 +0,0 @@
-class Gone {
-}
"""


def finding(**kw):
    base = {"id": "F1", "rule": "INV-03", "severity": "high", "file": "src/main/java/Foo.java",
            "line": 11, "problem": "p", "suggested_test": ""}
    base.update(kw)
    return base


class ParseDiffTest(unittest.TestCase):
    def test_new_file_line_numbers_cover_added_and_context_lines_only(self):
        files = review.parse_diff(DIFF)
        self.assertEqual(set(files), {"src/main/java/Foo.java"})  # deleted file is not reviewable
        self.assertEqual(files["src/main/java/Foo.java"], {10, 11, 12, 13, 14, 41, 42})

    def test_tests_touched_detects_test_files(self):
        self.assertTrue(review.tests_touched(DIFF))
        self.assertFalse(review.tests_touched("--- a/src/main/X.java\n+++ b/src/main/X.java\n"))


class AnchorTest(unittest.TestCase):
    def test_findings_outside_the_diff_are_dropped(self):
        files = review.parse_diff(DIFF)
        kept, dropped = review.anchor_findings([
            finding(id="F1", line=11),                       # changed line: kept
            finding(id="F2", line=10),                       # context line: kept
            finding(id="F3", line=30),                       # not in any hunk: dropped
            finding(id="F4", file="src/main/java/Bar.java"), # other file: dropped
            finding(id="F5", file="b/src/main/java/Foo.java", line=41),  # b/ prefix tolerated
            finding(id="F6", file="Foo.java", line=12),      # short path, unique match: kept
        ], files)
        self.assertEqual([f["id"] for f in kept], ["F1", "F2", "F5", "F6"])
        self.assertEqual([f["id"] for f in dropped], ["F3", "F4"])
        self.assertEqual(kept[3]["file"], "src/main/java/Foo.java")


class SchemaTest(unittest.TestCase):
    schema = json.loads((Path(__file__).resolve().parents[1] / "finding.schema.json").read_text())

    def test_valid_result_passes(self):
        self.assertEqual(review.validate({"findings": [finding()], "checked": ["x"]}, self.schema), [])

    def test_bad_severity_missing_field_and_extra_property_fail(self):
        errors = review.validate({"findings": [finding(severity="blocker")]}, self.schema)
        self.assertTrue(any("severity" in e for e in errors))
        bad = finding(); del bad["problem"]
        self.assertTrue(any("problem" in e for e in review.validate({"findings": [bad]}, self.schema)))
        self.assertTrue(any("unexpected" in e for e in review.validate({"findings": [finding(extra=1)]}, self.schema)))
        self.assertTrue(any("line" in e for e in review.validate({"findings": [finding(line="11")]}, self.schema)))
        self.assertTrue(review.validate({"nope": []}, self.schema))


class ParseModelOutputTest(unittest.TestCase):
    def test_bare_fenced_and_prose_wrapped_json_are_accepted(self):
        obj = {"findings": [], "checked": []}
        self.assertEqual(review.parse_findings_json(json.dumps(obj)), obj)
        self.assertEqual(review.parse_findings_json("```json\n" + json.dumps(obj) + "\n```"), obj)
        self.assertEqual(review.parse_findings_json("Here you go:\n" + json.dumps(obj) + "\nDone."), obj)

    def test_no_json_raises(self):
        with self.assertRaises(ValueError):
            review.parse_findings_json("no findings, all good")

    def test_refusal_is_an_api_error(self):
        with self.assertRaises(review.ApiError):
            review.response_text({"stop_reason": "refusal", "content": []})


class RenderTest(unittest.TestCase):
    def test_markdown_has_marker_title_table_and_test_warning(self):
        files = review.parse_diff(DIFF)
        md = review.render_markdown({"findings": [finding(problem="a | b")], "checked": ["c"]}, [finding(id="F9")], files, DIFF, "m")
        self.assertIn(review.MARKER, md)
        self.assertIn("## AI review (advisory)", md)
        self.assertIn("| F1 | high | INV-03 | `src/main/java/Foo.java:11` | a \\| b |", md)
        self.assertIn("touches files under `src/test/`", md)
        self.assertIn("1 finding(s) were dropped", md)

    def test_no_findings_message(self):
        md = review.render_markdown({"findings": []}, [], {}, "", "m")
        self.assertIn("**No findings**", md)


class EndToEndTest(unittest.TestCase):
    def run_main(self, response, diff=DIFF):
        with tempfile.TemporaryDirectory() as d:
            diff_path, resp_path, out_path, json_path = (Path(d) / n for n in ("d.diff", "r.json", "o.md", "f.json"))
            diff_path.write_text(diff)
            resp_path.write_text(json.dumps(response))
            code = review.main(["--diff", str(diff_path), "--response-file", str(resp_path),
                                "--output", str(out_path), "--json-out", str(json_path)])
            return code, out_path.read_text(), json.loads(json_path.read_text())

    @staticmethod
    def response_with(text):
        return {"stop_reason": "end_turn", "content": [{"type": "text", "text": text}]}

    def test_valid_response_keeps_only_anchored_findings_and_exits_zero(self):
        result = {"findings": [finding(id="F1", line=11), finding(id="F2", line=300)], "checked": ["INV-01..06"]}
        code, md, kept = self.run_main(self.response_with(json.dumps(result)))
        self.assertEqual(code, 0)
        self.assertEqual([f["id"] for f in kept], ["F1"])
        self.assertIn("1 finding(s) were dropped", md)

    def test_invalid_json_from_the_model_is_reported_not_crashed(self):
        code, md, kept = self.run_main(self.response_with("I think it is fine."))
        self.assertEqual(code, 3)
        self.assertIn("did not return valid JSON", md)
        self.assertEqual(kept, [])

    def test_invalid_finding_is_dropped_and_reported_not_fatal(self):
        code, md, kept = self.run_main(self.response_with(json.dumps({"findings": [finding(severity="huge")]})))
        self.assertEqual(code, 0)
        self.assertEqual(kept, [])
        self.assertIn("1 finding(s) were dropped because they did not match the finding schema", md)

    def test_broken_envelope_still_exits_3(self):
        for bad in ({"nope": []}, {"findings": "x"}):
            code, md, _ = self.run_main(self.response_with(json.dumps(bad)))
            self.assertEqual(code, 3)
            self.assertIn("did not match the finding schema", md)

    def test_empty_diff_is_a_clean_review(self):
        code, md, _ = self.run_main(self.response_with("{}"), diff="")
        self.assertEqual(code, 0)
        self.assertIn("The diff is empty", md)

    def test_missing_api_key_is_reported_without_calling_the_api(self):
        env = os.environ.pop("ANTHROPIC_API_KEY", None)
        try:
            with tempfile.TemporaryDirectory() as d:
                diff_path, out_path = Path(d) / "d.diff", Path(d) / "o.md"
                diff_path.write_text(DIFF)
                code = review.main(["--diff", str(diff_path), "--output", str(out_path)])
                self.assertEqual(code, 3)
                self.assertIn("ANTHROPIC_API_KEY is not set", out_path.read_text())
        finally:
            if env is not None:
                os.environ["ANTHROPIC_API_KEY"] = env

    def test_request_body_uses_documented_fields(self):
        body = review.build_request({"model": "m", "max_tokens": 10, "effort": "low", "anthropic_version": "v"}, "sys", "diff")
        self.assertEqual(set(body), {"model", "max_tokens", "system", "messages", "output_config"})
        self.assertEqual(body["messages"][0]["role"], "user")
        self.assertIn("diff", body["messages"][0]["content"])


if __name__ == "__main__":
    unittest.main()
