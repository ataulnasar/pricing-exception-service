#!/usr/bin/env python3
"""AI review agent: reads a git diff, asks Claude for findings, keeps only findings anchored in
the diff, and prints a markdown comment.

Standard library only. Configuration in agents/config.json, prompt in agents/review-prompt.md,
finding format in agents/finding.schema.json. The API key comes from ANTHROPIC_API_KEY.

Exit codes: 0 = review produced (even with findings), 2 = usage or configuration error,
3 = API error (the markdown still explains what happened, so CI can post it as advisory).
"""
import argparse
import codecs
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
API_URL = "https://api.anthropic.com/v1/messages"
MARKER = "<!-- ai-review -->"
TITLE = "AI review (advisory)"
GITHUB_COMMENT_LIMIT = 65536
COMMENT_BUDGET = 60000  # leave room below GitHub's limit
REQUIRED_CONFIG = ("model", "max_tokens", "anthropic_version")


# ---------------------------------------------------------------- diff parsing

def git_lines(text):
    """Splits like git does: on \\n only (str.splitlines also splits on form feed, NEL and
    U+2028/2029, which shifts line numbers), and tolerates CRLF."""
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    return [line[:-1] if line.endswith("\r") else line for line in lines]


def unquote_path(path):
    """git quotes unusual paths as "b/sp\\303\\244.txt" (C-style escapes)."""
    if len(path) >= 2 and path.startswith('"') and path.endswith('"'):
        raw = codecs.escape_decode(path[1:-1].encode("utf-8"))[0]
        path = raw.decode("utf-8", "replace")
    return path


def strip_prefix(path):
    return path[2:] if path.startswith(("a/", "b/")) else path


def parse_diff(diff_text):
    """Returns {new_file_path: set(line numbers present in the new file side of the diff)}.

    Added and context lines count; removed lines do not exist in the new file. Deleted files
    are skipped. A hunk's line counts come from its @@ header, so content that happens to look
    like a file header ("+++ x") is not mistaken for one."""
    files = {}
    current = None
    new_line = 0
    remaining_old = remaining_new = 0
    for raw in git_lines(diff_text):
        in_hunk = remaining_old > 0 or remaining_new > 0
        if not in_hunk:
            if raw.startswith("+++ "):
                path = raw[4:].strip()
                if path == "/dev/null":
                    current = None
                else:
                    current = strip_prefix(unquote_path(path))
                    files.setdefault(current, set())
                continue
            m = re.match(r"^@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", raw)
            if m:
                remaining_old = int(m.group(1)) if m.group(1) is not None else 1
                new_line = int(m.group(2))
                remaining_new = int(m.group(3)) if m.group(3) is not None else 1
            continue
        if raw.startswith("\\"):  # "\ No newline at end of file"
            continue
        if raw.startswith("+"):
            if current is not None:
                files[current].add(new_line)
            new_line += 1
            remaining_new -= 1
        elif raw.startswith("-"):
            remaining_old -= 1
        else:  # context line (" ..." or an empty line)
            if current is not None:
                files[current].add(new_line)
            new_line += 1
            remaining_new -= 1
            remaining_old -= 1
    return files


def tests_touched(diff_text):
    for raw in git_lines(diff_text):
        if raw.startswith(("+++ ", "--- ")):
            path = strip_prefix(unquote_path(raw[4:].strip()))
            if path.startswith("src/test/"):
                return True
    return False


# ---------------------------------------------------------------- schema validation (subset)

