# Requirements from the assignment

Our own short summary of the Blue Harvest assignment (not a copy of the PDF).
The `Proved by` column lists the tests or documents that show each requirement is met.

## Functional requirements

| ID | Requirement | Where in the design | Proved by |
|---|---|---|---|
| R1 | An RM can create a pricing exception request for an existing mortgage application, with an application id, a discount in basis points and a reason. | `POST /api/pricing-exceptions` | WorkflowIT.INV02_each_transition_writes_one_event (create → 201, Location, ETag); PricingExceptionControllerTest.api_create_returns_201_with_location_and_etag; ApiAttackIT (validation: discount 1..100, reason ≤ 500); WorkflowIT.INV06_cannot_create_for_closed_application |
| R2 | A reviewer can approve or decline a request. The outcome shows who decided and when. | `POST /{id}/decision`, `decidedBy`, `decidedAt` | WorkflowIT.INV02_each_transition_writes_one_event (approve: decidedBy = reviewer, decidedAt set); ApiAttackIT.INV02_decline_gives_etag_1_two_events_microsecond_decidedAt_and_final_state_rejects_everything; WorkflowIT.INV03_over_approval_limit_is_403_end_to_end; PricingExceptionControllerTest.INV03_decidedBy_and_decidedAt_in_body_are_ignored_caller_comes_from_header |
| R3 | An approved request is available to the mortgage application process, which needs the approved discount. | `GET /api/applications/{id}/approved-discount` | SupersedeIT.approved_discount_lifecycle_404_before_200_after; SupersedeIT.approved_discount_other_roles_are_403_WRONG_ROLE; SupersedeAttackIT.INV01_chain_approving_the_last_replacement_gives_its_discount_to_the_mortgage_process; ApplicationControllerTest.api_approved_discount_maps_all_five_fields_and_passes_caller_and_id |
| R4 | Internal users can see the history of a request: its current status and earlier decisions. | `GET /{id}` with `history` | WorkflowIT.INV02_each_transition_writes_one_event (GET shows SUBMITTED then APPROVED); SupersedeIT.INV01_supersede_links_old_and_new_both_ways_and_writes_both_events; SupersedeAttackIT.INV02_a_chain_writes_exactly_one_event_per_status_change; WithdrawalIT.INV02_get_after_approval_shows_full_history_in_order |
| R5 | No duplicate requests when an upstream system retries a call after a timeout. | `Idempotency-Key`, INV-04 | ConcurrencyIT.idempotent_parallel_creates_with_same_key_create_one_request_and_all_return_same_id; ConcurrencyIT.idempotent_replay_returns_same_status_location_etag_and_body; ConcurrencyIT.INV04_parallel_creates_without_key_create_one_request; SupersedeConcurrencyIT.idempotent_parallel_supersedes_with_same_key_create_one_request; SupersedeIT.idempotent_supersede_replays_first_response; PostgresApiIT.idempotent_parallel_creates_with_same_key_on_postgres_create_one_request_and_all_return_same_id (runs in CI; skipped locally without Docker) |
| R6 | Easy to run locally. Mortgage applications and users may be modelled as we choose. | H2 default, seed data, `X-User-Id` | PricingExceptionServiceApplicationTests.contextLoads (H2 + Flyway + seed on start); SeedDataTest (4 applications, 5 users, 3 requests); PostgresMigrationTest (same schema on Postgres); docs/runbook.md; http/requests.http |

## The stakeholder conflict

| ID | Requirement | Our decision | Proved by |
|---|---|---|---|
| R7 | One stakeholder wants RMs to change the discount after submit. Another wants an approval to always apply to the exact request that was reviewed. Make and explain a decision. | Requests never change (INV-01). The RM supersedes a PENDING request instead. Explained in ADR-001 and the README. | PricingExceptionRequestTest.INV01_approval_records_reviewed_discount; PricingExceptionRequestTest.INV01_request_has_no_way_to_change_the_discount; SupersedeIT.INV01_only_pending_request_can_be_superseded; SupersedeIT.INV01_no_endpoint_changes_discount_after_approval; docs/design.md "Key decisions" 1 (ADR-001 and README: Phase 6) |

## Deliverables

| ID | Deliverable | Where | Done |
|---|---|---|---|
| D1 | Working, runnable solution; HTTP API expected | the app | ✅ `./mvnw spring-boot:run`; HTTP API in docs/design.md; CI green on every push |
| D2 | Seed data or setup steps to try a realistic workflow | seed migration, `http/requests.http`, `scripts/demo.sh` | ✅ seed: `src/main/resources/db/seed/V1000__local_seed_data.sql`; walkthrough: `http/requests.http`; `scripts/demo.sh`: Phase 6 |
| D3 | Source in Git, with instructions to run, test and explore | GitHub repo, README | ✅ github.com/ataulnasar/pricing-exception-service; run/test steps in `docs/runbook.md`; full README: Phase 6 |
| D4 | Short README: the problem, assumptions, scope choices | `README.md` | Phase 6 (docs-writer) |
| D5 | Evidence that important behaviour is tested | test suite, README "requirement → test" table | ✅ 476 tests in `./mvnw verify` incl. Postgres; this file; README table: Phase 6 |
| D6 | Decisions, diagrams, notes and operational guidance for the next engineer | `docs/adr/`, `docs/diagrams/`, `docs/runbook.md` | partly: `docs/design.md`, `docs/invariants.yaml`, `docs/runbook.md`; ADRs and diagrams: Phase 6 |

## Constraints

| ID | Constraint | How we meet it | Done |
|---|---|---|---|
| C1 | Any language and tools; Java or C# preferred | Java 21, Spring Boot 4 | ✅ |
| C2 | Easy to run locally; no private infrastructure or paid services | H2 by default; Postgres optional via Docker | ✅ H2 in memory by default; Postgres only via Testcontainers / `postgres` profile (`docs/runbook.md`) |
| C3 | Say what must change before production where we took a lighter path | README "Before production" section | Phase 6 (README "Before production") |
| C4 | Use agentic development where it adds value; be ready to explain how agents helped and how output was checked | `.claude/agents/`, `docs/agent-workflow.md`, `docs/ai-usage.md` | ✅ `.claude/agents/`, `.claude/commands/run-phase.md`, `docs/agent-workflow.md`, `docs/ai-usage.md` (one line per phase) |

## Bonus

| ID | Bonus | Where | Done |
|---|---|---|---|
| B1 | Show how AI agents can improve the solution or the SDLC: the problem, how output is controlled and checked, remaining risks | Phase 8: CI review agent + eval | built; live eval not run (needs API key): `agents/review.py`, `agents/review-prompt.md`, `agents/finding.schema.json`, `.github/workflows/ai-review.yml`, `agents/eval/` (`results.md` shows the Java-test side), `agents/tests/`, README "AI review agent in CI" |
| B2 | A simple UI to create, review and inspect requests | Phase 7 | ✅ `src/main/resources/static/` (index.html, app.js); tests StaticUiIT, UiSourceRulesTest, UiFirstLoadIT |

## Submission

| ID | Item | Done |
|---|---|---|
| S1 | Share the repository | TODO |
| S2 | Include the time spent | ✅ README "Time spent" (about 6 hours) |
| S3 | Be ready to walk through choices, run a scenario, and say what you would do next | TODO |
