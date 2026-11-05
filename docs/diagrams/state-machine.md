# Request states

## Status diagram

```mermaid
stateDiagram-v2
    [*] --> PENDING: submit by RM
    PENDING --> APPROVED: approve by REVIEWER (within limit, application OPEN)
    PENDING --> DECLINED: decline by REVIEWER
    PENDING --> WITHDRAWN: withdraw by owner RM
    PENDING --> SUPERSEDED: supersede by owner RM (creates a new PENDING request)
    APPROVED --> [*]
    DECLINED --> [*]
    WITHDRAWN --> [*]
    SUPERSEDED --> [*]
```

Rules (see `docs/invariants.yaml`):
- APPROVED, DECLINED, WITHDRAWN and SUPERSEDED are final (INV-05).
- Only the owner RM can withdraw or supersede. The owner is the RM who submitted the request.
- A reviewer cannot decide their own request (INV-03).
- Approval needs the application to be OPEN (INV-06).

## Active requests

A request is **active** while it is PENDING or APPROVED. An application may have at most one
active request (INV-04). The database enforces this with a unique index on
`active_application_id`. A DECLINED, WITHDRAWN or SUPERSEDED request is not active, so it frees
the application's slot.

## One event per transition

Every transition writes exactly one `request_event` row in the same transaction (INV-02).

| Transition | Event type | Who |
|---|---|---|
| (start) to PENDING | `SUBMITTED` | RM |
| PENDING to APPROVED | `APPROVED` | REVIEWER |
| PENDING to DECLINED | `DECLINED` | REVIEWER |
| PENDING to WITHDRAWN | `WITHDRAWN` | owner RM |
| PENDING to SUPERSEDED | `SUPERSEDED` | owner RM |

## Supersede call

```mermaid
sequenceDiagram
    participant C as Client (owner RM)
    participant API as API (controller)
    participant S as PricingExceptionService
    participant DB as Database
    C->>API: POST /api/pricing-exceptions/{id}/supersessions (If-Match, body, optional Idempotency-Key)
    API->>S: supersede(caller, id, version, discountBps, reason)
    S->>S: validate the new discount and reason (422 before anything changes)
    S->>DB: load the old request (404 if unknown)
    S->>S: check If-Match version (412 if stale), then PENDING (409), then owner (403), then application OPEN (409)
    S->>DB: old request becomes SUPERSEDED (event written), flush
    S->>DB: insert the new PENDING request with supersedesId (event written)
    S->>DB: commit
    S-->>API: new request
    API-->>C: 201 Created, Location and ETag, body
```

The flush before the insert frees the active slot, so the unique index accepts the new row. Both
changes commit together or not at all.