def validate(instance, schema, path="$"):
    """Validates the small subset of JSON Schema used by finding.schema.json. Returns a list of
    error strings; empty means valid."""
    errors = []
    t = schema.get("type")
    if t == "object":
        if not isinstance(instance, dict):
            return [f"{path}: expected object"]
        for key in schema.get("required", []):
            if key not in instance:
                errors.append(f"{path}.{key}: required")
        props = schema.get("properties", {})
        for key, value in instance.items():
            if key in props:
                errors.extend(validate(value, props[key], f"{path}.{key}"))
            elif schema.get("additionalProperties") is False:
                errors.append(f"{path}.{key}: unexpected property")
    elif t == "array":
        if not isinstance(instance, list):
            return [f"{path}: expected array"]
        if "maxItems" in schema and len(instance) > schema["maxItems"]:
            errors.append(f"{path}: more than {schema['maxItems']} items")
        for i, item in enumerate(instance):
            errors.extend(validate(item, schema.get("items", {}), f"{path}[{i}]"))
    elif t == "string":
        if not isinstance(instance, str):
            return [f"{path}: expected string"]
        if "enum" in schema and instance not in schema["enum"]:
            errors.append(f"{path}: must be one of {schema['enum']}")
        if "minLength" in schema and len(instance) < schema["minLength"]:
            errors.append(f"{path}: too short")
        if "maxLength" in schema and len(instance) > schema["maxLength"]:
            errors.append(f"{path}: too long")
        if "pattern" in schema and not re.fullmatch(schema["pattern"], instance):
            errors.append(f"{path}: does not match {schema['pattern']}")
    elif t == "integer":
        if not isinstance(instance, int) or isinstance(instance, bool):
            return [f"{path}: expected integer"]
        if "minimum" in schema and instance < schema["minimum"]:
            errors.append(f"{path}: below minimum")
    return errors


# ---------------------------------------------------------------- model call

def build_request(config, prompt, diff_text):
    user = ("Review this git diff. Remember: return one JSON object only.\n\n"
            "```diff\n" + diff_text + "\n```")
    body = {
        "model": config["model"],
        "max_tokens": config["max_tokens"],
        "system": prompt,
        "messages": [{"role": "user", "content": user}],
        "output_config": {"effort": config.get("effort", "medium")},
    }
    return body


def call_api(config, body, api_key):
    """POST /v1/messages with retries on 429, 5xx and network errors. Returns the response JSON."""
    data = json.dumps(body).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "x-api-key": api_key,
        "anthropic-version": config["anthropic_version"],
    }
    attempts = int(config.get("retries", 3)) + 1
    delay = 2.0
    last_error = None
    for attempt in range(attempts):
        request = urllib.request.Request(API_URL, data=data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=config.get("timeout_seconds", 180)) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            text = e.read().decode("utf-8", "replace").replace(api_key, "***")
            last_error = f"HTTP {e.code}: {text[:500]}"
            if e.code in (429, 500, 502, 503, 504, 529) and attempt < attempts - 1:
                time.sleep(delay)
                delay *= 2
                continue
            raise ApiError(last_error)
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last_error = f"network error: {e}"
            if attempt < attempts - 1:
                time.sleep(delay)
                delay *= 2
                continue
            raise ApiError(last_error)
    raise ApiError(last_error or "unknown error")


class ApiError(Exception):
    pass


def response_text(response):
    if not isinstance(response, dict):
        raise ApiError("unexpected API response shape")
    if response.get("stop_reason") == "refusal":
        raise ApiError("the model refused the request (stop_reason=refusal)")
    if response.get("stop_reason") == "max_tokens":
        raise ApiError("the model output was cut off at max_tokens; raise max_tokens in agents/config.json")
    content = response.get("content")
    if not isinstance(content, list):
        return ""  # no text at all: reported as "did not return valid JSON" by the caller
    parts = [str(block.get("text") or "") for block in content
             if isinstance(block, dict) and block.get("type") == "text"]
    return "\n".join(parts).strip()


def parse_findings_json(text):
    """Finds the first JSON object in the model output: bare, inside a code fence, or surrounded
    by prose (even prose that itself contains braces)."""
    candidate = text.lstrip("\ufeff").strip()
    fence = re.search(r"```(?:json)?\s*(\{.*\})\s*```", candidate, re.S | re.I)
    if fence:
        candidate = fence.group(1)
    decoder = json.JSONDecoder()
    first_error = None
    for match in re.finditer(r"\{", candidate):
        try:
            obj, _ = decoder.raw_decode(candidate, match.start())
        except json.JSONDecodeError as e:
            first_error = first_error or e
            continue
        if isinstance(obj, dict):
            return obj
    if first_error is not None:
        raise ValueError(f"no complete JSON object in the model output ({first_error.msg})")
    raise ValueError("no JSON object in the model output")


