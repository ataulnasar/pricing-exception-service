# Agent workflow — the simple plan

## The team

| Agent | Model | Job | Can change |
|---|---|---|---|
| **You** | – | Decide, check, approve, commit | everything |
| **Implementer** (main Claude Code session) | Opus | Writes the code so the tests pass | `src/main/` (+ test fixes only if you say so) |
| **test-writer** | Sonnet | Writes tests first, later tries to break the code | `src/test/` only |
| **reviewer** | Sonnet | Checks the change against the rules | nothing (read only) |
| **docs-writer** | Haiku | README, ADRs, runbook; tests every command | `README.md`, `docs/` |

Why different models: the agent that checks the code should not be the same model
that wrote it. A cheap model is enough for docs.

## The same 5 steps in every phase

1. **Tests first** — ask the test-writer to write the tests for this phase.
   *You:* read the test names. Do they match the rules? If not, ask for changes.
2. **Code** — ask the implementer to make the tests pass.
   *You:* run `./mvnw verify`. It must be green.
3. **Attack** — ask the test-writer to attack the new code.
   *You:* for each new failing test: real bug → implementer fixes it. Wrong test → delete it.
4. **Review** — ask the reviewer to check the change.
   *You:* fix every `high` finding. Decide on the others.
5. **Commit** — check the checklist below, then commit and push. CI must be green.
   Write the time on your paper. Add one line to `docs/ai-usage.md`.

### Checklist before every commit
- [ ] `./mvnw verify` is green
- [ ] No test was deleted or weakened without my OK (`git diff src/test`)
- [ ] No new dependency without my OK (`git diff pom.xml`)
- [ ] I can explain what the code does in my own words
- [ ] One line added to `docs/ai-usage.md`

### Line format for `docs/ai-usage.md`
`Phase X — <agent>: <what it did>. I changed: <what>. Checked by: <tests / review / manual>.`

---

## Phase 1 — Domain core (business rules in plain Java)

**1. Tests first**
```
Use the test-writer agent. Write plain JUnit tests for the domain package
se.ataulnasar.pricingexception.domain, based on docs/design.md and docs/invariants.yaml.
Expected classes: PricingExceptionRequest (submit, approve, decline, withdraw,
markSuperseded, activeApplicationId), RequestStatus, DiscountBps, Reason, Actor, Role,
RequestEvent, InvalidTransitionException, ForbiddenActionException.
Include a parameterized test for every status x command, and tests for INV-01, INV-03, INV-05.
Use a fixed Clock.
```

**2. Code**
```
Implement the domain package so the domain tests pass. Plain Java only, no Spring or JPA.
Follow CLAUDE.md. Do not change the tests. Add an ArchUnit test that domain has no Spring/JPA imports.
```

**3. Attack** → `Use the test-writer agent to attack the domain package.`
**4. Review** → `Use the reviewer agent to review the uncommitted changes.`
**5. Commit** → `feat: domain model and business rules`

**Done when:** every status x command case is tested and green.

---

## Phase 2 — Database (Flyway, seed data, JPA)

**1. Tests first**
```
Use the test-writer agent. Write @DataJpaTest tests for the persistence layer:
saving and loading a request with its events, and INV-04 (a second active request for
the same application must fail with DataIntegrityViolationException). See the tables in docs/design.md.
```

**2. Code**
```
Create the Flyway migrations from docs/design.md: V1__init.sql (portable SQL for H2 and
Postgres, no partial indexes, no generated columns) and seed data in a separate location
used only in the default local profile. Add JPA entities with @Version, repositories and a
hand-written mapper to the domain objects. Set spring.jpa.hibernate.ddl-auto=validate.
If Flyway does not run on startup with Spring Boot 4, tell me what dependency is missing — do not guess.
```

**3. Attack · 4. Review · 5. Commit** → `feat: database schema, seed data and persistence`

**Done when:** the app starts, seed data is in the H2 console (`/h2-console`), tests green.

---

## Phase 3 — Core API

**1. Tests first**
```
Use the test-writer agent. Write @WebMvcTest tests (validation, 401 without X-User-Id,
403 for wrong role, 428 without If-Match, ProblemDetail with a code field) and one
@SpringBootTest workflow test: create -> get (1 event) -> approve -> get (2 events,
decidedBy and decidedAt set). Endpoints: create, get, list, decision, withdrawal, as in docs/design.md.
```

**2. Code**
```
Implement the API from docs/design.md: CallerResolver for X-User-Id, PricingExceptionService
(one transaction per command, save request and event together), controllers, DTO records,
ETag on create/get, If-Match on decision/withdrawal, and a @RestControllerAdvice with ProblemDetail.
decidedBy comes from the caller, never from the body. Start http/requests.http with the happy path.
```

