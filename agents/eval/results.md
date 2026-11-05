# AI review agent: eval results

Live AI eval not run: it needs an Anthropic API key. The Java tests catch all 6 seeded bugs (see the table). To run it: export ANTHROPIC_API_KEY and run agents/eval/run.sh.

Java tests run on 2026-11-05 (listed classes per case, each patch applied in a scratch worktree):

| case | expected | tests caught it? |
|---|---|---|
| inv01-approval-stores-rounded-discount | INV-01 | yes |
| inv02-withdraw-skips-event | INV-02 | yes |
| inv03-decline-skips-self-review-check | INV-03 | yes |
| inv04-no-precheck-and-no-active-slot | INV-04 | yes |
| inv05-decline-allowed-after-decision | INV-05 | yes |
| inv06-approve-skips-open-check | INV-06 | yes |
| clean01-extract-require-application-open | none | green (correct) |
| clean02-mapper-extract-approved-discount | none | green (correct) |

Tests behaved as expected on 8 of 8 cases. The AI column is written by `agents/eval/run.sh` when a key is set.