# ---------------------------------------------------------------- filtering and rendering

def anchor_findings(findings, diff_files):
    """Keeps findings whose file and line are in the diff. Returns (kept, dropped)."""
    kept, dropped = [], []
    for f in findings:
        path = f["file"]
        if path.startswith("b/") or path.startswith("a/"):
            path = path[2:]
        lines = diff_files.get(path)
        if lines is None:
            # tolerate a path given relative to a deeper directory
            matches = [p for p in diff_files if p.endswith("/" + path) or path.endswith("/" + p)]
            lines = diff_files.get(matches[0]) if len(matches) == 1 else None
            if lines is not None:
                f = dict(f, file=matches[0])
        if lines is None or f["line"] not in lines:
            dropped.append(f)
        else:
            kept.append(f)
    return kept, dropped


SEVERITY_ORDER = {"high": 0, "medium": 1, "low": 2}


def render_markdown(result, dropped, diff_files, diff_text, model, note=None, invalid=0):
    """Renders the comment. Stays under GitHub's comment size limit by omitting the least
    severe findings if needed (with a note)."""
    findings = sorted(result.get("findings", []), key=lambda f: SEVERITY_ORDER.get(f["severity"], 9))
    omitted = 0
    while True:
        text = _render(dict(result, findings=findings), dropped, diff_files, diff_text, model, note, invalid, omitted)
        if len(text) <= COMMENT_BUDGET or not findings:
            return text
        findings = findings[:-1]
        omitted += 1


def _render(result, dropped, diff_files, diff_text, model, note, invalid, omitted):
    lines = [MARKER, f"## {TITLE}", ""]
    lines.append(f"Model: `{model}`. This review is advisory: it does not block the merge. "
                 "Rules: `docs/invariants.yaml`, prompt: `agents/review-prompt.md`.")
    lines.append("")
    if note:
        lines.append(f"> {md(note)}")
        lines.append("")
    findings = result.get("findings", [])
    if tests_touched(diff_text):
        lines.append("Note: this change touches files under `src/test/`. Check that no test was weakened.")
        lines.append("")
    if not findings:
        lines.append("**No findings** anchored in the diff.")
    else:
        lines.append("| # | Severity | Rule | Where | Problem | Suggested test |")
        lines.append("|---|---|---|---|---|---|")
        for f in findings:
            where = "`{}:{}`".format(md(f["file"]).replace("`", "'"), f["line"])
            lines.append("| {} | {} | {} | {} | {} | {} |".format(
                f["id"], f["severity"], md(f["rule"]), where, md(f["problem"]), md(f.get("suggested_test", ""))))
    lines.append("")
    checked = result.get("checked") or []
    if checked:
        lines.append("<details><summary>What was checked</summary>")
        lines.append("")
        lines.extend(f"- {md(c)}" for c in checked)
        lines.append("")
        lines.append("</details>")
        lines.append("")
    if omitted:
        lines.append(f"<sub>{omitted} low-priority finding(s) omitted to stay under GitHub's comment size limit.</sub>")
        lines.append("")
    if dropped:
        lines.append(f"<sub>{len(dropped)} finding(s) were dropped because they did not point at a line in the diff.</sub>")
        lines.append("")
    if invalid:
        lines.append(f"<sub>{invalid} finding(s) were dropped because they did not match the finding schema.</sub>")
        lines.append("")
    lines.append(f"<sub>{len(diff_files)} file(s) in the diff.</sub>")
    return "\n".join(lines) + "\n"


def md(text):
    """Model text goes into a markdown table cell as plain text: no HTML, no table breaks, no
    links or images, no live @mentions. Backticks are removed first so the mention code spans
    below are always well formed."""
    text = re.sub(r"\s*[\r\n]+\s*", " ", str(text)).replace("`", "'")
    text = html.escape(text, quote=False)
    text = text.replace("|", "\\|").replace("[", "\\[").replace("]", "\\]").replace("!", "\\!")
    return re.sub(r"@([A-Za-z0-9][A-Za-z0-9-]*)", r"`@\1`", text)


