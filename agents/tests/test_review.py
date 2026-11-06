"""Unit tests for agents/review.py. Run: python3 -m unittest discover -s agents/tests -v

Written from the intended behaviour (git diff semantics, the schema, "never crash, always
explain, exit 2/3"), not from the code. Judgement calls are marked with '# JUDGEMENT:'."""
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

AGENTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AGENTS))
import review  # noqa: E402

REAL_CONFIG = json.loads((AGENTS / "config.json").read_text())
SCHEMA = json.loads((AGENTS / "finding.schema.json").read_text())

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

SIMPLE = "--- a/a.txt\n+++ b/a.txt\n@@ -1,2 +1,3 @@\n a\n+b\n c\n"


def finding(**kw):
    base = {"id": "F1", "rule": "INV-03", "severity": "high", "file": "src/main/java/Foo.java",
            "line": 11, "problem": "p", "suggested_test": ""}
    base.update(kw)
    return base


def response_with(text, stop="end_turn"):
    return {"stop_reason": stop, "content": [{"type": "text", "text": text}]}


class ParseDiffTest(unittest.TestCase):
    def test_new_file_line_numbers_cover_added_and_context_lines_only(self):
        files = review.parse_diff(DIFF)
        self.assertEqual(set(files), {"src/main/java/Foo.java"})  # deleted file is not reviewable
        self.assertEqual(files["src/main/java/Foo.java"], {10, 11, 12, 13, 14, 41, 42})

    def test_tests_touched_detects_test_files(self):
        self.assertTrue(review.tests_touched(DIFF))
        self.assertFalse(review.tests_touched("--- a/src/main/X.java\n+++ b/src/main/X.java\n"))

    def test_crlf_line_endings_give_the_same_line_numbers(self):
        files = review.parse_diff(SIMPLE.replace("\n", "\r\n"))
        self.assertEqual(files, {"a.txt": {1, 2, 3}})

    def test_rename_uses_the_new_path_and_hunk_lines(self):
        diff = ("diff --git a/old.txt b/new.txt\nsimilarity index 90%\nrename from old.txt\n"
                "rename to new.txt\n--- a/old.txt\n+++ b/new.txt\n@@ -1 +1 @@\n-x\n+y\n")
        self.assertEqual(review.parse_diff(diff), {"new.txt": {1}})

    def test_binary_file_and_a_file_without_hunks_add_no_lines(self):
        diff = (SIMPLE
                + "diff --git a/i.png b/i.png\nBinary files a/i.png and b/i.png differ\n"
                + "diff --git a/m.sh b/m.sh\nold mode 100644\nnew mode 100755\n"
                + "--- a/n.txt\n+++ b/n.txt\n")  # headers, but no hunks
        files = review.parse_diff(diff)
        self.assertEqual(files["a.txt"], {1, 2, 3})
        self.assertEqual(files.get("n.txt", set()), set())
        self.assertNotIn("i.png", files)

    def test_single_line_range_header_and_no_newline_marker(self):
        diff = "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n\\ No newline at end of file\n+new\n\\ No newline at end of file\n"
        self.assertEqual(review.parse_diff(diff), {"x": {1}})

    def test_path_with_spaces_and_git_quoted_path(self):
        files = review.parse_diff("--- a/my dir/my file.txt\t\n+++ b/my dir/my file.txt\t\n@@ -1 +1 @@\n+a\n")
        self.assertEqual(set(files), {"my dir/my file.txt"})
        # JUDGEMENT: git quotes non-ASCII paths ("b/sp\303\244.txt"); the key must at least lose
        # the quote and the b/ prefix, otherwise no finding can ever anchor on such a file.
        files = review.parse_diff('--- a/x\n+++ "b/sp\\303\\244.txt"\n@@ -0,0 +1 @@\n+a\n')
        self.assertTrue(all(not k.startswith('"') and not k.startswith("b/") for k in files), files)

    def test_deletions_only_and_new_file_from_dev_null(self):
        diff = ("--- a/d.txt\n+++ b/d.txt\n@@ -3,2 +2,0 @@\n-gone\n-gone2\n"
                "--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1,3 @@\n+1\n+2\n+3\n")
        files = review.parse_diff(diff)
        self.assertEqual(files["d.txt"], set())
        self.assertEqual(files["new.txt"], {1, 2, 3})

    def test_form_feed_or_unicode_separator_inside_a_line_is_not_a_line_break(self):
        # git splits lines on \n only. str.splitlines() also splits on \x0c, \x1c,   ...
        for sep in ("\x0c", " ", "\x85"):
            with self.subTest(sep=repr(sep)):
                diff = f"--- /dev/null\n+++ b/x.c\n@@ -0,0 +1,2 @@\n+a{sep} b\n+c\n"
                self.assertEqual(review.parse_diff(diff)["x.c"], {1, 2})

    def test_added_line_that_looks_like_a_file_header_stays_in_its_file(self):
        # JUDGEMENT: an added line with content "++ not a header" appears as "+++ not a header".
        # A hunk-aware parser (count the hunk lengths) handles it; the current one switches file.
        diff = "--- a/x\n+++ b/x\n@@ -1,1 +1,3 @@\n+first\n+++ not a header\n+third\n"
        files = review.parse_diff(diff)
        self.assertEqual(set(files), {"x"})
        self.assertEqual(files["x"], {1, 2, 3})


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

    def test_line_zero_negative_deleted_file_and_a_prefix(self):
        files = {"a.txt": {1, 2, 3}}
        kept, dropped = review.anchor_findings([
            finding(id="F1", file="a.txt", line=0),
            finding(id="F2", file="a.txt", line=-1),
            finding(id="F3", file="gone.txt", line=1),
            finding(id="F4", file="a/a.txt", line=2),
            finding(id="F5", file="a.txt", line=4),
        ], files)
        self.assertEqual([f["id"] for f in kept], ["F4"])
        self.assertEqual([f["id"] for f in dropped], ["F1", "F2", "F3", "F5"])

    def test_short_path_matching_two_files_is_dropped_not_guessed(self):
        files = {"m1/Foo.java": {1}, "m2/Foo.java": {1}}
        kept, dropped = review.anchor_findings([finding(file="Foo.java", line=1)], files)
        self.assertEqual(kept, [])
        self.assertEqual(len(dropped), 1)


