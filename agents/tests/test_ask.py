"""Unit tests for agents/ask.py. Run: python3 -m unittest discover -s agents/tests -v"""
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ask  # noqa: E402


def make_repo(tmp):
    root = Path(tmp)
    (root / "agents").mkdir()
    (root / "docs" / "adr").mkdir(parents=True)
    (root / "src" / "main" / "java").mkdir(parents=True)
    (root / "src" / "test" / "java").mkdir(parents=True)
    (root / "agents" / "config.json").write_text(json.dumps(
        {"model": "m", "max_tokens": 100, "anthropic_version": "v"}))
    (root / "agents" / "project-guide-prompt.md").write_text("You are the guide.")
    (root / "docs" / "invariants.yaml").write_text("INV-01 rule")
    (root / "docs" / "design.md").write_text("design")
    (root / "docs" / "adr" / "ADR-001.md").write_text("adr one")
    (root / "src" / "main" / "java" / "Supersede.java").write_text("class Supersede { supersede(); }")
    (root / "src" / "test" / "java" / "SupersedeTest.java").write_text("class SupersedeTest { supersede(); }")
    (root / "src" / "main" / "java" / "Other.java").write_text("class Other {}")
    return root


class KeywordTest(unittest.TestCase):
    def test_drops_stop_words_and_short_words_and_duplicates(self):
        self.assertEqual(ask.keywords("How do I change the supersede flow? Supersede it"),
                         ["supersede", "flow"])

    def test_keeps_identifiers(self):
        self.assertIn("If-Match", ask.keywords("Why is If-Match needed?"))


class ContextTest(unittest.TestCase):
    def test_core_files_come_first_in_rule_order_then_adrs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            self.assertEqual(ask.core_files(root),
                             ["docs/invariants.yaml", "docs/design.md", "docs/adr/ADR-001.md"])

    def test_code_files_match_question_and_prefer_main_over_test(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            files = ask.code_files_for(root, "How does supersede work?")
            self.assertEqual(files[0], "src/main/java/Supersede.java")
            self.assertIn("src/test/java/SupersedeTest.java", files)
            self.assertNotIn("src/main/java/Other.java", files)

    def test_code_files_limit(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            self.assertEqual(len(ask.code_files_for(root, "supersede", limit=1)), 1)

    def test_context_respects_size_limit_and_reports_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            (root / "docs" / "design.md").write_text("x" * 5000)
            context, included, skipped = ask.build_context(root, "supersede", max_chars=1000)
            self.assertLessEqual(len(context), 1000)
            self.assertIn("docs/invariants.yaml", included)
            self.assertIn("docs/design.md", skipped)

    def test_files_are_wrapped_as_data(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            context, _, _ = ask.build_context(root, "supersede")
            self.assertIn('<file path="docs/invariants.yaml">', context)

    def test_request_shape(self):
        body = ask.build_request({"model": "m", "max_tokens": 9}, "P", "CTX", " Why? ")
        self.assertEqual(body["system"], "P")
        self.assertEqual(body["messages"][0]["role"], "user")
        self.assertTrue(body["messages"][0]["content"].endswith("Why?"))


class MainTest(unittest.TestCase):
    def run_main(self, args, env=None):
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, env or {}, clear=True), redirect_stdout(out), redirect_stderr(err):
            code = ask.main(args)
        return code, out.getvalue(), err.getvalue()

    def test_dry_run_needs_no_key_and_makes_no_call(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            with mock.patch.object(ask.review, "call_api") as call:
                code, out, _ = self.run_main(["--root", str(root), "--dry-run", "supersede?"])
            self.assertEqual(code, 0)
            call.assert_not_called()
            self.assertIn("docs/invariants.yaml", out)

    def test_missing_key_exits_2_without_call(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            with mock.patch.object(ask.review, "call_api") as call:
                code, _, err = self.run_main(["--root", str(root), "why?"])
            self.assertEqual(code, 2)
            call.assert_not_called()
            self.assertIn("ANTHROPIC_API_KEY", err)

    def test_prints_answer_from_api(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            response = {"content": [{"type": "text", "text": "Because of ADR-001."}]}
            with mock.patch.object(ask.review, "call_api", return_value=response):
                code, out, _ = self.run_main(["--root", str(root), "why?"], {"ANTHROPIC_API_KEY": "k"})
            self.assertEqual(code, 0)
            self.assertIn("Because of ADR-001.", out)

    def test_api_error_exits_1(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(tmp)
            with mock.patch.object(ask.review, "call_api", side_effect=ask.review.ApiError("HTTP 401")):
                code, _, err = self.run_main(["--root", str(root), "why?"], {"ANTHROPIC_API_KEY": "k"})
            self.assertEqual(code, 1)
            self.assertIn("HTTP 401", err)


if __name__ == "__main__":
    unittest.main()
