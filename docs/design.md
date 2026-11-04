# Design — Pricing Exception Service

Agents: read this file, `CLAUDE.md` and `docs/invariants.yaml` before any work.

## The problem
A relationship manager (RM) asks for a discount (basis points, bps) on the standard
interest rate of a mortgage application. A reviewer approves or declines it.
The mortgage application process reads the approved discount.

## Key decisions
1. **Requests never change after submit.** To change the discount, the RM *supersedes*
   a PENDING request: a new request is created (with `supersedesId`) and the old one
   becomes SUPERSEDED, in one transaction. After a decision, the RM makes a new request.
   An approval stores `approvedDiscountBps` (the value the reviewer saw).
2. **Idempotency:** `POST` create and supersede accept an `Idempotency-Key` header.
   Same key + same body → replay the first response. Same key + different body → 422.
3. **History:** every status change writes one row in `request_event`, same transaction.
4. **Optimistic locking:** decide / withdraw / supersede need `If-Match: "<version>"`.
   Missing → 428. Old version → 412. Lost race inside the transaction → 409.
5. **Mortgage process** reads a small, separate endpoint for the approved discount.
6. **Runs locally:** H2 by default, Postgres with profile `postgres`. Flyway owns the schema.

## Users and roles (seed data, identity from header `X-User-Id`)
| id | role | approval limit (bps) |
|---|---|---|
| rm-anna | RM | – |
| rm-erik | RM | – |
| rev-sara | REVIEWER | 50 |
| rev-johan | REVIEWER | 150 |
| mortgage-system | MORTGAGE_PROCESS | – |

Missing or unknown `X-User-Id` → 401.

## Mortgage applications (seed data)
Fields: `id` (e.g. `MA-1001`), `customerRef`, `standardRateBps`, `status` (OPEN / CLOSED), `ownerRmId`.
Seed 4 applications, one of them CLOSED. Also seed one APPROVED, one PENDING and one
DECLINED request, each with its events.

## Request statuses
- PENDING → APPROVED (reviewer), DECLINED (reviewer), WITHDRAWN (owner RM), SUPERSEDED (owner RM)
- All other statuses are final.
- Active = PENDING or APPROVED. Max one active request per application.

## Validation
- `discountBps`: whole number, 1 to `pricing.max-discount-bps` (default 100).
- `reason`: not blank, max 500 characters.
- `comment` on decisions: optional, max 500 characters.
- "Characters" are UTF-16 units (`String.length()`, same as `@Size`), never more than the DB column.

## HTTP API
| Method and path | Who | What | Main responses |
|---|---|---|---|
| `POST /api/pricing-exceptions` | RM | Create. Body: `applicationId`, `discountBps`, `reason` | 201 + `Location` + `ETag`; 404 application; 409 `ACTIVE_REQUEST_EXISTS` or `APPLICATION_NOT_OPEN`; 422 validation / key reuse |
| `GET /api/pricing-exceptions/{id}` | anyone | Request + status + decision + full history | 200 + `ETag`; 404 |
| `GET /api/pricing-exceptions?status=&applicationId=` | anyone | List / filter | 200 |
| `POST /api/pricing-exceptions/{id}/decision` | REVIEWER | Body: `outcome` (APPROVED / DECLINED), `comment` | 200; 403 self-review / over limit / wrong role; 409 not pending; 412; 428 |
| `POST /api/pricing-exceptions/{id}/withdrawal` | owner RM | Withdraw a pending request | 200; 403; 409; 412; 428 |
| `POST /api/pricing-exceptions/{id}/supersessions` | owner RM | Body: `discountBps`, `reason`. Replaces a pending request | 201 new request; 403; 409; 412; 428 |
| `GET /api/applications/{id}/approved-discount` | MORTGAGE_PROCESS | `{applicationId, discountBps, requestId, decidedBy, decidedAt}` | 200; 403; 404 `NO_APPROVED_DISCOUNT` |
| `GET /api/applications`, `GET /api/users` | anyone | Seed data for the UI | 200 |

Errors use ProblemDetail (RFC 9457) with an extra `code` field.
`decidedBy` / `decidedAt` come from the caller and the clock, never from the body.

## Tables (Flyway, portable SQL for H2 and Postgres)
- `app_user` (id, name, role, approval_limit_bps)
- `mortgage_application` (id, customer_ref, standard_rate_bps, status, owner_rm_id)
- `pricing_exception_request` (id UUID, application_id, requested_by, discount_bps, reason,
  status, supersedes_id, decided_by, decided_at, decision_comment, approved_discount_bps,
  created_at, version, **active_application_id**)
  - `active_application_id` = application id while PENDING or APPROVED, else NULL.
    Set by the code on every status change. Plain UNIQUE index on it (gives INV-04).