**3. Attack · 4. Review · 5. Commit** → `feat: core API`

**Done when:** the happy path works from `http/requests.http`.

---

## Phase 4 — No duplicates, no double decisions

**1. Tests first**
```
Use the test-writer agent. Write concurrency tests (@SpringBootTest RANDOM_PORT,
ExecutorService, CountDownLatch):
- 10 parallel creates, same Idempotency-Key and body -> 1 request, all responses same id
- same key, different body -> 422
- 10 parallel creates, no key, same application -> 1 success, 9 x 409
- 2 parallel decisions with the same If-Match -> one 200, one 409 or 412, exactly 2 events
```

**2. Code**
```
Implement idempotency as in docs/design.md: hash the canonical JSON body (SHA-256),
replay on same key + same hash, 422 on same key + different hash, insert the record in the
same transaction as the create, and on a primary-key clash re-read and replay.
Add header Idempotent-Replayed: true on replays. Add a scheduled purge (TTL from config,
default 24h). Map optimistic lock failures to 409. Explain the transaction design to me in 5 lines.
```

**3. Attack** (important here!) · **4. Review** · **5. Commit** → `feat: idempotency and optimistic locking`

**Done when:** run the concurrency tests 20 times — always green.
`for i in {1..20}; do ./mvnw -q test -Dtest='Concurrency*' || break; done`

---

## Phase 5 — Supersede + mortgage lookup

**1. Tests first**
```
Use the test-writer agent. Write tests for supersede (only PENDING; old becomes SUPERSEDED,
new is PENDING, linked both ways, both events in history; 409 after a decision; 403 for
another RM) and for GET /api/applications/{id}/approved-discount (only MORTGAGE_PROCESS;
404 before approval; 200 after; returns approvedDiscountBps).
Also INV-01: after approval, no endpoint can change the stored discount.
```

**2. Code**
```
Implement supersede and the approved-discount endpoint from docs/design.md.
In supersede, mark the old request SUPERSEDED and flush before inserting the new one,
so the unique index does not fail. Add both to http/requests.http.
```

**3. Attack · 4. Review · 5. Commit** → `feat: supersede and mortgage lookup`

**Done when:** every bullet in the assignment PDF is covered by at least one test.

---

## Phase 6 — Documentation

```
Use the docs-writer agent. Write:
- README.md: problem, quick start (3 commands), demo walkthrough, assumptions, the
  stakeholder decision (supersede instead of edit), scope choices, a table
  "requirement -> test", "before production" list, time spent (done), AI usage link.
- docs/adr/ADR-001 to ADR-006 from docs/design.md "Key decisions".
- docs/diagrams/state-machine.md (Mermaid).
- docs/runbook.md: config, health endpoint, error codes, reset local DB.
- scripts/demo.sh (curl + jq) that runs the full scenario and prints each HTTP status.
Run every command you write and report the results.
```
*You:* read the README carefully. It is your voice. Fill in the time.
**Commit** → `docs: README, ADRs, runbook, demo script`

---

## Phase 7 — Simple UI

```
Build a single page UI in src/main/resources/static (index.html + app.js, no build step,
plain fetch, one classless CSS file from a CDN). Features: user switcher (sets X-User-Id),
request list with status filter, detail panel with history, create form (Idempotency-Key
with crypto.randomUUID() per form), Approve/Decline/Withdraw/Supersede buttons by role and
status, sending If-Match. Show ProblemDetail errors as text; on 412 show
"This request changed — reload". Show user text with textContent, never innerHTML.
```
Then: **Review** → **Commit** → `feat: simple UI`
*You:* click through the full scenario, including a 412 with two browser tabs.

---

## Phase 8 — AI review agent in CI (bonus)

```
Build a CI review agent:
- agents/review-prompt.md (reuse the rules from .claude/agents/reviewer.md)
- agents/finding.schema.json for the finding format
- agents/review.py: takes a git diff, calls the Anthropic API (model id from config,
  key from env ANTHROPIC_API_KEY), validates the JSON, drops findings not in the diff,
  prints markdown
- .github/workflows/ai-review.yml on pull_request, permissions contents: read and
  pull-requests: write, posts one comment "AI review (advisory)"
- agents/eval/: one patch per invariant INV-01..06 that breaks it, plus 2 clean refactors,
  expected.json, and run.sh that prints a table: case, expected, found, tests caught it?
Check the current Anthropic API docs for the request format — do not guess.
```
*You:* run the eval. Commit the real results table — even if the agent misses some.
**Commit** → `feat: AI review agent with eval`

---

## Phase 9 — Final check and submit

