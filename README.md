# Pricing exception service

A small internal service for mortgage pricing exception requests. A relationship manager (RM)
asks for a discount, in basis points, on the standard interest rate of a mortgage application.
A reviewer approves or declines it. The mortgage application process reads the approved discount.

## The problem

The service must let an RM create a request with an application id, a discount and a reason
(R1); let a reviewer approve or decline it and show who decided and when (R2); give the approved
discount to the mortgage process (R3); show the history of a request (R4); avoid duplicate
requests when an upstream system retries after a timeout (R5); be easy to run locally (R6); and
decide one conflict between stakeholders (R7, below). Each requirement and the test that proves
it: [docs/requirements.md](docs/requirements.md).

## Quick start

You need Java 21. Docker is optional; it is only used by the PostgreSQL tests.

```bash
./mvnw verify
```

```bash
./mvnw spring-boot:run
```

The first command builds and runs all tests. The second starts the app on port 8080 with an
in-memory H2 database and seed data. Then open http://localhost:8080/ and pick a user at the top
right; the choice sets the `X-User-Id` header that every call needs. RMs create, withdraw and
supersede requests; reviewers approve or decline; `mortgage-system` looks up approved discounts.

Also available while it runs: Swagger UI at `/swagger-ui/index.html`, health at
`/actuator/health`, and the H2 console at `/h2-console` (JDBC URL
`jdbc:h2:mem:pricing;DB_CLOSE_DELAY=-1`, user `sa`, empty password).

## Three-minute demo

Seed users: `rm-anna` and `rm-erik` (RMs), `rev-sara` (reviewer, limit 50 bps), `rev-johan`
(reviewer, limit 150 bps), `mortgage-system`. Seed applications `MA-1001` to `MA-1004`; the last
one is CLOSED. The full seed is listed in [docs/design.md](docs/design.md).

1. As `rm-erik`, create a request for `MA-1003` with 40 bps. It is PENDING with one event,
   `SUBMITTED`, and ETag `"0"`.
2. As `rev-sara`, approve it. The service checks she is not the requester and that 40 is within
   her limit of 50. The request is APPROVED, `decidedBy` is `rev-sara`, the ETag is `"1"`.
3. As `mortgage-system`, read the approved discount for `MA-1003`. It returns 40 bps.
4. As `rm-anna`, supersede the seed PENDING request on `MA-1002` (80 bps) with 60 bps. The old
   request becomes SUPERSEDED and links to the new one; the new one is PENDING and links back.
   Both changes happen in one transaction.
5. Open the same request in two browser tabs and decide it in both. The second decision fails
   with `412 VERSION_MISMATCH`, because decisions carry the version they saw (`If-Match`).

The same steps run automatically in `WorkflowIT` on every build. One call by hand:

```bash
curl -s -H "X-User-Id: rm-erik" http://localhost:8080/api/applications
```

## The main design decision: supersede instead of edit

**The conflict (R7).** One stakeholder wants RMs to change the discount after they submit. Another
wants an approval to always apply to the exact request that was reviewed. Both cannot be true if
a request can be edited.

**The decision.** A submitted request never changes (INV-01). To use a different discount, the RM
*supersedes* a PENDING request: a new request is created and linked to the old one, the old one
becomes SUPERSEDED, both in one transaction. An approval stores the discount the reviewer saw. So
an approval always applies to the request that was reviewed, and the history shows every discount
that was asked for. Reasoning: [ADR-001](docs/adr/ADR-001.md). States:
[docs/diagrams/state-machine.md](docs/diagrams/state-machine.md).

The other rules the service enforces, INV-01 to INV-06, are in
[docs/invariants.yaml](docs/invariants.yaml). Every business rule lives in one class,
`PricingExceptionService`, and every status change writes exactly one history event.

## API

