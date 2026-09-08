# MongoDB datasource support (Helical Insight)

## Assessment summary (reviewer)

| Item | Detail |
|------|--------|
| Submission repo | https://github.com/SOWJANYAKAGITHA/helical-insight-mongodb |
| Clone | `git clone https://github.com/SOWJANYAKAGITHA/helical-insight-mongodb.git` |
| Startup | `bash scripts/setup-dev.sh` then `docker compose -f docker-compose.dev.yml up --build` |
| App URL | http://localhost:8080/hi-ee/ — `hiadmin` / `hiadmin` |
| Mongo (Compose) | Host `mongodb`, DB `hi_demo`, user `hi_mongo`, password `hi_mongo_dev`, authSource `admin` |
| Frontend in image | `Dockerfile.dev` builds `client/` and embeds SPA (incl. Test→Save fix) into the WAR |
| Explicit SQL limits | Prefer `LIMIT n`. Omitting `LIMIT` caps at **200** rows (`defaultQueryLimit`) and raises a `SQLWarning` — not a full scan |
| Out of scope | Joins, `GROUP BY`, `ORDER BY`, full Adhoc SQL parity, Instant BI sidecar config |

Upstream baseline for this work: Helical Insight `61d84cd` (history flattened to a single root commit on this fork).

## Upstream baseline

| Item | Value |
|------|--------|
| Upstream repository | https://github.com/helicalinsight/helicalinsight |
| Branch | `master` |
| Baseline commit | `61d84cd0aa6964aea62a8e65ddfb141f9a69d911` |
| Product version property | `7.0.0-SNAPSHOT` (`server/pom.xml`) |

This work adds a **MongoDB JDBC adapter** that matches Helical Insight’s existing datasource extension points. Upstream already contained configuration stubs for `com.helical.mongodb.MongoJdbcDriver` (URL templates, dialect/function mappings, SQL function XML, UI mock entries, and `MongoConnectionFactory`), but the **driver JAR was not present** in `System/Drivers`, so MongoDB did not appear as a usable connection in the application.

## What changed

### Added

| Path | Purpose |
|------|---------|
| `server/himongo-jdbc/` | Source for the Helical MongoDB JDBC driver (`com.helical.mongodb.MongoJdbcDriver`) |
| `server/hi-repository/System/Drivers/himongo-jdbc-1.0.0.jar` | Shaded driver JAR discovered at runtime like other JDBC drivers |
| `docker/mongodb/` | Local MongoDB 7 Compose stack + deterministic seed data |
| `docs/mongodb-setup.md` | This document |
| `docs/verification-screenshots/` | Credential-free UI screenshots from local verification |

### Updated

| Path | Purpose |
|------|---------|
| `server/hi-repository/System/Admin/databaseDrivers.properties` | Clarified Mongo URL templates; optional `mongodb+srv` type template |
| `server/hi-repository/System/Admin/sqlDialects.properties` | Kept a single himongo dialect mapping note |
| `server/hi-repository/System/Admin/Static/DataSourcesList.groovy` | Categorize Helical Mongo driver under **No SQL & Big Data** as **Mongodb** |
| `server/core/.../GlobalDBUpdateHandler.java` | Missing `id` / `type` / `dataSourceProvider` → validation error instead of NPE |
| `client/.../datasource-create-and-edit.jsx` (+ default/flat/jndi/advanced wrappers) | Fix Test→Save race via synchronous `buttonActionRef` / `onButtonAction` |
| `server/himongo-jdbc/.../MongoDatabaseMetaData.java` | Empty schemas / null `TABLE_SCHEM` so metadata UI lists collections |
| `server/himongo-jdbc/.../MongoSqlExecutor.java` | Unwrap Helical Views `select * from (…) limit n` wrapper for subset SQL |
| `docker-compose.dev.yml` + root `Dockerfile.dev` | Rebuild React client into WAR; Mongo + Postgres sidecars |
| `scripts/setup-dev.sh` | Relative symlinks; bash-invoked helpers; safe before first WAR build; native path patching only if `NATIVE_HI_SETUP=1` |
| `README.md` | Submission clone URL + reviewer startup |

## Why this integration fits

Helical Insight’s primary datasource path is **JDBC**:

1. Driver JARs under `hi-repository/System/Drivers` are scanned.
2. `databaseDrivers.properties` supplies default URL templates for the create-connection UI.
3. Connections are tested through the existing Tomcat JDBC test path → `DriverManager`.
4. Credentials are stored encrypted via existing global connection persistence.
5. Metadata discovery uses JDBC `DatabaseMetaData` (also exposed via `adhoc/metadata/get`).
6. Queries execute through JDBC `Statement` / `ResultSet`.

