# Pricing exception service

A small internal service for mortgage pricing exception requests. A relationship manager (RM)
asks for a discount on the standard interest rate of a mortgage application. A reviewer approves
or declines the request. The mortgage application process then reads the approved discount.

## The problem

An RM asks for a discount in basis points (bps) on the standard rate of a mortgage application.
A reviewer must approve or decline it. The mortgage application process needs the approved
discount. The service must:

- let an RM create a request with an application id, a discount in bps and a reason (R1);
- let a reviewer approve or decline, and show who decided and when (R2);
- give the approved discount to the mortgage application process (R3);
- show the history of a request, with its current status and earlier decisions (R4);
- avoid duplicate requests when an upstream system retries after a timeout (R5);
- be easy to run locally (R6);
- make and explain a decision on one conflict between stakeholders (R7, see below).

Details: [docs/design.md](docs/design.md) and [docs/requirements.md](docs/requirements.md).

## Quick start

You need Java 21. Docker is optional. It is only needed for the PostgreSQL tests. The `postgres`
profile needs a PostgreSQL database; see [docs/runbook.md](docs/runbook.md).

```bash
./mvnw verify
./mvnw spring-boot:run
scripts/demo.sh
```

1. `./mvnw verify` builds the project and runs all tests.
2. `./mvnw spring-boot:run` starts the app on port 8080 with H2 and seed data. Leave it running.
3. In a second terminal, `scripts/demo.sh` runs the full scenario. It needs `curl`, `jq` and the
   app running.

While the app runs:

- UI: open http://localhost:8080/ in a browser. Pick a user at the top right (the choice sets the
  `X-User-Id` header). RMs create, withdraw and supersede requests; reviewers approve or decline;
  `mortgage-system` looks up approved discounts. Open the same request in two tabs and decide it
  in both to see the `412` handling.
- H2 console: http://localhost:8080/h2-console. JDBC URL `jdbc:h2:mem:pricing;DB_CLOSE_DELAY=-1`,
  user `sa`, empty password.
- Health: http://localhost:8080/actuator/health

## Demo walkthrough

The scenario below is in `http/requests.http` (for the IntelliJ or VS Code REST client) and in
`scripts/demo.sh` (curl and jq). Every call needs the `X-User-Id` header with a seeded user.

Try one call by hand:

```bash
curl -s -H "X-User-Id: rm-erik" http://localhost:8080/api/applications
```

**The scenario:**

1. RM `rm-erik` creates a request for application `MA-1003`. It is PENDING with one event
   (`SUBMITTED`). The response has an `ETag` of `"0"`.
2. `rm-erik` reads the request. It is still PENDING.
3. Reviewer `rev-sara` approves it with `If-Match: "0"`. The discount is 40 bps. Her limit is 50.
   The request is APPROVED.
4. `rm-erik` reads it again. It has two events (`SUBMITTED`, `APPROVED`), `decidedBy` is
   `rev-sara`, `decidedAt` is set, and the `ETag` is `"1"`.
5. `mortgage-system` reads the approved discount for `MA-1003`. It gets 40 bps.
6. `rm-anna` supersedes the seed PENDING request on `MA-1002` (80 bps) with a new discount of
   60 bps. The old request becomes SUPERSEDED and points to the new one (`supersededById`). The
   new request is PENDING and points back (`supersedesId`). Both changes happen in one
   transaction.
7. Errors:
   - no `X-User-Id` header gives `401 UNAUTHENTICATED`;
   - a second decision on an APPROVED request gives `409 INVALID_TRANSITION`;
   - a withdrawal with no `If-Match` gives `428 IF_MATCH_REQUIRED`;
   - a withdrawal with a stale `If-Match` gives `412 VERSION_MISMATCH`;
   - a reviewer who tries to create a request gives `403 WRONG_ROLE`.

`scripts/demo.sh` runs steps 1 to 7 and prints each HTTP status. It ends with a summary line. Run
it once after a fresh start. A second run on the same data gets `409 ACTIVE_REQUEST_EXISTS` at
step 1 (printed as expected), skips steps 2 to 6 because the slot is taken and the seed request is
already superseded, and still runs the error examples in step 7.

### Seed users

