#!/usr/bin/env bash
# Evaluates the AI review agent on agents/eval/expected.json.
# For every case: apply the patch in a scratch worktree, review the diff with agents/review.py,
# compare the findings with the expected invariant, and run the listed test classes to see
# whether the Java tests catch the break too. Prints a table and writes agents/eval/results.md.
#
#   agents/eval/run.sh              # everything (needs ANTHROPIC_API_KEY for the AI column)
#   RUN_TESTS=0 agents/eval/run.sh  # skip the Maven runs (AI column only)
#   ONLY=inv03 agents/eval/run.sh   # one case
#   RESULTS=/tmp/r.md agents/eval/run.sh  # write the table elsewhere
set -euo pipefail

ROOT=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
cd "$ROOT"
EVAL=agents/eval
EXPECTED=$EVAL/expected.json
RESULTS=${RESULTS:-$EVAL/results.md}   # override to keep an earlier results table
RUN_TESTS=${RUN_TESTS:-1}
ONLY=${ONLY:-}
command -v jq >/dev/null || { echo "jq is required"; exit 2; }
jq -e 'type == "array" and length > 0' "$EXPECTED" >/dev/null || { echo "$EXPECTED is not a non-empty JSON array"; exit 2; }

MODEL=$(jq -r .model agents/config.json)
if [ -n "${ANTHROPIC_API_KEY:-}" ]; then AI_MODE="live ($MODEL)"; else AI_MODE="not run: it needs an Anthropic API key (export ANTHROPIC_API_KEY and rerun)"; fi
TMP=$(mktemp -d)
cleanup() { rm -rf "$TMP"; git worktree prune; }
trap cleanup EXIT

rows=()
first_error=""
total=0; ai_hits=0; ai_runs=0; test_hits=0; test_runs=0

while IFS=$'\t' read -r case patch expected tests; do
  if [ -n "$ONLY" ] && [ "$case" != "$ONLY" ]; then continue; fi
  total=$((total + 1))
  wt="$TMP/wt-$case"
  expected=$(printf '%s' "$expected" | tr '[:lower:]' '[:upper:]')
  [ "$expected" = "NONE" ] && expected="none"
  git worktree add -q "$wt" HEAD
  if ! ( cd "$wt" && git apply "$ROOT/$EVAL/$patch" ); then
    echo "patch $patch does not apply"; git worktree remove --force "$wt"; exit 2
  fi
  ( cd "$wt" && git add -N . && git diff ) > "$TMP/$case.diff"   # -N: new files appear in the diff too

  # --- AI review
  found="-"; ai_ok="skipped"
  if [ -n "${ANTHROPIC_API_KEY:-}" ]; then
    ai_runs=$((ai_runs + 1))
    set +e
    python3 agents/review.py --diff "$TMP/$case.diff" --output "$TMP/$case.md" --json-out "$TMP/$case.json"
    rc=$?
    set -e
    if [ $rc -ne 0 ]; then
      # review.py explains the problem in a "> ..." note; show it so the table is self-explanatory
      reason=$(grep -m1 '^> ' "$TMP/$case.md" 2>/dev/null | sed 's/^> //' | cut -c1-160)
      found="error (exit $rc): ${reason:-no note}"; ai_ok="no (not run)"
      [ -z "${first_error:-}" ] && first_error="$reason"
    else
      found=$(jq -r '[.[] | "\(.severity) \(.rule)"] | join("; ")' "$TMP/$case.json")
      [ -z "$found" ] && found="none"
      if [ "$expected" = "none" ]; then
        # a clean refactor is a hit when no high finding is raised
        if jq -e '[.[] | select(.severity == "high")] | length == 0' "$TMP/$case.json" >/dev/null; then ai_ok="yes"; else ai_ok="no (false alarm)"; fi
      else
        # strict: the finding's rule must name the invariant (a mention in the problem text is not enough)
        if jq -e --arg inv "$expected" '[.[] | select(.rule | ascii_upcase | contains($inv))] | length > 0' "$TMP/$case.json" >/dev/null; then ai_ok="yes"; else ai_ok="no (missed)"; fi
      fi
      [ "$ai_ok" = "yes" ] && ai_hits=$((ai_hits + 1))
    fi
  fi

  # --- Java tests
  tests_caught="skipped"
  if [ "$RUN_TESTS" = "1" ]; then
    classes=$tests
    [ -z "$classes" ] && classes="WorkflowIT"   # clean refactors: the end-to-end suite must stay green
    test_runs=$((test_runs + 1))
    if ( cd "$wt" && ./mvnw -q test -Dtest="$classes" > "$TMP/$case.mvn.log" 2>&1 ); then
      if [ "$expected" = "none" ]; then tests_caught="green (correct)"; test_hits=$((test_hits + 1)); else tests_caught="no (green!)"; fi
    elif grep -qE '<<< (FAILURE|ERROR)!' "$TMP/$case.mvn.log"; then
      # a real test failure or error, not a build problem
      if [ "$expected" = "none" ]; then tests_caught="red (false alarm)"; else tests_caught="yes"; test_hits=$((test_hits + 1)); fi
    else
      tests_caught="build error (see log)"   # compile error or environment problem: counts as neither
      cp "$TMP/$case.mvn.log" "$EVAL/$case.build-error.log"
    fi
  fi

  rows+=("| $case | $expected | $found | $ai_ok | $tests_caught |")
  git worktree remove --force "$wt"
done < <(jq -r '.[] | [.case, .patch, .expected, (.tests | join(","))] | @tsv' "$EXPECTED")

if [ "$total" -eq 0 ]; then echo "no cases matched (ONLY=$ONLY)"; exit 2; fi
header="| case | expected | AI found | AI correct? | tests caught it? |"
sep="|---|---|---|---|---|"
{
  echo "# AI review agent: eval results"
  echo
  echo "Run on $(date -u +%Y-%m-%dT%H:%MZ). AI review: $AI_MODE. Java tests: $([ "$RUN_TESTS" = 1 ] && echo "listed classes per case" || echo skipped)."
  echo
  echo "$header"; echo "$sep"; printf '%s\n' "${rows[@]}"
  echo
  if [ -n "${ANTHROPIC_API_KEY:-}" ]; then
    echo "AI correct on $ai_hits of $ai_runs cases."
    if [ -n "${first_error:-}" ]; then echo; echo "At least one review call failed. First error: $first_error"; fi
  fi
  if [ "$RUN_TESTS" = "1" ]; then echo "Tests behaved as expected on $test_hits of $test_runs cases."; fi
} | tee "$RESULTS"