- `request_event` (id, request_id, type, actor_id, occurred_at, from_status, to_status, comment) — insert only
- `idempotency_record` (caller_id, idem_key, request_hash, response_status, response_body, created_at), PK (caller_id, idem_key)

## Packages (`se.ataulnasar.pricingexception`)
`domain` (plain Java) · `application` (services) · `api` (controllers, DTOs) · `persistence` (JPA)

## Domain error codes and events
- **Error codes** (stable `code` on domain exceptions, mapped to ProblemDetail in `api`):
  - `ForbiddenActionException`: `WRONG_ROLE`, `SELF_REVIEW`, `OVER_APPROVAL_LIMIT`, `NOT_OWNER`
  - `InvalidTransitionException`: `INVALID_TRANSITION`
  - `ValidationException`: `INVALID_DISCOUNT`, `INVALID_REASON`, `INVALID_COMMENT`
- **Event types** (`request_event.type`, one per status change): `SUBMITTED`, `APPROVED`, `DECLINED`, `WITHDRAWN`, `SUPERSEDED`
- **Check order for decisions** (approve / decline): status, then role, then self-review, then approval limit.
- **INV-06** (application must be OPEN) is checked in the application service, not in the domain.

## API error codes
Every error is an RFC 9457 ProblemDetail (`application/problem+json`) with `status`, `title`,
plain-text `detail` and the extra property `code`:

| HTTP | `code` | When |
|---|---|---|
| 400 | `MALFORMED_REQUEST` | Unreadable JSON, non-UUID id, unparsable `If-Match` |
| 401 | `UNAUTHENTICATED` | Missing or unknown `X-User-Id` |
| 403 | `WRONG_ROLE`, `SELF_REVIEW`, `OVER_APPROVAL_LIMIT`, `NOT_OWNER` | Domain `ForbiddenActionException` (INV-03, owner rules) |
| 404 | `REQUEST_NOT_FOUND`, `APPLICATION_NOT_FOUND` | Unknown request or application id |
| 409 | `INVALID_TRANSITION` | Request is not PENDING (INV-05) |
| 409 | `ACTIVE_REQUEST_EXISTS` | Application already has a PENDING or APPROVED request (INV-04) |
| 409 | `APPLICATION_NOT_OPEN` | Application is not OPEN on create or approve (INV-06) |
| 409 | `CONCURRENT_MODIFICATION` | Optimistic lock lost inside the transaction |
| 409 | `DATA_CONFLICT` | Any other database constraint violation (details only in the server log) |
| 409 | `IDEMPOTENCY_IN_PROGRESS` | The first request with this `Idempotency-Key` is still running |
| 412 | `VERSION_MISMATCH` | `If-Match` is not the current version (INV-05) |
| 422 | `VALIDATION_FAILED` | Bean Validation on the body; `errors[]` lists `{field, message}` |
| 422 | `INVALID_DISCOUNT`, `INVALID_REASON`, `INVALID_COMMENT` | Domain `ValidationException` |
| 422 | `IDEMPOTENCY_KEY_REUSED` | Same caller and `Idempotency-Key`, different body |
| 428 | `IF_MATCH_REQUIRED` | Decision, withdrawal or supersede without `If-Match` |
| 500 | `INTERNAL_ERROR` | Unexpected error; no details are exposed |
| any | `HTTP_<status>` | Spring's own errors: unknown route (404), wrong method (405, with `Allow`), unsupported media type (415) |

The `ETag` of a request is its version in double quotes, e.g. `"0"`. `If-Match` must carry exactly
that value; `*`, weak tags, lists, unquoted or zero-padded numbers are 400.

**Idempotency-Key** (optional on create and supersede, 1 to 128 characters, scoped per caller): the first
response (status, `Location`, `ETag`, body) is stored in the same transaction as the request and replayed
for the same key and the same body, with `Idempotent-Replayed: true`. "Same body" means the same
SHA-256 of the canonical form of the parsed body (operation name, then each field length-prefixed:
`create\n7:MA-1003\n2:40\n<len>:<reason>`), so whitespace, field order and unknown fields do not matter
and the hash does not depend on any JSON library. A failed create stores nothing. Records are purged after `pricing.idempotency.ttl` (default 24h).

**Check order of an HTTP request:** identity (401) → request shape: body validation (400/422), then
`If-Match` presence and form (428/400) → the use case: existence (404), version (412), then the domain
rules in their documented order (403/409/422). So an invalid body is reported before a missing
`If-Match`, a missing `If-Match` before an unknown request id, and a stale version before a
non-PENDING status.