| id | name | role | approval limit (bps) |
|---|---|---|---|
| `rm-anna` | Anna Lind | RM | – |
| `rm-erik` | Erik Berg | RM | – |
| `rev-sara` | Sara Holm | REVIEWER | 50 |
| `rev-johan` | Johan Ek | REVIEWER | 150 |
| `mortgage-system` | Mortgage process | MORTGAGE_PROCESS | – |

### Seed applications

| id | customer | standard rate (bps) | status | owner RM | requests in the seed |
|---|---|---|---|---|---|
| `MA-1001` | `CUST-0001` | 425 | OPEN | `rm-anna` | APPROVED, 30 bps (`00000000-0000-0000-0000-000000000001`) |
| `MA-1002` | `CUST-0002` | 410 | OPEN | `rm-anna` | PENDING, 80 bps (`00000000-0000-0000-0000-000000000002`) |
| `MA-1003` | `CUST-0003` | 450 | OPEN | `rm-erik` | DECLINED, 60 bps (`00000000-0000-0000-0000-000000000003`) |
| `MA-1004` | `CUST-0004` | 395 | CLOSED | `rm-erik` | none |

The seed data is in `src/main/resources/db/seed/V1000__local_seed_data.sql`.

## API in one table

| Method and path | Who | What | Main responses |
|---|---|---|---|
| `POST /api/pricing-exceptions` | RM | Create. Body: `applicationId`, `discountBps`, `reason` | 201 + `Location` + `ETag`; 404 application; 409 `ACTIVE_REQUEST_EXISTS` or `APPLICATION_NOT_OPEN`; 422 validation / key reuse |
| `GET /api/pricing-exceptions/{id}` | anyone | Request + status + decision + full history, incl. `supersedesId` / `supersededById` | 200 + `ETag`; 404 |
| `GET /api/pricing-exceptions?status=&applicationId=` | anyone | List / filter | 200 |
| `POST /api/pricing-exceptions/{id}/decision` | REVIEWER | Body: `outcome` (APPROVED / DECLINED), `comment` | 200; 403 self-review / over limit / wrong role; 409 not pending; 412; 428 |
| `POST /api/pricing-exceptions/{id}/withdrawal` | owner RM | Withdraw a pending request | 200; 403; 409; 412; 428 |
| `POST /api/pricing-exceptions/{id}/supersessions` | owner RM | Body: `discountBps`, `reason`. Replaces a pending request; accepts `Idempotency-Key` | 201 new request + `Location` + `ETag`; 403; 404; 409 `INVALID_TRANSITION` / `APPLICATION_NOT_OPEN`; 412; 422; 428 |
| `GET /api/applications/{id}/approved-discount` | MORTGAGE_PROCESS | `{applicationId, discountBps, requestId, decidedBy, decidedAt}` | 200; 403; 404 `APPLICATION_NOT_FOUND` / `NO_APPROVED_DISCOUNT` |
| `GET /api/applications`, `GET /api/users` | anyone | Seed data for the UI | 200 |

