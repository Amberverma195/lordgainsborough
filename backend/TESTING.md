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