- [ ] Fresh clone in a new folder → `./mvnw verify` → `./mvnw spring-boot:run` → click through the UI
- [ ] No secrets in the repo (search for "key", "token", "sk-")
- [ ] Time spent written in README
- [ ] `docs/ai-usage.md` complete — include one case where an agent was wrong and how you saw it
- [ ] Tag: `git tag v1.0 && git push --tags`
- [ ] Practise a 10-minute demo: decision (2 min) → live demo (4) → tests (2) → AI agents (2)

---

## Phase 10 — Cleanup: simpler code, same behaviour

Work on the branch `cleanup`. Goal: less code that is easier to read and maintain,
with **exactly the same behaviour**. The existing tests are the safety net.

**0. Measure before** (put the numbers in the final report)
- lines of code: `src/main`, `src/test`, `src/main/resources/static`, `agents/` (Python)
- test count from `./mvnw verify` and from the Python tests
- the list of test names referenced in `docs/invariants.yaml`, `docs/requirements.md` and the README

**1. Production code first — do not touch any test in this step**
```
Simplify src/main without changing behaviour:
- remove dead code: unused classes, methods, fields, parameters, constants, config
- merge tiny classes or wrappers that add no meaning (only when it makes the code easier
  to follow, not just shorter); remove needless indirection and duplicated logic
- shorten long methods; clear names; short comments only where the "why" is not obvious
Behaviour must stay identical: HTTP status codes, error codes, headers (ETag, Location,
Idempotent-Replayed), JSON fields, SQL schema, check order, transactions.
Do not edit existing Flyway migrations. Do not add dependencies.
Run ./mvnw verify after each larger change. All tests must stay green WITHOUT changing them.
```
Commit → `refactor: simplify production code`

**2. Tests — merge, do not lose coverage**
```
Use the test-writer agent:
- merge each *AttackIT / *AttackTest into the main test class of the same topic
- remove a test only when another test checks the same behaviour; list every removed
  test with the test that still covers it
- share setup helpers instead of copying them; keep test names that say the rule (INVxx_...)
- every invariant INV-01..06 and every requirement R1..R7 keeps at least one test
- concurrency tests stay; run them 5 times in a row
Update test names in docs/invariants.yaml (names only), docs/requirements.md and the README table.
```
Commit → `test: merge overlapping tests`

**3. Python and UI**
```
Simplify agents/review.py, agents/ask.py and src/main/resources/static/app.js without
changing behaviour. Merge agents/tests/test_review_attack.py into test_review.py the same
way as step 2. All Python and UI tests stay green.
```
Commit → `refactor: simplify review agent, project guide and UI`

**4. Review and docs**
- Use the **reviewer** agent on the whole branch diff (`git diff main...cleanup`). Check:
  behaviour unchanged, no rule lost a test, nothing removed that the docs still mention.
- Update class names in `docs/diagrams/architecture.md`, `docs/how-to-change.md`,
  `docs/design.md` if they changed. Add one line to `docs/ai-usage.md`.
- Commit → `docs: update after cleanup`

**5. Pull request** — push the branch and open a PR to `main` with a before/after table
(lines and test counts), the list of removed tests and their replacements, and CI green.

**Stop and ask me** if a simplification would change behaviour, an API, the schema, or a business rule.

**Done when:** CI is green on the PR, every INV and R row still has a test, and I have
done one manual click-through of the UI on the branch.

---

## Phase 11 — Deeper compression (same branch `cleanup`)

Goal: about **−10 to −15 % production code** and **−30 to −40 % test code**, with the
**same behaviour** and **the same rule coverage**. Same rules as Phase 10: no change to
HTTP status, error codes, headers, JSON fields, schema or check order; no new dependency;
stop and ask before any behaviour change.

**0. Measure before** (lines and test counts, as in Phase 10).

**1. Production code — tests unchanged in this step**
```
- Replace the small "status + code" exceptions in application and api
  (NotFoundException, ConflictException, VersionMismatchException,
  IdempotencyInProgressException, IdempotencyKeyReusedException,
  IfMatchRequiredException, MalformedRequestException, UnauthenticatedException)
  with ONE class, e.g. ApiProblem(HttpStatus status, String code, String message),
  plus static factory methods with clear names. Keep the domain exceptions as they are.
  Shrink ApiExceptionHandler to match.
- Merge record classes in application that only copy each other (commands, views,
  results). Keep one clear type per concept.
- Remove getters, setters and constructors in the JPA entities that nothing uses.
  Use a record for IdempotencyRecordId only if Hibernate in this Spring Boot version
  supports it — check, do not guess.
- Keep IdempotencyTransactions separate if it exists so @Transactional works through a
  Spring proxy; say so in a one-line comment.
Run ./mvnw verify after each change. All existing tests must stay green WITHOUT edits.
```
Commit → `refactor: one ApiProblem type, fewer records, slimmer entities`