## Supported SQL subset (verified)

The adapter does **not** implement arbitrary SQL. Verified subset:

| Supported | Examples |
|-----------|----------|
| Connectivity probe | `SELECT 1` |
| Projection | `SELECT * FROM customers` / `SELECT name, email, score FROM customers` |
| Nested fields as columns | `SELECT name, address.city FROM customers` |
| Equality filter (literal) | `WHERE field = 'value'` / numeric / boolean literals |
| Row limit | `LIMIT n` (**prefer this**) |
| Helical Views preview wrapper | `select * from (<subset SELECT>) foo limit 10` (unwrapped before execution; outer/inner `LIMIT` → `min`) |
| Default row cap (no `LIMIT`) | At most **200** rows (`MongoSqlExecutor.DEFAULT_QUERY_ROW_CAP` / JDBC property `defaultQueryLimit`). A `SQLWarning` is attached so callers know results may be incomplete. **Do not treat an uncapped SELECT as a full collection read.** |

**Not supported** (will fail or are out of scope): joins, `GROUP BY`, `ORDER BY`, nested/arbitrary subqueries beyond the Views wrapper above, functions beyond passthrough column names, vendor SQL dialects from full Adhoc SQL generation, JavaScript/shell query evaluation, using Mongo as Helical’s own persistence DB.

Adhoc report builders may emit richer SQL than this adapter executes. Prefer metadata browsing, Views with the subset above, or extend the adapter deliberately for broader translation later.

## Document conversion

| Mongo type | JDBC / tabular |
|------------|----------------|
| ObjectId | hex string |
| Date | `Timestamp` |
| Nested objects | dotted column names (e.g. `address.city`) |
| Arrays / subdocuments | JSON strings |
| Missing / null | SQL `NULL` |

## Local setup

### Full app + Mongo + Postgres (recommended)

From the **repository root**:

```bash
bash scripts/setup-dev.sh
docker compose -f docker-compose.dev.yml up --build
```

`docker-compose.dev.yml` builds via root `Dockerfile.dev`: Node builds `client/` (`npm run build` / build18), overlays SPA into `presentation` webapp (asserts `onButtonAction` is present), then Maven-packages the WAR. Reviewers therefore get the Test→Save UI fix without a manual `docker cp`.

Open `http://localhost:8080/hi-ee/` (login: `hiadmin` / `hiadmin`).

Inside the Compose network, Mongo hostname is **`mongodb`**:

```text
mongodb://hi_mongo:hi_mongo_dev@mongodb:27017/hi_demo?authSource=admin
```

UI form equivalent: Host `mongodb`, Port `27017`, Database `hi_demo`, Username `hi_mongo`, Password `hi_mongo_dev`  
(JDBC URL built as `mongodb://mongodb:27017/hi_demo`; credentials via form fields).

### MongoDB-only demo DB

```bash
cd docker/mongodb
docker compose up -d
```

Host-side URL uses `127.0.0.1` instead of `mongodb`.

### Build the driver

```bash
bash scripts/build-himongo-jdbc.sh
# or:
cd server/himongo-jdbc && mvn clean package
cp target/himongo-jdbc-1.0.0.jar ../hi-repository/System/Drivers/
```

### Config paths and Docker

Tracked `setting.xml` / `globalConnections.xml` keep portable `${INSTALL_PATH}` placeholders.  
`server/docker/entrypoint.sh` rewrites them for the container on each start.  
Do **not** commit machine-specific `/home/...` paths. For native (non-Docker) Tomcat only, run `NATIVE_HI_SETUP=1 bash scripts/setup-dev.sh`.

## Connection rules

- Supply credentials in the **URI userinfo** *or* the JDBC user/password fields, **not both**.
- Prefer `?authSource=admin` in the URI when using root-style users created by the demo Compose stack.
- Finite timeouts: `serverSelectionTimeoutMS`, `connectTimeoutMS`, `socketTimeoutMS` (defaults 5s / 5s / 10s).
- Column discovery samples up to `sampleSize` documents (default 50).

## Datasource update / NPE (investigated)

