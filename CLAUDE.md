# CLAUDE.md — rules for AI agents in this repo

## What this project is
A small internal service for **mortgage pricing exception requests**.
A relationship manager (RM) asks for a discount (in basis points) on the standard
interest rate of a mortgage application. A reviewer approves or declines it.
The mortgage application process reads the approved discount.

Design and decisions: `docs/adr/`. Business rules: `docs/invariants.yaml`.
Read both before you change any code.

## Stack
- Java 21, Spring Boot 4.1.2, Maven (use `./mvnw`, never a global `mvn`)
- Spring Web, Validation, Spring Data JPA, Flyway, H2 (default), PostgreSQL (profile `postgres`)
- Tests: JUnit 5, AssertJ, Spring Boot Test, Testcontainers, ArchUnit

## Commands
- Build and run all tests: `./mvnw verify`
- Run the app (H2 + seed data): `./mvnw spring-boot:run`
- A change is only done when `./mvnw verify` is green.

## Package structure (`se.ataulnasar.pricingexception`, classic Spring MVC, ADR-007)
- `controller` — `@RestController` classes and the `If-Match` parser.
- `service` — `@Service` classes: **every business rule**, one use case per method in one transaction, idempotency.
- `repository` — Spring Data JPA repositories.
- `model` — JPA entities, their enums (`RequestStatus`, `EventType`, `Role`) and `Actor`.
- `dto` — request and response records.
- `exception` — `ApiProblem`, the business-rule exceptions, the ProblemDetail handler.
- `config` — Clock and JSON beans, `pricing.*` properties, the `X-User-Id` argument resolver.
- Controllers call services. Controllers never call repositories. Every status change goes
  through `PricingExceptionService.transition` (one event per change).

## Business rules (short version — full list in `docs/invariants.yaml`)
- INV-01: A submitted request never changes. To change the discount, the RM
  **supersedes** it (new request, old one becomes SUPERSEDED). Only a PENDING
  request can be superseded. An approval stores the discount it saw.
- INV-02: Every status change writes exactly one history event, in the same transaction.
- INV-03: The requester can never decide their own request. A reviewer can only
  approve up to their approval limit.
- INV-04: Max one active (PENDING or APPROVED) request per application.
- INV-05: Only PENDING requests can change status. Decisions need a matching version (`If-Match`).
- INV-06: The application must be OPEN to create or approve a request.

If a task would break one of these rules, **stop and ask**. Do not work around it.

## How to work
1. Tests come first. The test-writer agent writes them; the human approves them.
2. **Never change, delete, skip or weaken a test to make it pass.** If you think a
   test is wrong, stop and explain why.
3. **Do not add new dependencies** without asking first.
4. Keep changes small: one step per commit.
5. Do not change files in `docs/adr/` or `docs/invariants.yaml` unless asked.
6. Do not change existing Flyway migrations (`V1__...`). Add a new one instead.
7. When you are not sure, ask. Do not guess library versions or APIs — check them.

## Code style
- Constructor injection only. No field injection.
- No Lombok. Use Java records for DTOs and value objects.
- Time comes from an injected `java.time.Clock`, never `Instant.now()` directly.
- Money and rates are whole numbers in basis points (`int`), never `double`.
- Errors: domain exceptions with a stable error `code`, mapped to ProblemDetail in `api`.
- `decidedBy` and `decidedAt` come from the caller and the clock, **never from the request body**.
- Show user text (like `reason`) as text, never as HTML.

## Tests
- Test names say the rule they prove, e.g. `INV03_requester_cannot_approve_own_request`.
- Rule tests are plain JUnit + Mockito on `PricingExceptionService` (no Spring context).
- Use a fixed `Clock` in tests.

## Commits
- Conventional commits: `feat:`, `fix:`, `test:`, `docs:`, `chore:`, `refactor:`.
- Do not add `Assisted-by` or `Co-authored-by` trailers. AI usage is recorded in `docs/ai-usage.md`.
