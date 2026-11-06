# AI review agent: evaluation set

Eight patches against the current HEAD, each changing only `src/main/.../service/PricingExceptionService.java`.
- `inv01..inv06-*.patch`: each breaks exactly one business rule (INV-01..INV-06) in a subtle way.
- `clean01-*.patch`, `clean02-*.patch`: refactors that keep behaviour. The reviewer should report nothing.
- `expected.json`: per case the patch file, the expected invariant (`INV-0N` or `none`), a description,
  and the test classes that fail when the patch is applied (empty for clean patches).
- `run.sh` applies each patch in a scratch git worktree (`git apply`), feeds the diff to `agents/review.py`,
  and compares the finding with `expected`.
- For the "tests caught it?" column, `run.sh` runs `./mvnw -q test -Dtest=<tests>` in the same worktree.
- Output is a table: case, expected, found, tests caught it. Misses are results, not errors.
- Patches must stay applicable: re-check with `git apply --check` after any change to `src/main`.
