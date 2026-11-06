# How to change this service

Read this before you change anything. The project guide (`/ask` in Claude Code, or
`agents/ask.py`) uses this file to answer "how do I change X?" questions.

## Golden rules

1. **Business rules first.** If the change touches a rule in `docs/invariants.yaml`
   (INV-01 to INV-06), stop. A human decides first, then updates `docs/invariants.yaml`
   and writes a new ADR in `docs/adr/`. Only then change code.
2. **Tests first.** Write or change the tests, see them fail, then change the code.
3. **Never weaken a test** to make it pass. If a test is wrong, explain why in the commit.
4. **Never edit an existing Flyway migration.** Add a new one (`V2__...`, `V3__...`).
5. **No new dependency** without approval.
6. Follow `CLAUDE.md` (constructor injection, injected `Clock`, no `Instant.now()`,
   basis points as `int`, ProblemDetail errors with a `code`). ArchUnit checks several
   of these on every build (`ArchitectureRulesTest`).
7. Finish with `./mvnw verify` green, the commit checklist in `docs/agent-workflow.md`,
   and one line in `docs/ai-usage.md` if agents helped.

## Where things live

| You want to change | Look here first |
|---|---|
| A business rule (who may do what, which status changes are allowed) | `service/PricingExceptionService.java` (`create`, `decide`, `withdraw`, `supersede` and the private `require*` checks; every status change goes through `transition`) |
| Valid values (discount range, reason length) | `requireValidDiscount`, `requireValidReason`, `requireValidComment` in `service/PricingExceptionService.java`, `pricing.max-discount-bps` in `application.properties` |
| A use case, transactions, idempotency | `service/PricingExceptionService.java`, `service/IdempotencyService.java`, `service/IdempotencyTransactions.java` |
| An endpoint, headers, If-Match, ETag | `controller/PricingExceptionController.java`, `controller/ApplicationController.java`, `controller/IfMatch.java` |
| Error codes and HTTP status | `exception/ApiProblem.java` (one type with a factory per code), the business-rule exceptions in `exception/`, `exception/ApiExceptionHandler.java`, `docs/design.md` "API error codes" |
| Who the caller is (`X-User-Id`) | `config/CallerArgumentResolver.java`, `service/UserLookup.java` |
| Tables and columns | `src/main/resources/db/migration/` (new file), `model/*Entity.java`, `repository/*Repository.java` |
| Local seed data | `src/main/resources/db/seed/` (default profile only) |
| The UI | `src/main/resources/static/index.html`, `app.js` |

(Paths under `src/main/java/se/ataulnasar/pricingexception/` unless written in full.)

## Recipes

### Change a limit or a value (e.g. max discount from 100 to 150 bps)
1. Check whether a rule or ADR mentions the value (`docs/design.md` "Validation").
2. Update the tests that check the edges (`PricingExceptionServiceTest`, `WorkflowIT`): the new max is
   accepted, max + 1 is rejected.
3. Change `pricing.max-discount-bps` (and the UI label in `index.html` if it shows the range).
4. Update `docs/design.md` and the README if they state the value.

### Add a new field to a request (e.g. a customer segment)
1. Decide: is it part of what the reviewer approves? If yes, it must be immutable like the
   discount (INV-01). Write that down in an ADR.
2. Tests: service (`PricingExceptionServiceTest`), repository
   (`PricingExceptionRequestRepositoryTest`), API (`PricingExceptionControllerTest`, `WorkflowIT`).
3. Code: validation in the service, new Flyway migration, entity column in `model`,
   request/response records in `dto`, idempotency hash input if the field is in the
   create body (`IdempotencyHashTest` pins the hash).
4. Docs: `docs/design.md` API and tables.

### Add a new action or status (e.g. "revoke an approval")
1. **Business-rule change.** Update `docs/invariants.yaml` and add an ADR first:
   which statuses it moves between, who may do it, does it free the application's slot (INV-04).
2. Tests: extend the status × command table in `PricingExceptionServiceTest`, add role and
   owner tests, an end-to-end IT, and a concurrency test if two people can race.
3. Code: a service method in one transaction that loads the row, checks the expected version
   (If-Match, INV-05), runs the `require*` checks in the documented order, and calls `transition`
   once so exactly one event is written (INV-02); a controller endpoint; error mapping.
4. The enum is stored as text, so a new status needs no migration, but check
   `active_application_id` logic. Update `docs/diagrams/state-machine.md`.

### Add a new endpoint
1. Write a `@WebMvcTest` (status codes, ProblemDetail `code`, 401 without `X-User-Id`) and an IT.
2. Controller calls a service, never a repository (ArchUnit enforces this).
3. Writes need `If-Match`; creates should accept `Idempotency-Key`.
4. Add it to `docs/design.md`, `http/requests.http` and, if useful, `scripts/demo.sh`.

### Change the database
1. New file `V<n>__short_name.sql` in `db/migration`, portable SQL for H2 and PostgreSQL.
2. Run `./mvnw verify` (H2) **and** the PostgreSQL tests (`docs/runbook.md`).
3. Note the H2 2.4.240 enum CHECK constraint issue in `docs/runbook.md` "Known issues".

## Doing it with the agent team

In Claude Code, describe the change and use the same loop as the phases:
test-writer writes tests → implementer codes → test-writer attacks → reviewer checks →
you approve and commit. See `docs/agent-workflow.md` and `.claude/commands/run-phase.md`.
