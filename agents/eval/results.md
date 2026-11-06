# AI review agent: eval results

Live AI eval not run: it needs an Anthropic API key. The Java tests catch all 6 seeded bugs (see the table). To run it: export ANTHROPIC_API_KEY and run agents/eval/run.sh.

Run on 2026-11-06T21:45Z. AI review: not run: it needs an Anthropic API key (export ANTHROPIC_API_KEY and rerun). Java tests: listed classes per case, after the Phase 12 move to the MVC structure (475 tests).

| case | expected | AI found | AI correct? | tests caught it? |
|---|---|---|---|---|
| inv01-approval-stores-rounded-discount | INV-01 | - | skipped | yes |
| inv02-withdraw-skips-event | INV-02 | - | skipped | yes |
| inv03-decline-skips-self-review-check | INV-03 | - | skipped | yes |
| inv04-no-precheck-and-no-active-slot | INV-04 | - | skipped | yes |
| inv05-decline-allowed-after-decision | INV-05 | - | skipped | yes |
| inv06-approve-skips-open-check | INV-06 | - | skipped | yes |
| clean01-extract-require-application-open | none | - | skipped | green (correct) |
| clean02-extract-record-decision-time | none | - | skipped | green (correct) |

Tests behaved as expected on 8 of 8 cases.