Eight endpoints under `/api`: create, read, list, decision, withdrawal, supersession, the
approved-discount lookup, and the seed lists for the UI. Every call needs `X-User-Id`. Writes use
`ETag` / `If-Match` (optimistic locking) and creates accept `Idempotency-Key`. Errors are RFC 9457
ProblemDetail responses with a stable `code`. The full table with responses and error codes is in
[docs/design.md](docs/design.md#http-api).

## Assumptions

- **Identity.** The caller is the user id in the `X-User-Id` header. Users are seeded. No real login.
- **Ownership.** Any RM may create a request for any application. Only the RM who created a
  request may withdraw or supersede it.
- **Discounts.** A whole number of basis points from 1 to 100 (`pricing.max-discount-bps`).
- **One active request.** An application has at most one PENDING or APPROVED request (INV-04). An
  APPROVED request keeps the slot, so the application cannot get a new request after an approval.
- **Time.** Timestamps are stored with microsecond precision. Text lengths count UTF-16 units.

## Scope choices

**In:** the HTTP API above; `Idempotency-Key` on create and supersede; `ETag` / `If-Match`;
supersede with links both ways; H2 by default and PostgreSQL with the `postgres` profile, both
with Flyway; seed data; ArchUnit rules for the package structure; Testcontainers PostgreSQL tests
in CI; a single-page UI with no build step.

**Out:** real authentication and authorisation; pagination; notifications; closing applications
through the API; audit beyond the `request_event` history.

## Before production

1. **Real login and authorisation.** Replace trust in the `X-User-Id` header; today any seeded
   identity can be assumed.
2. **Lock the application when approving.** The application is read without a lock, so one closed
   between the check and the commit could still receive an approval (INV-06 race).
3. **Enum CHECK constraints.** Add them back in a new migration once Spring Boot ships an H2 with
   the fix for h2database issue #4342 (see [docs/runbook.md](docs/runbook.md), "Known issues").
4. **Revoke or expire an approval.** An APPROVED request keeps the application's slot forever.
5. **Observability.** Structured logs, metrics and tracing; today there is only `/actuator/health`.
6. **Secrets per environment.** The `postgres` profile has placeholder credentials.
7. Smaller open reviewer notes (driver-dependent error mapping, nullable approved discount, list
   without paging, and others) are in [docs/runbook.md](docs/runbook.md#open-notes-before-production).

## Time spent

About **6 hours** of my own working time:

| Part | Hours |
|---|---|
| Design and planning (design, ADR decisions, invariants, agent setup) | 1 |
| Setup (project, GitHub, CI) | 0.5 |
| Building the service (phases 1 to 5) | 2 |
| Docs, UI and AI review agent (phases 6 to 8) | 1.5 |
| Manual testing and fixes | 1 |

The hours are my hands-on time: planning, reviewing each phase report, deciding and testing. The
AI agents ran the phases partly on their own while I reviewed, so their run time is not counted.

## AI usage

The service was built in phases with AI agents: tests first, a reviewer after each phase, and a
human approval before every commit. [docs/ai-usage.md](docs/ai-usage.md) records what each agent
did per phase, how it was checked, and one case where an agent was wrong.
[docs/agent-workflow.md](docs/agent-workflow.md) is the plan; [.claude/agents/](.claude/agents/)
holds the agent definitions. The bonus part, an AI review agent that comments on every pull
request and was evaluated against seeded bugs, is described in
[docs/ai-review-agent.md](docs/ai-review-agent.md).

## More documentation

- [docs/design.md](docs/design.md): the design, decisions, seed data, tables and the full API.
- [docs/invariants.yaml](docs/invariants.yaml): the business rules (INV-01 to INV-06).
- [docs/adr/](docs/adr/): architecture decision records ADR-001 to ADR-007.
- [docs/diagrams/](docs/diagrams/): state machine, architecture, development flow.
- [docs/runbook.md](docs/runbook.md): configuration, health, error codes, database reset, logs, PostgreSQL tests.
- [docs/requirements.md](docs/requirements.md): the requirements and where each one is proved.
- [docs/how-to-change.md](docs/how-to-change.md): golden rules, where things live, recipes for common changes.
- [docs/ai-review-agent.md](docs/ai-review-agent.md): the AI review agent in CI and the project guide.
