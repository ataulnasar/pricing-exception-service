# Architecture

## 1. System context

Who uses the service, and how.

```mermaid
flowchart LR
    rm["Relationship manager (RM)"]
    rev["Reviewer"]
    mp["Mortgage application process<br/>(system client)"]
    ui["Simple UI<br/>(static page, same app)"]
    svc["Pricing exception service<br/>(Spring Boot, HTTP API)"]
    db[("Database<br/>H2 local / PostgreSQL")]

    rm --> ui
    rev --> ui
    ui -- "HTTP + X-User-Id" --> svc
    mp -- "GET approved-discount" --> svc
    svc --> db
```

- Identity comes from the `X-User-Id` header and the seeded users (no real login, see README "Before production").
- The mortgage process only reads one small endpoint: `GET /api/applications/{id}/approved-discount`.

## 2. Layers inside the service

Package `se.ataulnasar.pricingexception`. Arrows show allowed dependencies.

```mermaid
flowchart TB
    subgraph api["api"]
        ctrl["PricingExceptionController<br/>ApplicationController"]
        web["CallerArgumentResolver (X-User-Id)<br/>IfMatch, ApiExceptionHandler (ProblemDetail)"]
    end
    subgraph application["application"]
        svc["PricingExceptionService<br/>(one transaction per command)"]
        idem["IdempotencyService<br/>IdempotencyPurge"]
        ref["ReferenceDataService, UserLookup"]
    end
    subgraph domain["domain (plain Java)"]
        agg["PricingExceptionRequest<br/>(all business rules)"]
        vo["DiscountBps, Reason, Actor,<br/>RequestStatus, RequestEvent"]
    end
    subgraph persistence["persistence"]
        repo["Repositories<br/>(request and idempotency: no delete)"]
        map["PricingExceptionRequestMapper"]
        ent["JPA entities<br/>(request entity has @Version)"]
    end
    db[("Flyway schema<br/>H2 / PostgreSQL")]

    api --> application
    application --> domain
    application --> persistence
    persistence --> domain
    persistence --> db
```

Rules checked by ArchUnit on every build (`DomainArchitectureTest`, `ArchitectureRulesTest`):

- `domain` has no Spring, JPA or Jackson imports, and depends on no other layer.
- `api` never depends on `persistence`.
- No `Instant.now()` (time comes from an injected `Clock`), no field injection, no Lombok.

## 3. What happens on a decision

```mermaid
sequenceDiagram
    actor Reviewer
    participant C as PricingExceptionController
    participant S as PricingExceptionService
    participant D as PricingExceptionRequest (domain)
    participant DB as Database

    Reviewer->>C: POST /{id}/decision<br/>X-User-Id, If-Match: "0", body
    C->>C: resolve caller, validate body, parse If-Match
    C->>S: decide(caller, id, version, outcome, comment)
    S->>DB: load request + events (transaction starts)
    S->>S: version == If-Match? else 412
    S->>D: approve(reviewer, comment, clock)
    D->>D: check status, role, self-review, limit
    D-->>S: new state + one RequestEvent
    S->>S: approve only: application OPEN? else 409 (INV-06)
    S->>DB: save request (@Version) + event, commit
    S-->>C: view with new version
    C-->>Reviewer: 200 + ETag: "1"
```

If two reviewers decide at the same time, only one commit wins. The other gets 409 or 412,
and writes no event (INV-02, INV-05).

## 4. Tables

```mermaid
erDiagram
    app_user ||--o{ pricing_exception_request : "requests / decides"
    mortgage_application ||--o{ pricing_exception_request : "has"
    pricing_exception_request ||--o{ request_event : "history"
    pricing_exception_request |o--o| pricing_exception_request : "supersedes"
    app_user ||--o{ idempotency_record : "caller id (no FK)"
```

- `active_application_id` + a unique index allow max one active request per application (INV-04).
- `request_event` is insert only. It is the audit history.
- `idempotency_record` stores the first response per (caller, key) for safe retries. Its `caller_id`
  is the user id but has no foreign key, so records outlive user changes and are purged by age.

More: [state-machine.md](state-machine.md), [../design.md](../design.md), [../adr/](../adr/).