| Finding | Detail |
|---------|--------|
| Cause | Invalid update payloads **missing `type`** (and related required fields) caused `JsonObject.get(...).getAsString()` NPE in `GlobalDBUpdateHandler` |
| UI behavior | Create/edit UI (`getCreateEditFD`) sends `type` (e.g. `dynamicDataSource`) on update |
| With UI-shaped payload | Update + retest of saved Mongo connections **succeed** |
| Fix | `GsonUtility.optString` + validation → `RequiredParameterIsNullException: The parameter type is null or empty. Invalid request.` (no NPE) |
| Not Mongo-specific | Same handler path for other global JDBC datasources |

## Verification status (local)

Honesty rule: items are separated by **how** they were checked (browser / API / JDBC). Do not treat an API or JDBC pass as proof of a browser UI path.

### Browser checks (Chrome CDP / app UI)

| Check | Result | Evidence |
|-------|--------|----------|
| Test → Save race (Mongo create) | **Fixed**. Synchronous `buttonActionRef` / `onButtonAction` so `onFinish` sees Save, not the prior Test. Multi-URL Mongo still shows confirm modal before `write`. | Create → Test → Save → Yes → id **1101**; Edit → Update → `BrowserMongoFresh-edited` |
| Fresh Mongo create / test / save / reopen / edit / save | Pass | Drawer list shows `BrowserMongoFresh-edited` / `1101` |
| Persistence after full page refresh | Pass | `49c-mongo-after-full-refresh.png` — connection still listed |
| Postgres row **Test** still succeeds | Pass | `ComposePostgresDemoAPI` (`1001`) → `quickTest` success (`60-postgres-row-test.png`) |
| Metadata sidebar: expand Mongodb → connection → `hi_demo` → **`customers`** | Pass (was an app/driver defect, not automation timing) | Empty `getSchemas()` + null `TABLE_SCHEM`; `51-metadata-customers.png` |
| Fields in UI | Pass after Add to Metadata + expand in metadata panel | `_id`, `name`, `email`, `score`, `address.city`, … (`57` / `62`) |
| Views **Execute** + eye **Preview** with subset SQL | Pass | `SELECT name, email, score FROM customers LIMIT 3` → Ada / Grace / Alan (`63` / `65-views-data-drawer.png`) |

### API checks (authenticated `services`)

| Check | Result |
|-------|--------|
| `core/dataSource/test` Mongo `1101` (`mongodb` host) | Pass — connection test successful |
| `adhoc/metadata` `fetchColumns` for `customers` on `1101` | Pass — columns include `_id`, `name`, `email`, `address.city`, … |
| `adhoc/metadata/retrieveViewLabels` (Views path; HI wraps SQL) | Pass after unwrap fix — returns labels + 3 seeded rows |
| Missing-`type` update | Validation error (`RequiredParameterIsNullException`), not NPE |

### JDBC checks (himongo jar / IT)

| Check | Result |
|-------|--------|
| Himongo IT vs Compose Mongo | Pass — isolated collection `himongo_it_customers`; `HIMONGO_IT=true` fails hard if Mongo is down; real assertions (no `\|\| true`) |
| Direct JDBC: collections / columns / `SELECT … LIMIT` | Pass |
| Omitting `LIMIT` | Caps at 200 + `SQLWarning` (documented) |

### Instant BI sidecar (out of scope for this change)

| Finding | Detail |
|---------|--------|
| Symptom | `assignment-instantbi-1` restart loop |
| Cause | Missing `/app/helicalbi/config/application_config.yaml` (`FileNotFoundError` in `ConfigLoader`) |
| Impact on Mongo JDBC workflow | **None observed.** Datasource Test/Save, metadata expand, and Views preview use `hiee` + Mongo only. |
| Action | Left unchanged (pre-existing Compose/config gap). |

### Remaining functional limitations

| Item | Status |
|------|--------|
| Full Adhoc / report SQL beyond documented subset | Out of scope — richer generators may still emit unsupported SQL |
| Metadata **file save** / share / production report packaging | Not required for this pass; Views preview + metadata browse verified |
| Instant BI | Unrelated restart loop; see above |
| Upstream git history on this fork | Flattened to a single root commit for upload size/reliability; baseline noted above |

## Assumptions

- Deployments load driver JARs from `hi-repository/System/Drivers`.
- MongoDB 4.4+ with sync driver 4.11.x; demo uses MongoDB 7.
- First milestone does not require full SQL parity with RDBMS Adhoc generation.
- Omitting `LIMIT` never means “read the whole collection”; it means “at most `defaultQueryLimit` rows” (default 200).
