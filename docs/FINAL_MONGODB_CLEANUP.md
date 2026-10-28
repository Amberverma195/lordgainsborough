# Final MongoDB cleanup audit

Checked 2026-10-27, America/Toronto. Starting main commit: c2db8d26f595dafc82585c8dd720618f211d8b11. Latest origin/main was fetched and matched that commit before edits.

## Source and dependency audit

PG-026 already removed the active MongoDB persistence implementation. No active MongoDB model/repository, MongoTemplate, MongoRepository, MongoDB annotation/import, runtime configuration, connection variable reference, initialization script, migration adapter or MongoDB-specific container setup remains in the current application. PostgreSQL/JPA/JDBC is the only database backend. A scan of all tracked files and explicit backend/frontend runtime/configuration scans found only historical comments, migration records, negative regression assertions and outdated operational instructions.

Updated projectrun.txt to list current PostgreSQL environment variables instead of obsolete MongoDB variables. Updated backend/OPERATIONS.md to describe the actual PostgreSQL counters, expiry checks and transactional password reset. Historical migration journal content, migration SQL, test assertions proving absent legacy beans and historical test names were preserved. No application runtime source, frontend behavior, API, JWT, Socket.IO, PostgreSQL entity/repository, Flyway V1/V2 or cloud data was modified.

Executed in backend:

```powershell
.\mvnw.cmd dependency:tree '-Dincludes=org.mongodb:*,org.springframework.boot:*mongo*,org.springframework.data:*mongo*,org.testcontainers:*'
```

Result: BUILD SUCCESS; no matching direct/transitive dependencies. No full regression or compilation rerun was necessary for these documentation-only changes. The previous PG-027 test results remain historical evidence, not newly executed tests.

## Railway production

Project: ample-courtesy (27d07ab7-1c22-428b-b7ac-18155de85570). Service: lordgainsborough (694abf12-8b30-4537-9b27-4ca706e06a29). Environment: production (8129f8aa-d58d-4818-ae2c-e7dc3a3f33f4).

Initial connected-management reads listed MONGODB_URI and MONGODB_DATABASE and three pending changes. During the audit, those changes were applied outside this agent's tool calls. Follow-up live configuration and rendered variable reads confirmed **both variables absent**. All required DB_*, JWT_SECRET, ABUSE_IP_HASH_SECRET, mail credentials, APP_ALLOWED_ORIGINS, SOCKETIO_ORIGIN and Railway-managed variable names remained present. Secret values were redacted by the connected API and were not overwritten. No variable deletion, pending-change acceptance, service-setting mutation or explicit deployment was performed by this agent. The connector has no variable-delete tool; the local CLI login was expired, so no CLI mutation was attempted.

Observed subsequent deployment: c014d080-c6a7-4f8d-97af-d64f26b57837, SUCCESS. Runtime logs show:

- Java 21 startup with 13 JPA repositories.
- PostgreSQL 17.11 connection pool started against Supabase.
- Both Flyway migrations validated, schema v2 current, no migration necessary.
- Socket.IO on 9092 and HTTP on 8080 started; application startup completed at 2026-10-28T00:05:25Z.
- Existing catalog initialization already applied and skipped.

The live menu API returned HTTP 200 with an array of 119 items. Local DNS filtered the custom API hostname to a Fortinet block page, so ordinary local requests initially failed certificate verification. The successful read used curl --connect-to to connect through Railway's already-configured CNAME while retaining the original URL hostname, Host header, SNI and certificate verification. No DNS, firewall, TLS or deployment configuration was changed:

```powershell
curl.exe --silent --show-error --connect-to api.lordgainsboroughrestaurant.ca:443:vqgtc4kg.up.railway.app:443 https://api.lordgainsboroughrestaurant.ca/api/menu
```

Railway reports the custom domain verified, its certificate valid and the CNAME propagated. An earlier diagnostic with TLS verification disabled returned only the local filtering page; it was not counted as a successful health check. The Railway Socket.IO polling endpoint also returned HTTP 200. No application data was written during these health checks.

## Atlas: deletion not completed

The saved local settings identify a **candidate** database named LordGainsBorough on a host associated with cluster0. The URI was consumed in memory without printing, logging or committing its credentials. A read-only connection attempt failed with MongoServerError code 8000, authentication failed. No database command to drop data was issued.

Atlas project name/ID: not verified. Cluster display name/ownership: not verified; cluster0 is inferred only from the saved host label. Database existence/exclusive application ownership: not verified. Collections: could not enumerate. Collections deleted: none. Database actually dropped: **no**. Deletion cannot be reported as complete.

The MongoDB Atlas connector is available in the plugin directory but is not installed/connected in this session. Existing local MongoDB credentials are unusable. To complete the authorized deletion, connect Atlas with access to the original project or perform these dashboard steps:

1. Sign in to MongoDB Atlas. Select the original organization and project, then identify the cluster matching the saved connection host. Do not assume an unrelated cluster named Cluster0 is the correct one.
2. Open Database > Data Explorer for that cluster. Select LordGainsBorough only after verifying it belongs exclusively to this application. Confirm that other applications use different databases. This is MongoDB Atlas, not Supabase.
3. Record the project, cluster, exact database name, collections and any indexes needed for the cleanup record. Do not export credentials or customer data.
4. In the Connections sidebar, select or hover over the verified application database and click its trash icon. Type the exact database name and choose Drop Database. This deletes that database and all its collections/indexes.
5. Refresh Data Explorer and verify that the application database is absent. Record that verification and the deleted collection names.
6. Leave admin, config, local, other databases, projects, organizations and shared backup/billing resources unchanged. Cluster/project deletion requires separate approval even if exclusive use is later established. Do not decommission the cluster based solely on this audit.

Official dashboard instructions: [Create, View, and Drop Databases](https://www.mongodb.com/docs/atlas/atlas-ui/databases/).

## Remaining action

Source cleanup and Railway variable absence are verified; PostgreSQL and the live menu remain healthy. **Atlas data deletion remains blocked by authentication and missing project/ownership verification.** No Supabase data, shared cluster, Atlas project, organization or unrelated resource was deleted. Old local Windows MongoDB settings were not removed because they may be needed to identify the retired database; they are unused by the application and were never printed.
