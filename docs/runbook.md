# Runbook

## Configuration

The default profile reads `src/main/resources/application.properties`. The `postgres` profile
adds `application-postgres.properties` on top of it.

### Default profile (`application.properties`)

| Property | Value | Meaning |
|---|---|---|
| `spring.application.name` | `pricing-exception-service` | The application name. |
| `spring.datasource.url` | `jdbc:h2:mem:pricing;DB_CLOSE_DELAY=-1` | In-memory H2 database `pricing`. `DB_CLOSE_DELAY=-1` keeps it until the app stops. |
| `spring.datasource.username` | `sa` | H2 user. |
| `spring.datasource.password` | (empty) | H2 password. |
| `spring.flyway.locations` | `classpath:db/migration,classpath:db/seed` | The schema (V1) and the seed data (V1000). |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Hibernate checks the entities against the schema. It never changes the schema. |
| `spring.jpa.open-in-view` | `false` | Services load what they need inside their transaction. |
| `spring.h2.console.enabled` | `true` | The H2 console is at `/h2-console`. |
| `spring.jackson.deserialization.accept-float-as-int` | `false` | JSON numbers must be whole. `40.0` gets `400 MALFORMED_REQUEST`. Strings such as `"40"` are rejected too (see `AppConfig`). |
| `pricing.max-discount-bps` | `100` | The largest allowed `discountBps`. The smallest is 1. |
| `pricing.idempotency.ttl` | `PT24H` | How long a stored `Idempotency-Key` response can be replayed. |
| `pricing.idempotency.purge-interval` | `PT1H` | How often expired records are removed. The first run is one interval after start. |

### PostgreSQL profile (`application-postgres.properties`)

| Property | Value | Meaning |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/pricing` | Database `pricing` on `localhost`, port 5432. |
| `spring.datasource.username` | `pricing` | A placeholder user. |
| `spring.datasource.password` | `pricing` | A placeholder password. Do not use it outside your machine. |
| `spring.flyway.locations` | `classpath:db/migration` | The schema only. No seed data. |
| `spring.h2.console.enabled` | `false` | The H2 console is off. `/h2-console` returns 404. |

Start the app with the profile:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

The database must already exist, with user `pricing` and password `pricing`. One way to get
it (tested with the `postgres:17` image):

```bash
docker run -d --name pes-pg -e POSTGRES_DB=pricing -e POSTGRES_USER=pricing -e POSTGRES_PASSWORD=pricing -p 5432:5432 postgres:17
```

## Health

Health check: `GET http://localhost:8080/actuator/health`. No `X-User-Id` header is needed.

```bash
curl -s http://localhost:8080/actuator/health
```

The response is `{"groups":["liveness","readiness"],"status":"UP"}`.

- `UP` means the health check passed. The response does not show the parts that were checked.
- Flyway runs on startup, before the web server starts. The log shows the migrations first, then
  `Tomcat started on port 8080`.
- The app does not expose `/actuator/info`. It returns 404.

## Error codes

Every error is an RFC 9457 ProblemDetail with a `code` field. The full table (HTTP status, code,
when it happens) is in [docs/design.md, "API error codes"](design.md#api-error-codes).

The codes, grouped by HTTP status:

- **400:** `MALFORMED_REQUEST`
- **401:** `UNAUTHENTICATED`
- **403:** `WRONG_ROLE`, `SELF_REVIEW`, `OVER_APPROVAL_LIMIT`, `NOT_OWNER`
- **404:** `REQUEST_NOT_FOUND`, `APPLICATION_NOT_FOUND`, `NO_APPROVED_DISCOUNT`
- **409:** `INVALID_TRANSITION`, `ACTIVE_REQUEST_EXISTS`, `APPLICATION_NOT_OPEN`, `CONCURRENT_MODIFICATION`, `IDEMPOTENCY_IN_PROGRESS`, `DATA_CONFLICT`
- **412:** `VERSION_MISMATCH`
- **422:** `VALIDATION_FAILED`, `INVALID_DISCOUNT`, `INVALID_REASON`, `INVALID_COMMENT`, `IDEMPOTENCY_KEY_REUSED`
- **428:** `IF_MATCH_REQUIRED`
- **500:** `INTERNAL_ERROR`
- **Any status:** `HTTP_<status>` for Spring's own errors, for example an unknown route gives `HTTP_404`.

## Reset the local database

- **H2 (default profile):** the data is in memory. Stop the app (Ctrl+C) and start it again. The
  database is empty and the seed data loads again.
- **PostgreSQL (`postgres` profile):** Flyway's clean command is not set up in this project. Drop
  and recreate the `public` schema, then start the app again. The example uses the container from
  the start command above:

  ```bash
  docker exec pes-pg psql -U pricing -d pricing -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
  ```

  Flyway creates the schema again on the next start. There is no seed data in this profile.

## Logs

- The app writes its logs to the console (standard output). No log file is set up.
- `ApiExceptionHandler` logs an unexpected error at ERROR level with the stack trace. The client
  gets `500 INTERNAL_ERROR` with no details.
- A `DATA_CONFLICT` (409 for a database constraint error other than the active-request index) is
  logged at WARN with the stack trace. The client only gets "the change conflicts with stored data".
- `ACTIVE_REQUEST_EXISTS` gets a 409 and no log line.
- `IdempotencyPurge` logs at INFO when it removes records, for example
  `purged 3 idempotency record(s) created before ...`.

## Running the PostgreSQL tests locally

`PostgresMigrationTest` runs the Flyway migrations and the seed data against a real
PostgreSQL 17 in Docker (Testcontainers). It is annotated
`@Testcontainers(disabledWithoutDocker = true)`, so without a reachable Docker daemon it is
**skipped**, and `./mvnw verify` reports both Postgres classes (`PostgresMigrationTest`, `PostgresApiIT`) as skipped. In GitHub CI the workflow fails if
that happens, so a skip can never pass unnoticed there.

### Docker Desktop
Nothing to do. Testcontainers finds `/var/run/docker.sock`.

### Colima (macOS)
Testcontainers must talk to Colima's socket, and its helper container (Ryuk) must mount the
socket path *inside* the Colima VM, which is `/var/run/docker.sock`. Set both and run the tests:

```bash
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw verify
```

`docker context use colima` alone is not enough, because Testcontainers reads `DOCKER_HOST`,
not the Docker CLI context. If the container starts but the JDBC connection to `localhost`
times out, also set the VM address as the host:

```bash
export TESTCONTAINERS_HOST_OVERRIDE="$(colima ls -j | jq -r '.address')"
```

Check it worked: the report `target/surefire-reports/TEST-se.ataulnasar.pricingexception.persistence.PostgresMigrationTest.xml`
must contain `skipped="0"`.

Only the default `colima` profile is covered above. For another profile, replace `default` in
the socket path. The variables are read from the environment only; nothing in the repository
refers to a machine-specific path.

## Known issues

- Enum CHECK constraints were removed because of H2 2.4.240 bug
  https://github.com/h2database/h2database/issues/4342 (duplicate of #4291, fixed upstream,
  waiting for a release). Add them back in a new migration when Spring Boot ships a fixed H2
  version. The domain still enforces valid statuses.
