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
