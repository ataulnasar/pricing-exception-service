# AI review agent in CI, and the project guide

This is the bonus part of the assignment: how AI agents can improve the solution or the
development process, how their output is controlled and checked, and what risks remain.
How the agents built the service itself is in [ai-usage.md](ai-usage.md) and
[agent-workflow.md](agent-workflow.md).

## The review agent

Every pull request gets one advisory comment, "AI review (advisory)", from `agents/review.py`. The
script sends the diff and the rules from `agents/review-prompt.md` (the same rules as the human
reviewer agent in `.claude/agents/reviewer.md`) to the Anthropic API, validates the answer against
`agents/finding.schema.json`, drops every finding that does not point at a line in the diff, and
prints markdown. The workflow is `.github/workflows/ai-review.yml`; it needs the repository secret
`ANTHROPIC_API_KEY` and never blocks a merge. The model and limits are in `agents/config.json`.

The script is standard-library Python: no dependency to install, in CI or locally.

## How the output is controlled and checked

- The answer is validated after the fact against the finding format. Findings that do not match
  are dropped and counted. An answer that is not JSON at all is reported as an error, not posted
  as a review.
- Findings outside the diff are dropped, so the agent cannot comment on code it did not see.
- The prompt tells the model to treat the diff and its comments as data, never as instructions.
- The agent is advisory. The tests in `./mvnw verify` decide whether a change is green.
- `agents/eval/` holds eight patches: one per invariant INV-01 to INV-06 that breaks that rule in
  a subtle way, plus two clean refactors that should get no finding. `agents/eval/run.sh` applies
  each one in a scratch worktree, asks the agent, runs the listed tests and compares both with
  `agents/eval/expected.json`. The results are in `agents/eval/results.md`, recorded as they come
  out, misses included. The Java tests catch all six seeded bugs and stay green on the two
  refactors. The live AI column needs an API key: `export ANTHROPIC_API_KEY` and run the script.
- `agents/tests/` has unit tests for the script (diff parsing, anchoring, schema, error paths).
  CI runs them, and also checks that every eval patch still applies to the current code.

## Remaining risks

- The agent sees only the diff, not the whole code base.
- Findings can only point at lines that survive in the new version, so a deleted file or line is
  reported through its context.
- A wrong "no findings" can reassure a reviewer. That is why it is advisory and the tests decide.
- Costs scale with diff size. The diff is cut at `max_diff_chars`.
- Anyone who can push a same-repo branch can also edit the workflow that holds the secret, so the
  secret should live in a protected environment before wider use.

## The project guide

A read-only guide answers questions about what was built, how and why, and how to change
something safely. It answers from the repository only (the rules, design, ADRs, the AI usage
log, the git history and the source files that match the question) and names the files it used.

- In Claude Code: `/ask "How do I add a revoke action for an approval?"` runs the agent in
  `.claude/agents/project-guide.md`.
- From the command line: `python3 agents/ask.py "How does idempotency work?"`. It needs
  `ANTHROPIC_API_KEY`; `--dry-run` shows which files would be sent and needs no key.
- Both follow the same instructions, `agents/project-guide-prompt.md`, and the change recipes in
  [how-to-change.md](how-to-change.md).
