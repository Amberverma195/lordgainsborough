# Backend verification

Tests use real PostgreSQL 17, JDBC, Flyway, Hibernate schema validation and real
transactions. Socket.IO application tests start the actual server and client on
temporary loopback ports. Email delivery is mocked; authentication and persistence
are real. Docker and Testcontainers are not used.

Use an exclusively owned local test cluster, bound to `127.0.0.1`, with its test
administrator `postgres` and local test password `postgres`. These credentials are
fixtures, not application credentials. Do not point these tests at a production or
shared database. The harness verifies the PostgreSQL major version and requires
either the previously owned disposable cluster directory or an explicit isolation
attestation before allowing database creation.

The Railway startup wrapper is covered by `RailwayPostgresCaStartScriptTest`. It does
not connect to PostgreSQL. That test runs the script Maven copies to
`target/railway-start.sh`, which is the path Railpack ships, and checks that it
matches `scripts/railway-start.sh` byte for byte.

```powershell
.\mvnw.cmd -Dtest=RailwayPostgresCaStartScriptTest,PostgresTlsPolicyTest test
```

`RailpackRuntimeImageTest` needs Docker. It builds from the published
`ghcr.io/railwayapp/railpack-runtime:debian-trixie` image, copies the packaged
wrapper and application jar into `/app/target`, and runs the `railway.toml` start
command. It checks `sh`, `base64`, and `openssl`, the CA file mode, and that Java
receives the existing arguments. It uses a certificate generated inside the
container. It does not use production credentials or connect to a database. Package
the jar first, then opt in:

```powershell
.\mvnw.cmd -DskipTests package
.\mvnw.cmd -Dtest=RailpackRuntimeImageTest -Drailpack.runtime.image=true surefire:test
```

From `backend`, run:

```powershell
.\mvnw.cmd clean test '-Dtest.postgres.port=54353' '-Dtest.postgres.mandatory=true'
```

For another dedicated local cluster, use its port and add
`'-Dtest.postgres.isolated=true'` to attest that the entire cluster is reserved for
testing. The suite creates synthetic databases with unique names and cleans up
its own databases. A missing, unreachable or unverified server is a failure;
database coverage is not silently replaced by mocked persistence.

The default application integration suite exercises startup without migration
profiles or backend flags, validates Flyway, and covers signup, login, reset and
JWT revocation, profile writes, administrator authorization/search, catalog and
settings, order history, and live Socket.IO room admission, reconnects and events.
Focused repository, entity, controller and security suites retain PostgreSQL
locking, rollback, concurrency and independent-connection assertions. Historical
tests specific to the retired database implementation were removed; current
results are recorded in `docs/POSTGRESQL_MIGRATION.md` at the repository root.

## Endpoint smoke tests
Run endpoint smoke tests against local or deployed environments:
```powershell
# Local environment
.\scripts\smoke-test.ps1 -BaseUrl "http://localhost:8080"

# Railway deployment
.\scripts\smoke-test.ps1 -BaseUrl "https://your-railway-app.up.railway.app"
```
Or with bash:
```bash
sh scripts/smoke-test.sh https://your-railway-app.up.railway.app
```

