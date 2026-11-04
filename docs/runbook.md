# Runbook

## Running the PostgreSQL tests locally

`PostgresMigrationTest` runs the Flyway migrations and the seed data against a real
PostgreSQL 17 in Docker (Testcontainers). It is annotated
`@Testcontainers(disabledWithoutDocker = true)`, so without a reachable Docker daemon it is
**skipped**, and `./mvnw verify` prints `Skipped: 2` for it. In GitHub CI the workflow fails if
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