class SchemaTest(unittest.TestCase):
    def test_valid_result_passes(self):
        self.assertEqual(review.validate({"findings": [finding()], "checked": ["x"]}, SCHEMA), [])

    def test_bad_severity_missing_field_and_extra_property_fail(self):
        errors = review.validate({"findings": [finding(severity="blocker")]}, SCHEMA)
        self.assertTrue(any("severity" in e for e in errors))
        bad = finding(); del bad["problem"]
        self.assertTrue(any("problem" in e for e in review.validate({"findings": [bad]}, SCHEMA)))
        self.assertTrue(any("unexpected" in e for e in review.validate({"findings": [finding(extra=1)]}, SCHEMA)))
        self.assertTrue(any("line" in e for e in review.validate({"findings": [finding(line="11")]}, SCHEMA)))
        self.assertTrue(review.validate({"nope": []}, SCHEMA))

    def test_each_malformed_result_is_rejected(self):
        cases = {
            "extra top-level property": {"findings": [], "summary": "x"},
            "checked is not an array": {"findings": [], "checked": "INV-01"},
            "checked has a non-string": {"findings": [], "checked": [1]},
            "line is a float": {"findings": [finding(line=11.0)]},  # JUDGEMENT: strict; 11.0 is rejected
            "line is a bool": {"findings": [finding(line=True)]},
            "line is zero": {"findings": [finding(line=0)]},
            "severity upper case": {"findings": [finding(severity="HIGH")]},
            "id f1": {"findings": [finding(id="f1")]},
            "id with newline": {"findings": [finding(id="F1\n")]},
            "empty problem": {"findings": [finding(problem="")]},
            "empty file": {"findings": [finding(file="")]},
            "finding is null": {"findings": [None]},
            "findings is an object": {"findings": {}},
        }
        for name, result in cases.items():
            with self.subTest(name):
                self.assertTrue(review.validate(result, SCHEMA), name)

    def test_boundary_values_are_accepted_and_one_past_is_rejected(self):
        self.assertEqual(review.validate({"findings": [finding(line=1, problem="x" * 1000, rule="r" * 80,
                                                               suggested_test="s" * 500)]}, SCHEMA), [])
        for bad in (finding(problem="x" * 1001), finding(rule="r" * 81), finding(suggested_test="s" * 501)):
            self.assertTrue(review.validate({"findings": [bad]}, SCHEMA))


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

    def test_two_json_objects_never_crash_and_never_merge(self):
        text = '{"findings": []} {"findings": [], "checked": ["x"]}'
        try:
            result = review.parse_findings_json(text)
        except ValueError:
            return  # rejected cleanly: fine
        self.assertEqual(result["findings"], [])  # or the first object; never a merged one

    def test_braces_inside_strings_and_in_the_prose_around_the_json(self):
        obj = {"findings": [finding(problem='use } and { and "{x}" here')], "checked": ["a } b"]}
        body = json.dumps(obj)
        for name, text in {
            "bare": body,
            "prose before": "Sure, here it is:\n" + body,
            "fenced": "```json\n" + body + "\n```",
            "braces in prose after": body + "\nNote: placeholders look like {this}.",  # real-world model habit
            "braces in prose before": "Format {json}:\n" + body,
        }.items():
            with self.subTest(name):
                self.assertEqual(review.parse_findings_json(text), obj)

    def test_upper_case_fence_bom_and_whitespace_only(self):
        obj = {"findings": []}
        self.assertEqual(review.parse_findings_json("```JSON\n" + json.dumps(obj) + "\n```"), obj)
        self.assertEqual(review.parse_findings_json("﻿" + json.dumps(obj)), obj)
        with self.assertRaises(ValueError):
            review.parse_findings_json("  \n\t ")

    def test_response_text_with_empty_or_missing_content(self):
        self.assertEqual(review.response_text({"content": []}), "")
        self.assertEqual(review.response_text({"stop_reason": "end_turn"}), "")


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

    def render(self, findings, files=None, dropped=()):
        return review.render_markdown({"findings": findings, "checked": []}, list(dropped),
                                      files if files is not None else {"a.txt": {1}}, SIMPLE, "m")

    def test_pipe_and_newline_in_every_text_cell_cannot_break_the_table(self):
        md = self.render([finding(rule="a|b\nc", problem="x | y\nz\r\nw", suggested_test="t|\nu")])
        rows = [l for l in md.splitlines() if l.startswith("| F1 ")]
        self.assertEqual(len(rows), 1)
        # JUDGEMENT: a lone \r also ends a line for some renderers; count unescaped pipes in the row
        unescaped = rows[0].replace("\\|", "")
        self.assertEqual(unescaped.count("|"), 7)
        self.assertNotIn("\r", md)

    def test_severity_order_missing_suggested_test_and_zero_files(self):
        items = [finding(id="F1", severity="low"), finding(id="F2", severity="high"),
                 finding(id="F3", severity="medium"), finding(id="F4", severity="high")]
        for f in items:
            del f["suggested_test"]
        md = self.render(items, files={})
        order = [l.split("|")[1].strip() for l in md.splitlines() if l.startswith("| F")]
        self.assertEqual(order, ["F2", "F4", "F3", "F1"])  # high (stable), medium, low
        self.assertIn("0 file(s) in the diff.", md)

    def test_html_in_model_text_is_shown_as_text(self):
        # JUDGEMENT: CLAUDE.md says "show user text as text, never as HTML". The problem text is
        # model output influenced by the diff (prompt injection); raw tags go into the comment.
        md = self.render([finding(problem="<script>alert(1)</script><img src=x onerror=y>")])
        self.assertNotIn("<script>", md)
        self.assertNotIn("<img", md)

    def test_comment_stays_under_the_github_limit_of_65536_characters(self):
        # JUDGEMENT: GitHub rejects comments over 65536 chars (422), the workflow step then fails.
        # The schema has no maxItems, so many long findings can exceed it. Expect a cap.
        many = [finding(id=f"F{i}", problem="x" * 1000) for i in range(100)]
        self.assertLessEqual(len(self.render(many)), 65536)


class EndToEndTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.d = Path(self.dir.name)

    def run_main(self, response=None, diff=DIFF, config=None, diff_bytes=None, write_diff=True):
        diff_path, out, js = self.d / "d.diff", self.d / "o.md", self.d / "f.json"
        out.unlink(missing_ok=True)
        js.unlink(missing_ok=True)
        if write_diff:
            diff_path.write_bytes(diff_bytes if diff_bytes is not None else diff.encode())
        cfg = self.d / "config.json"
        cfg.write_text(json.dumps(REAL_CONFIG if config is None else config))
        args = ["--diff", str(diff_path), "--config", str(cfg), "--output", str(out), "--json-out", str(js)]
        if response is not None:
            resp = self.d / "r.json"
            resp.write_text(response if isinstance(response, str) else json.dumps(response))
            args += ["--response-file", str(resp)]
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            code = review.main(args)
        return (code, out.read_text() if out.exists() else None,
                json.loads(js.read_text()) if js.exists() else None, err.getvalue())

    def test_valid_response_keeps_only_anchored_findings_and_exits_zero(self):
        result = {"findings": [finding(id="F1", line=11), finding(id="F2", line=300)], "checked": ["INV-01..06"]}
        code, md, kept, _ = self.run_main(response_with(json.dumps(result)))
        self.assertEqual(code, 0)
        self.assertEqual([f["id"] for f in kept], ["F1"])
        self.assertIn("1 finding(s) were dropped", md)

    def test_invalid_json_from_the_model_is_reported_not_crashed(self):
        code, md, kept, _ = self.run_main(response_with("I think it is fine."))
        self.assertEqual(code, 3)
        self.assertIn("did not return valid JSON", md)
        self.assertEqual(kept, [])

    def test_invalid_finding_is_dropped_and_reported_not_fatal(self):
        code, md, kept, _ = self.run_main(response_with(json.dumps({"findings": [finding(severity="huge")]})))
        self.assertEqual(code, 0)
        self.assertEqual(kept, [])
        self.assertIn("1 finding(s) were dropped because they did not match the finding schema", md)

    def test_broken_envelope_still_exits_3(self):
        for bad in ({"nope": []}, {"findings": "x"}):
            code, md, *_ = self.run_main(response_with(json.dumps(bad)))
            self.assertEqual(code, 3)
            self.assertIn("did not match the finding schema", md)

    def test_empty_diff_is_a_clean_review(self):
        code, md, *_ = self.run_main(response_with("{}"), diff="")
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


    def test_missing_diff_file_is_a_clean_error_exit_2(self):
        # JUDGEMENT: the task asks for a clean exit 2; today FileNotFoundError escapes (traceback, exit 1).
        code, *_ = self.run_main(response=response_with("{}"), write_diff=False)
        self.assertEqual(code, 2)

    def test_missing_or_broken_or_incomplete_config_is_exit_2(self):
        for name, setup in {"missing": None, "not json": "{nope", "no model": {}}.items():
            with self.subTest(name):
                diff_path = self.d / "d.diff"
                diff_path.write_text(SIMPLE)
                cfg = self.d / "c.json"
                if isinstance(setup, str):
                    cfg.write_text(setup)
                elif isinstance(setup, dict):
                    cfg.write_text(json.dumps(setup))
                resp = self.d / "r.json"
                resp.write_text(json.dumps(response_with('{"findings": []}')))
                with contextlib.redirect_stderr(io.StringIO()):
                    code = review.main(["--diff", str(diff_path), "--config", str(cfg),
                                        "--response-file", str(resp), "--output", str(self.d / "o.md")])
                self.assertEqual(code, 2)

    def test_exit_3_paths_never_raise_and_still_write_markdown_and_json_out(self):
        cases = {
            "empty content list": {"stop_reason": "end_turn", "content": []},
            "whitespace only": response_with("  \n "),
            "refusal": {"stop_reason": "refusal", "content": []},
            "max_tokens, truncated, no closing brace": response_with('{"findings":[{"id":"F1","rule":"INV-03"', "max_tokens"),
            "max_tokens, truncated after an inner brace": response_with('{"findings":[{"id":"F1","rule":"x"}', "max_tokens"),
            "response is a list": [],  # JUDGEMENT: a malformed response file / API body must not crash
            "text block with null text": {"content": [{"type": "text", "text": None}]},
        }
        for name, response in cases.items():
            with self.subTest(name):
                code, md, kept, _ = self.run_main(response=response)
                self.assertEqual(code, 3)
                self.assertIn(review.MARKER, md)
                self.assertEqual(kept, [])
        code, md, _, _ = self.run_main(response=response_with('{"findings":[{"id":"F1"', "max_tokens"))
        self.assertIn("cut off at max_tokens", md)

    def test_unparseable_response_file_is_exit_3(self):
        code, md, kept, _ = self.run_main(response="not json at all")
        self.assertEqual((code, kept), (3, []))
        self.assertIn(review.MARKER, md)

    def test_truncation_note_and_default_when_max_diff_chars_is_not_configured(self):
        code, md, _, _ = self.run_main(response=response_with('{"findings": []}'),
                                       config=dict(REAL_CONFIG, max_diff_chars=20))
        self.assertEqual(code, 0)
        self.assertIn("only the first 20 were reviewed", md)
        # No max_diff_chars key: the check uses a default of 120000, so the note must use it too.
        big = "--- a/x\n+++ b/x\n@@ -1 +1 @@\n" + "+a\n" * 45000
        config = {k: v for k, v in REAL_CONFIG.items() if k != "max_diff_chars"}
        code, md, _, _ = self.run_main(diff=big, response=response_with('{"findings": []}'), config=config)
        self.assertEqual(code, 0)
        self.assertIn("120000", md)

    def test_diff_that_is_not_valid_utf8_is_still_reviewed(self):
        # git diff prints file bytes as they are; a Latin-1 source file must not crash the review.
        diff = b"--- a/x\n+++ b/x\n@@ -1 +1 @@\n+caf\xe9\n"
        code, md, kept, _ = self.run_main(diff_bytes=diff, response=response_with(
            json.dumps({"findings": [finding(file="x", line=1)]})))
        self.assertEqual(code, 0)
        self.assertEqual(len(kept), 1)

    def test_finding_on_a_line_the_model_could_not_see_after_truncation_is_dropped(self):
        diff = SIMPLE + "--- a/b.txt\n+++ b/b.txt\n@@ -1 +1 @@\n+z\n"
        code, md, kept, _ = self.run_main(diff=diff, config=dict(REAL_CONFIG, max_diff_chars=len(SIMPLE)),
                                          response=response_with(json.dumps({"findings": [finding(file="b.txt", line=1)]})))
        self.assertEqual((code, len(kept)), (0, 0))
        self.assertIn("dropped because they did not point at a line in the diff", md)