**2. Tests — compress, keep every case**
```
Use the test-writer agent:
- one shared test helper for HTTP calls in the IT classes (create, get, decide,
  withdraw, supersede, with headers), instead of a copy in each class
- turn groups of near-identical error tests into @ParameterizedTest tables
  (one row per case: caller, call, expected status, expected code)
- PricingExceptionControllerTest: keep only cases an IT cannot check (for example
  "decidedBy in the body is ignored", malformed JSON, strict number parsing).
  Remove a case only when an IT asserts the same status AND code; list each one.
- keep the INVxx_ names that docs/invariants.yaml, docs/requirements.md and the README
  quote, or update those docs (names only)
- every INV-01..06 and R1..R7 keeps at least one test; concurrency tests run 5 times
Write a table: removed or merged test → where it is covered now.
```
Commit → `test: shared helpers and table-driven tests`

**3. Review** — the **reviewer** agent on `git diff main...cleanup`: behaviour unchanged,
no rule lost coverage, docs still match class names (`docs/diagrams/architecture.md`,
`docs/how-to-change.md`, `docs/design.md`). Fix, add one line to `docs/ai-usage.md`.
Commit → `docs: update after compression`

**4. Update PR #1** — push, and add the new before/after table and the test mapping
table to the PR description. Do not merge.

**Done when:** CI is green on the PR, the targets are reached (or the report explains
why a part could not shrink safely), and I have done one manual UI click-through.

---

## Phase 12 — Switch to classic Spring MVC (branch `mvc`)

Decision: the owner builds Spring MVC apps day to day and must defend this code in an
interview, so the code moves to the classic MVC structure he knows. Record it as
**ADR-007** (context, decision, consequences; ADR-006 and older stay as history).

**Target packages** (`se.ataulnasar.pricingexception`):
`controller` (@RestController) · `service` (@Service, all business rules, @Transactional,
idempotency) · `repository` (Spring Data JPA) · `model` (@Entity classes and enums) ·
`dto` (request/response records) · `exception` (ApiProblem + error handler) · `config`.
The `domain` package, the entity↔domain mapper and `reconstitute()` are removed.

**Must not change:** URLs, HTTP status codes, error codes, headers (ETag, Location,
Idempotent-Replayed, If-Match rules), JSON fields, Flyway schema (no edits to existing
migrations), the check order (status → role → self-review → limit → comment), one event
per status change in the same transaction, idempotency and optimistic locking. No new
dependency. Stop and ask before any behaviour change.

**0. Measure before** — lines and test counts; list the API/IT test classes. These are the
safety net and are NOT edited in step 1.

**1. Restructure the production code**
```
Move to the target packages. Put every business rule from domain/PricingExceptionRequest
into PricingExceptionService (or small private helper methods in it), keeping the same
check order and the same error codes and messages. Work directly on the JPA entities.
Value checks (discount 1..max, reason, comment length) keep their codes
(INVALID_DISCOUNT, INVALID_REASON, INVALID_COMMENT) and HTTP status.
Delete the domain package, the mapper and reconstitute(). Keep the injected Clock.
The api-level tests (*IT, controller tests, UI tests) must pass WITHOUT edits.
Domain unit tests will not compile: move them to step 2, do not delete them yet.
```
Commit → `refactor: move to classic Spring MVC structure`

**2. Tests**
```
Use the test-writer agent: turn the old domain tests (status x command matrix, INV-01,
INV-03, INV-05, value checks) into service tests (plain JUnit + Mockito for the
repositories, fixed Clock) or @DataJpaTest where the database matters. Keep EVERY case
and keep the INVxx_ test names. Remove tests only for code that no longer exists
(mapper, reconstitute) and list them. Update ArchUnit rules for the new packages:
controllers never use repositories, no Instant.now(), no field injection.
Run the concurrency tests 5 times.
```
Commit → `test: service-level rule tests for MVC structure`

**3. Docs, agents and eval**
```
Update: ADR-007 (new), docs/design.md, docs/diagrams/architecture.md, docs/how-to-change.md,
CLAUDE.md (package rules), docs/invariants.yaml and docs/requirements.md (test names only),
README, agents/project-guide-prompt.md if it names packages, and regenerate the
agents/eval patches so each still breaks its invariant (CI checks they apply).
Add one line to docs/ai-usage.md.
```
Commit → `docs: update for MVC structure`

**4. Review and PR** — reviewer agent on `git diff main...mvc` (behaviour unchanged, every
INV-01..06 and R1..R7 still tested, no doc names a removed class). Push, open a PR with a
before/after table and the old→new test mapping. Do not merge.

**Done when:** CI green on the PR, all ITs passed unedited, and I have done one manual
UI click-through on the branch.