Every endpoint needs an `X-User-Id` header with a seeded user id. Errors are RFC 9457
ProblemDetail responses (`application/problem+json`) with a `code` field. The codes are in the
table in [docs/design.md, "API error codes"](docs/design.md#api-error-codes).

## The stakeholder decision: supersede instead of edit

**The conflict (R7).** One stakeholder wants RMs to change the discount after they submit. Another
wants an approval to always apply to the exact request that was reviewed. Both cannot be true if
a request can be edited.

**The decision.** A submitted request never changes (INV-01). To use a different discount, the RM
*supersedes* a PENDING request:

- a new request is created, linked to the old one with `supersedesId`;
- the old request becomes SUPERSEDED;
- both changes happen in one transaction;
- an approval stores `approvedDiscountBps`, the discount the reviewer saw.

So an approval always applies to the request that was reviewed. The history shows every discount
that was asked for. The reasoning is in [ADR-001](docs/adr/ADR-001.md). The state changes are in
[docs/diagrams/state-machine.md](docs/diagrams/state-machine.md).

## Assumptions

- **Identity.** The caller is the user id in the `X-User-Id` header. The users are seeded. There
  is no real login.
- **Ownership.** Any RM may create a request for any application. The app does not check who owns
  the application. The owner RM of a request is the only one who may withdraw or supersede it.
- **Discounts.** A discount is a whole number of basis points from 1 to 100. The upper limit is
  set with `pricing.max-discount-bps`.
- **Length.** "Characters" means UTF-16 units (`String.length()`). This is also how `@Size`
  counts.
- **One active request.** An application has at most one active request, which is PENDING or
  APPROVED (INV-04). An APPROVED request keeps the slot. So after an approval, the application
  cannot get a new request. This is by design.
- **Time.** Timestamps are stored with microsecond precision.

## Scope choices

**In scope:**

- the HTTP API: create, read, list, decision, withdrawal, supersede, approved discount, and the
  seed lists for the UI;
- `Idempotency-Key` on create and supersede;
- optimistic locking with `ETag` and `If-Match`;
- supersede, with links both ways in the history;
- the mortgage lookup;
- H2 by default and PostgreSQL with the `postgres` profile, both with Flyway;
- seed data for the default profile;
- ArchUnit rules for the package structure;
- Testcontainers PostgreSQL tests, which run in CI;
- a single-page UI at `/` (plain HTML and JavaScript, no build step).

**Out of scope:**

- real authentication and authorisation;
- pagination;
- notifications;
- closing applications through the API;
- audit beyond the `request_event` history.

## Requirement → test

One main test per requirement. The full list, with the deliverables and constraints, is in
[docs/requirements.md](docs/requirements.md).

| ID | Requirement | Main test |
|---|---|---|
| R1 | An RM creates a request for an existing application, with a discount in bps and a reason. | `WorkflowIT.INV02_each_transition_writes_one_event` |
| R2 | A reviewer approves or declines. The outcome shows who decided and when. | `WorkflowIT.INV02_decline_gives_etag_1_two_events_microsecond_decidedAt_and_final_state_rejects_everything` |
| R3 | An approved request is available to the mortgage application process. | `SupersedeIT.approved_discount_lifecycle_404_before_200_after` |
| R4 | Internal users can see the history of a request: current status and earlier decisions. | `WithdrawalIT.INV02_get_after_approval_shows_full_history_in_order` |
| R5 | No duplicate requests when an upstream system retries after a timeout. | `ConcurrencyIT.idempotent_parallel_creates_with_same_key_create_one_request_and_all_return_same_id` |
| R6 | Easy to run locally. Mortgage applications and users may be modelled as we choose. | `PricingExceptionServiceApplicationTests.contextLoads` (H2, Flyway and seed data on start) |
| R7 | Decide the conflict: "RMs change the discount" versus "an approval applies to the exact request reviewed". | `SupersedeIT.INV01_no_endpoint_changes_discount_after_approval` |

## Before production

1. **Real login and authorisation.** Replace trust in the `X-User-Id` header. Today `/api/users`
   lists every identity, and any of them can be assumed through the header.
2. **Lock the application when approving.** Today the application is read without a lock. An
   application closed between the check and the commit could still receive an approval (INV-06
   race). Lock or version-check the application row.
3. **Enum CHECK constraints.** Add the constraints for status, role and event type back in a new
   Flyway migration. Do this when Spring Boot ships an H2 release with the fix for h2database
   issue #4342 (see `docs/runbook.md`, "Known issues").
4. **Revoke or expire an approval.** Today an APPROVED request keeps the application's slot
   forever, so the application can never get a new discount. A reviewer revoke action, or an
   expiry date, would free the slot.
5. **Open reviewer notes from phases 3 to 5:**
   - The `ACTIVE_REQUEST_EXISTS` mapping finds the unique index by its name in the database
     error message. That text depends on the driver.
   - The approved discount column is nullable in the database. Only the domain guarantees a
     value for APPROVED rows. Consider a CHECK or NOT NULL rule.
   - The approved-discount and `supersededById` lookups load the full event history. They need one
     column. Use projections.
   - `idempotency_record.created_at` has no index for the hourly purge.
   - The stored idempotency response fits `VARCHAR(16000)` only because a fresh create has one
     event and no comment.
   - The list endpoint sorts in memory and has no paging.
6. **Observability.** Add structured logs, metrics and tracing. Today there is only
   `/actuator/health`.
7. **Secrets and configuration per environment.** The `postgres` profile has placeholder
   credentials (`pricing` / `pricing`).

## AI review agent in CI (bonus)

Every pull request gets one advisory comment, "AI review (advisory)", from `agents/review.py`. The
script sends the diff and the rules from `agents/review-prompt.md` (the same rules as the human
reviewer agent in `.claude/agents/reviewer.md`) to the Anthropic API, validates the answer against
`agents/finding.schema.json`, drops every finding that does not point at a line in the diff, and
prints markdown. The workflow is `.github/workflows/ai-review.yml`; it needs the repository secret
`ANTHROPIC_API_KEY` and never blocks a merge. The model and limits are in `agents/config.json`.

How the output is controlled and checked:

- the answer is validated after the fact against the finding format; findings that do not match are
  dropped and counted, and an answer that is not JSON at all is reported as an error, not posted as a review;
- findings outside the diff are dropped, so the agent cannot comment on code it did not see;
- the agent is advisory; the tests in `./mvnw verify` decide whether a change is green;
- `agents/eval/` holds eight patches (one per invariant INV-01 to INV-06 that breaks it, plus two
  clean refactors). `agents/eval/run.sh` applies each one, asks the agent, and runs the listed tests.
  Live AI eval not run: it needs an Anthropic API key. The Java tests catch all 6 seeded bugs (see the table). To run it: export ANTHROPIC_API_KEY and run agents/eval/run.sh. The results file is `agents/eval/results.md`; the AI column
  is recorded as it comes out, misses included;
- `agents/tests/` has unit tests for the script (diff parsing, anchoring, schema, error paths); CI runs them.

Remaining risks: the agent sees only the diff, not the whole code base; findings can only point at
lines that survive in the new version, so a deleted file or line is reported through its context; a
wrong "no findings" can reassure a reviewer; costs scale with diff size (the diff is cut at
`max_diff_chars`); anyone who can push a same-repo branch can also edit the workflow that holds the
secret, so the secret should live in a protected environment before wider use.

## Ask the project guide

A read-only guide answers questions about what was built, how and why, and how to change
something safely. It answers from the repository only (the rules, design, ADRs, the AI usage
log, the git history and the source files that match the question) and names the files it used.

- In Claude Code: `/ask "How do I add a revoke action for an approval?"` runs the agent in
  `.claude/agents/project-guide.md`.
- From the command line: `python3 agents/ask.py "How does idempotency work?"`. It needs
  `ANTHROPIC_API_KEY`; `--dry-run` shows which files would be sent and needs no key.
- Both follow the same instructions, `agents/project-guide-prompt.md`, and the change recipes in
  [docs/how-to-change.md](docs/how-to-change.md). Read that file before you change anything.

## Time spent

About **6 hours** of my own working time in total:

| Part | Hours |
|---|---|
| Design and planning (design, ADR decisions, invariants, agent setup) | 1 |
| Setup (Phase 0: project, GitHub, CI) | 0.5 |
| Building the service (Phases 1–5) | 2 |
| Docs, UI and AI review agent (Phases 6–8) | 1.5 |
| Manual testing and fixes | 1 |
| **Total** | **6** |

The hours are my hands-on time: planning, reviewing each phase report, deciding,
and testing. The AI agents ran the phases partly on their own while I reviewed,
so their run time is not counted separately.

## AI usage

The service was built in phases with AI agents, with tests first and a reviewer after each
phase. See [docs/ai-usage.md](docs/ai-usage.md) for what each agent did and how it was checked,
[docs/agent-workflow.md](docs/agent-workflow.md) for the plan, and [.claude/agents/](.claude/agents/)
for the agent definitions.

## More documentation

- [docs/design.md](docs/design.md): the design, decisions, tables and API.
- [docs/invariants.yaml](docs/invariants.yaml): the business rules (INV-01 to INV-06).
- [docs/adr/](docs/adr/): architecture decision records (ADR-001 to ADR-006).
- [docs/diagrams/state-machine.md](docs/diagrams/state-machine.md): request states and the supersede sequence.
- [docs/diagrams/architecture.md](docs/diagrams/architecture.md): system context, layers, decision flow, tables.
- [docs/diagrams/development-flow.md](docs/diagrams/development-flow.md): how the AI agents and the human built each phase.
- [docs/runbook.md](docs/runbook.md): configuration, health, error codes, resetting the database, logs, PostgreSQL tests.
- [docs/requirements.md](docs/requirements.md): the requirements and where each one is proved.
- [docs/how-to-change.md](docs/how-to-change.md): golden rules, where things live, and recipes for common changes.
- [http/requests.http](http/requests.http): the HTTP walkthrough.
- OpenAPI: the app serves `/v3/api-docs` and a Swagger UI at `/swagger-ui/index.html` (springdoc). Send the `X-User-Id` header when you try calls from it.
