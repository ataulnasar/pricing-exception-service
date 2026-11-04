---
description: Run one phase of docs/agent-workflow.md end to end with the agent team, then stop for human approval before commit.
argument-hint: <phase number, e.g. 1>
---

Run **Phase $ARGUMENTS** from `docs/agent-workflow.md` using the agent team.
Read `CLAUDE.md`, `docs/design.md`, `docs/invariants.yaml` and `docs/agent-workflow.md` first.

## Steps — do them in order, without asking me between steps

1. **Check the current state.** Run `git status` and `./mvnw verify`.
   If this phase is already partly done (tests or code exist), continue from where it is.
   Do not redo finished work.
2. **Tests first.** If the tests for this phase do not exist yet, use the **test-writer**
   agent with the step-1 prompt for this phase from `docs/agent-workflow.md`.
3. **Code.** You are the implementer. Make the tests pass with the step-2 prompt for this phase.
   Never change a test to make it pass.
   If you think a test is wrong, use the **test-writer** agent to fix it and explain why.
4. **Attack.** Use the **test-writer** agent to attack the new code.
   For each new failing test: real bug → fix the code. Wrong test → test-writer fixes or removes it, with a reason.
5. **Review.** Use the **reviewer** agent on all uncommitted changes.
   Fix every `high` finding. List `medium` and `low` findings for me; do not fix them unless they are quick and safe.
6. **Verify.** Run `./mvnw verify`. It must be green.
   Repeat steps 4–6 at most **2 times**. If it is still not green, stop and explain.

## Stop and ask me instead of guessing when
- a business rule in `docs/invariants.yaml` would have to change,
- a new dependency is needed,
- `docs/design.md` is unclear or contradicts a test.

## Do NOT
- commit or push,
- delete or weaken a test without a written reason,
- change `docs/invariants.yaml` or existing Flyway migrations.

## When done, show me (short, simple English)
1. **Result:** verify green or red, test count.
2. **What each agent did** (test-writer, implementer, attack, reviewer).
3. **Bugs found** and who found them (tests, attack or review). Include cases where an agent was wrong.
4. **Decisions you made** that the design did not cover.
5. **Reviewer findings** still open (medium / low).
6. **Draft line for `docs/ai-usage.md`** in its format (do not write it yet).
7. **The commit message** from `docs/agent-workflow.md` for this phase.

Then wait. I will check and say "commit".