# ---------------------------------------------------------------- main

def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def read_diff(path):
    """git prints file bytes as they are; undecodable bytes must not stop the review."""
    if path:
        return Path(path).read_bytes().decode("utf-8", "replace")
    return sys.stdin.buffer.read().decode("utf-8", "replace")


def main(argv=None):
    parser = argparse.ArgumentParser(description="AI review of a git diff (advisory).")
    parser.add_argument("--diff", help="path to a unified diff; default: stdin")
    parser.add_argument("--config", default=str(HERE / "config.json"))
    parser.add_argument("--prompt", default=str(HERE / "review-prompt.md"))
    parser.add_argument("--schema", default=str(HERE / "finding.schema.json"))
    parser.add_argument("--output", help="write the markdown here (default: stdout)")
    parser.add_argument("--json-out", help="also write the kept findings as JSON here")
    parser.add_argument("--response-file", help="use this saved API response instead of calling the API (tests, eval replay)")
    args = parser.parse_args(argv)

    try:
        config = load_json(args.config)
        schema = load_json(args.schema)
        prompt = Path(args.prompt).read_text(encoding="utf-8")
        missing = [k for k in REQUIRED_CONFIG if k not in config]
        if missing:
            raise ValueError(f"config is missing {', '.join(missing)}")
        diff_text = read_diff(args.diff)
    except (OSError, ValueError) as e:
        print(f"configuration error: {e}", file=sys.stderr)
        return 2
    model = config["model"]

    if not diff_text.strip():
        return emit(render_markdown({"findings": [], "checked": []}, [], {}, "", model,
                                    note="The diff is empty; nothing to review."), [], args)
    note = None
    limit = int(config.get("max_diff_chars", 120000))
    if len(diff_text) > limit:
        note = f"The diff has {len(diff_text)} characters; only the first {limit} were reviewed."
        diff_text = diff_text[:limit]
    diff_files = parse_diff(diff_text)  # after truncation: anchors only on lines the model saw

    def failed(reason, code=3):
        emit(render_markdown({"findings": [], "checked": []}, [], diff_files, diff_text, model, note=reason), [], args)
        return code

    try:
        if args.response_file:
            response = load_json(args.response_file)
        else:
            api_key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
            if not api_key:
                return failed("ANTHROPIC_API_KEY is not set; the review did not run.")
            response = call_api(config, build_request(config, prompt, diff_text), api_key)
        result = parse_findings_json(response_text(response))
    except ApiError as e:
        return failed(f"The review could not run: {e}")
    except (ValueError, OSError, AttributeError, TypeError, KeyError) as e:
        return failed(f"The model did not return valid JSON: {e}")

    # Validate the envelope, then each finding on its own: one bad finding must not discard the review.
    envelope_errors = [e for e in validate(dict(result, findings=[]), schema)]
    if envelope_errors or not isinstance(result.get("findings"), list):
        return failed("The model output did not match the finding schema: " + "; ".join(envelope_errors[:5] or ["findings is not a list"]))
    item_schema = schema["properties"]["findings"]["items"]
    max_items = schema["properties"]["findings"].get("maxItems")
    findings_in = result["findings"]
    valid, invalid = [], 0
    if max_items is not None and len(findings_in) > max_items:
        invalid += len(findings_in) - max_items  # the cap is part of the schema; the rest is dropped
        findings_in = findings_in[:max_items]
    for f in findings_in:
        if validate(f, item_schema):
            invalid += 1
        else:
            valid.append(f)
    kept, dropped = anchor_findings(valid, diff_files)
    result = dict(result, findings=kept)
    return emit(render_markdown(result, dropped, diff_files, diff_text, model, note=note, invalid=invalid), kept, args)


def emit(markdown, findings, args):
    if args.output:
        Path(args.output).write_text(markdown, encoding="utf-8")
    else:
        sys.stdout.write(markdown)
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(findings, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
