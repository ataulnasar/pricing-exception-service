# AI review agent: eval results

Run on 2026-11-08T00:04Z. AI review: not run: it needs an Anthropic API key (export ANTHROPIC_API_KEY and rerun). Java tests: listed classes per case.

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