class CallApiTest(unittest.TestCase):
    class Ok:
        def __enter__(self):
            return self
        def __exit__(self, *a):
            return False
        def read(self):
            return b'{"content": []}'

    def http_error(self, code):
        return urllib.error.HTTPError(review.API_URL, code, "x", {}, io.BytesIO(b'{"error": "nope"}'))

    def test_401_is_not_retried_and_the_key_never_appears_in_the_error(self):
        with mock.patch.object(review.urllib.request, "urlopen", side_effect=self.http_error(401)) as op, \
                mock.patch.object(review.time, "sleep") as sleep:
            with self.assertRaises(review.ApiError) as ctx:
                review.call_api(REAL_CONFIG, {}, "fake-test-key-not-real")
        self.assertEqual(op.call_count, 1)
        sleep.assert_not_called()
        self.assertNotIn("fake-test-key-not-real", str(ctx.exception))

    def test_429_then_success_retries_with_backoff_and_503_forever_gives_up(self):
        with mock.patch.object(review.urllib.request, "urlopen", side_effect=[self.http_error(429), self.Ok()]), \
                mock.patch.object(review.time, "sleep") as sleep:
            self.assertEqual(review.call_api(REAL_CONFIG, {}, "k"), {"content": []})
        sleep.assert_called_once_with(2.0)
        with mock.patch.object(review.urllib.request, "urlopen", side_effect=lambda *a, **k: (_ for _ in ()).throw(self.http_error(503))) as op, \
                mock.patch.object(review.time, "sleep"):
            with self.assertRaises(review.ApiError):
                review.call_api(REAL_CONFIG, {}, "k")
        self.assertEqual(op.call_count, REAL_CONFIG["retries"] + 1)


if __name__ == "__main__":
    unittest.main()
