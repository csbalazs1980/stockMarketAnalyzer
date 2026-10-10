# Stock Market Analyzer — backend

Spring Boot 4.1 REST backend on Java 25 (LTS), built with Gradle 9 (Kotlin DSL),
persisting to PostgreSQL 18 with Liquibase-managed schema.

## Stack

| Concern            | Choice                                        |
|--------------------|-----------------------------------------------|
| Language / runtime | Java 25 (Gradle toolchain)                    |
| Framework          | Spring Boot 4.1.0 (Spring Framework 7)        |
| Build              | Gradle 9.7.0 via wrapper, Kotlin DSL          |
| Persistence        | Spring Data JPA / Hibernate + PostgreSQL 18   |
| Migrations         | Liquibase (YAML changelogs)                   |
| Identity           | Red Hat build of Keycloak 26.6 (OIDC)         |
| API security       | Spring Security OAuth2 resource server (JWT)  |
| Boilerplate        | Lombok 1.18.46 (JDK 25/26 capable)            |
| API docs           | springdoc-openapi 3.1.0 + Swagger UI          |
| Tests              | JUnit 5 + Testcontainers                      |

Dependency versions come from the Spring Boot BOM (`SpringBootPlugin.BOM_COORDINATES`),
so the only version you normally bump is the Spring Boot plugin in `build.gradle.kts`.
One caveat with this approach: the platform only reaches configurations that extend the
ones it is declared on (`implementation`, `annotationProcessor`, `testImplementation`).
Gradle's `developmentOnly` configuration extends none of them, so a dependency added there
needs either an explicit version or its own `developmentOnly(platform(...))` line —
otherwise it resolves to a blank version and the build fails with
`Could not find org.springframework.boot:some-artifact:`.

Two dependencies are pinned explicitly because the BOM does not manage them: **Lombok**
(JDK support is tied to specific releases — JDK 25 landed in 1.18.40, JDK 26 in 1.18.46)
and **springdoc-openapi** (the 3.x line targets Spring Boot 4; 2.x is Boot 3 only, and
3.1.0 is built against Boot 4.1.0 exactly).

## Lombok conventions

Lombok is `compileOnly` + `annotationProcessor` (and the same pair for tests). Enable the
Lombok plugin in your IDE — IntelliJ bundles it, just make sure annotation processing is
on under *Build, Execution, Deployment → Compiler → Annotation Processors*.

Where it is and is not used:

* **Entities** (`Instrument`, `PriceBar`, `AuditableEntity`) — `@Getter`, `@Setter`,
  `@NoArgsConstructor(access = PROTECTED)` for JPA, and `@ToString(onlyExplicitlyIncluded = true)`.
* **`@Data` and `@EqualsAndHashCode` are deliberately not used on entities.** Field-wide
  equality breaks Hibernate's identity contract for lazy proxies and detached instances, so
  `equals`/`hashCode` are hand-written against the primary key. `lombok.config` documents this.
* `@Setter(AccessLevel.NONE)` guards generated ids and `Instrument.symbol` — the latter has a
  hand-written setter that upper-cases the ticker.
* Lazy associations (`PriceBar.instrument`) are excluded from `toString()` to avoid
  `LazyInitializationException` from a log statement.
* **Services and controllers** use `@RequiredArgsConstructor` (constructor injection, fields
  stay `final`) and `@Slf4j` where there is something worth logging.
* **DTOs stay as Java records.** A record already gives you the accessors, `equals`,
  `hashCode` and `toString` with less ceremony than `@Value`, and Jackson binds them
  natively — Lombok would be a step backwards there.

Project-wide Lombok settings live in `lombok.config` at the project root.

## Prerequisites

* JDK 25 — if it is not installed, Gradle's toolchain support will try to provision it.
  You can also point it at an existing JDK: `org.gradle.java.installations.paths=...` in
  `gradle.properties`.
* Docker Desktop (for PostgreSQL and for the Testcontainers-based tests).

## Run it

```bash
# 1. copy the env template (optional — the defaults below are already baked in)
cp .env.example .env

# 2. start PostgreSQL and Keycloak
docker compose up -d

# 3. run the app (applies Liquibase changesets on startup)
./gradlew bootRun

# with dev seed data (AAPL / MSFT / SPY), verbose SQL logging AND NO AUTHENTICATION:
./gradlew bootRun --args='--spring.profiles.active=local'
```

The `local` profile turns API security off — see [Local development without
authentication](#local-development-without-authentication). Keycloak is not needed for it,
so step 2 can be `docker compose -f docker.yaml up -d postgres` alone.

Windows PowerShell: use `.\gradlew.bat` instead of `./gradlew`.

Default connection: `jdbc:postgresql://localhost:5432/stockmarket`, user/password
`stockmarket` / `stockmarket`. Override with the `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
environment variables (see `application.yaml`).

Optional database UI: `docker compose --profile tools up -d` → http://localhost:8081

Stop everything (keeping data): `docker compose down`.
Wipe the database volume too: `docker compose down -v` — note this now drops the application
data *and* Keycloak's, since both live in the same volume. To reset only Keycloak (e.g. to make
`--import-realm` pick up an edited `realm-stockmarket.json`), drop just its database instead:

```bash
docker compose stop keycloak
docker compose exec postgres psql -U stockmarket -d stockmarket -c 'DROP DATABASE keycloak'
docker compose up -d keycloak
```

Keycloak takes roughly 30–60 seconds to become ready on a cold start (schema creation plus
realm import). `docker compose ps` shows it as `healthy` when it is done — the app will
return 401 on every request until then, because it cannot fetch the signing keys.

### Where Postgres stores its data

`postgres:18` and later put the cluster in a major-version-specific directory --
`PGDATA=/var/lib/postgresql/18/docker` -- and declare the volume one level up, at
`/var/lib/postgresql`. That is what `docker.yaml` mounts.

Mounting the pre-18 path (`postgres-data:/var/lib/postgresql/data`) does not move `PGDATA`; it
just parks the named volume somewhere the server never writes, and the cluster ends up in an
anonymous volume that disappears the next time the container is recreated. The 18 entrypoint
refuses to start on that layout:

```
Error: in 18+, these Docker images are configured to store database data in a
       format which is compatible with "pg_ctlcluster" ...
         /var/lib/postgresql/data (unused mount/volume)
```

Despite the wording, that particular line means the mount is *unused*, not that data is at
risk: the entrypoint only reports it when it found no cluster anywhere (`docker-entrypoint.sh`
adds it when `${#OLD_DATABASES[@]}` is 0 and `/var/lib/postgresql/data` is a mountpoint). The
same message without the `(unused mount/volume)` suffix is the other case -- a real cluster in
the old location, which needs `pg_upgrade` or a dump/restore before 18 will start on it.
Setting `PGDATA=/var/lib/postgresql/data` pins the legacy layout and skips the check entirely,
but it also gives up the version-scoped directories that make the next major upgrade a
`pg_upgrade --link` away. See [docker-library/postgres#1259](https://github.com/docker-library/postgres/pull/1259).

### Keycloak image and database

`docker.yaml` pulls `quay.io/keycloak/keycloak:26.6`, the upstream community image. It pulls
anonymously — no registry login. Red Hat's supported build,
`registry.redhat.io/rhbk/keycloak-rhel9:26.6`, is the same codebase with the same configuration
surface; to use it, `docker login registry.redhat.io` (a free Developer account is enough) and
set `KEYCLOAK_IMAGE` in `.env`:

```
KEYCLOAK_IMAGE=registry.redhat.io/rhbk/keycloak-rhel9:26.6
```

Keycloak shares the `sma-postgres` container with the application, but gets its own role and
its own database (`keycloak` / `keycloak`), so its tables never mix with the Liquibase-managed
application schema. The `keycloak-db-init` one-shot service creates that role and database
before Keycloak starts; it is idempotent, so it is a no-op on every run after the first.

It is a job rather than a `/docker-entrypoint-initdb.d` script deliberately: those scripts only
run when the Postgres data volume is first created, so on an existing `sma-postgres-data`
volume they would silently never fire.

## REST API

Base path `/api/v1`. Errors are returned as RFC 9457 problem details.

### Instruments

| Method   | Path                       | Notes                                                          |
|----------|----------------------------|----------------------------------------------------------------|
| `GET`    | `/instruments`             | Paged. Filters: `search`, `assetType`, `exchange`, `active`, plus `page`/`size`/`sort`. |
| `GET`    | `/instruments/{symbol}`    | Symbol lookup is case-insensitive.                             |
| `POST`   | `/instruments`             | Creates an instrument; `409` if the symbol exists.             |
| `PUT`    | `/instruments/{symbol}`    | Full update.                                                   |
| `DELETE` | `/instruments/{symbol}`    | Cascades to that instrument's price bars.                      |

### Price bars (daily OHLCV)

| Method   | Path                                    | Notes                                                     |
|----------|-----------------------------------------|-----------------------------------------------------------|
| `GET`    | `/instruments/{symbol}/prices`          | Paged, newest first. Optional `from` / `to` (ISO dates).  |
| `GET`    | `/instruments/{symbol}/prices/latest`   | Most recent stored bar.                                   |
| `GET`    | `/instruments/{symbol}/prices/summary`  | Count, min low, max high, average close over a range.     |
| `POST`   | `/instruments/{symbol}/prices`          | Bulk upsert — a JSON array of bars, keyed on `tradeDate`. |
| `DELETE` | `/instruments/{symbol}/prices`          | Deletes a date range, returns `{"deleted": n}`.           |

### Wealth tracking

A personal net-worth ledger, unrelated to the market data above. An entry is a *quantity of a
unit* held on a date (`36775 USD`, `130 OZ`, `2.03 KG`); `parentId` links rows into a tree so
accounts roll up into an aggregate. Nothing about worth is stored — values are derived on read
from the caller's own exchange rates, so correcting a rate re-values every snapshot at once.

Everything here is scoped to the caller's Keycloak `sub`: another subject's rows return `404`,
never someone else's data.

| Method   | Path                             | Notes                                                                                       |
|----------|----------------------------------|---------------------------------------------------------------------------------------------|
| `GET`    | `/wealth/entries`                | Paged, newest snapshot first. Filters: `from`, `to`, `unit`, `parentId`, `rootsOnly`, `search`, `valueIn`. |
| `GET`    | `/wealth/entries/latest`         | Every row of the most recent snapshot date.                                                 |
| `GET`    | `/wealth/entries/{id}`           | Optional `valueIn` (defaults to `HUF`).                                                     |
| `GET`    | `/wealth/entries/{id}/children`  | The rows that roll up into this one.                                                        |
| `POST`   | `/wealth/entries`                | Creates a row. `parentId` must be one of your own entries.                                  |
| `POST`   | `/wealth/entries/{id}/clone`     | Copies the row and its whole subtree into a new snapshot, in one transaction.               |
| `PUT`    | `/wealth/entries/{id}`           | Full update. Re-parenting that would create a cycle is a `400`.                             |
| `DELETE` | `/wealth/entries/{id}`           | `409` when the row has children, unless `?cascade=true` (which removes the subtree).        |

| Method   | Path                              | Notes                                                                    |
|----------|-----------------------------------|--------------------------------------------------------------------------|
| `GET`    | `/wealth/exchange-rates`          | Paged, newest first. Filters: `sourceUnit`, `destinationUnit`, `from`, `to`. |
| `GET`    | `/wealth/exchange-rates/in-force` | Newest quote for a pair on or before `on` (defaults to today).           |
| `POST`   | `/wealth/exchange-rates`          | Upsert — one quote per pair and date, so re-posting a month is idempotent. |
| `POST`   | `/wealth/exchange-rates/refresh`  | Fetches the configured pairs from the rate feed. See [Rate feed](#rate-feed). |
| `POST`   | `/wealth/exchange-rates/import`   | Multipart CSV upload. See [CSV import](#csv-import).                     |
| `GET`    | `/wealth/exchange-rates/import/sample` | The sample file the import expects.                                 |
| `DELETE` | `/wealth/exchange-rates/{id}`     | Removes a quote.                                                         |

Valuation resolves a factor in three steps: the same unit converts one-to-one, a directly
quoted pair wins next, and otherwise the reciprocal of the opposite quote is used. When
nothing connects the two units the entry's `currentValue` comes back `null` — *not convertible*
and *worth nothing* are different answers.

```bash
# 1 USD = 314 HUF as of the August snapshot
curl -s -X POST localhost:8080/api/v1/wealth/exchange-rates \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sourceUnit":"USD","destinationUnit":"HUF","rate":314,"rateDate":"2026-08-05"}'

curl -s -X POST localhost:8080/api/v1/wealth/entries \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"rev","unit":"USD","quantity":3437,"entryDate":"2026-08-05"}'

curl -s 'localhost:8080/api/v1/wealth/entries/latest' -H "Authorization: Bearer $TOKEN"

# Next month: copy entry 12 and everything under it, keeping the shape and the names.
# Rows left out of "overrides" are copied as they stand; an override replaces the row's state
# outright, so a null quantity records the copy as an aggregate and a blank unit inherits the
# unit of the copy above it.
curl -s -X POST localhost:8080/api/v1/wealth/entries/12/clone \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"entryDate":"2026-09-30","overrides":[
        {"sourceId":12,"quantity":null,"unit":"USD"},
        {"sourceId":13,"quantity":3978},
        {"sourceId":14,"quantity":1189}]}'
```

The copy hangs off the same parent as the source — a clone is the same holding recorded again,
not a move — and either every row is written or none is, so a failure half way through cannot
leave a subtree that is neither last month's snapshot nor this month's.

### Received dividends

What you were actually paid, by which instrument, in what currency, on what date. Per-user like
the wealth tables — a dividend you received is your data, not the installation's — and valued on
read from **your own exchange rates**, using the rate in force on the payment date. Nothing about
worth is stored, so correcting a rate re-values the whole history.

`instrumentId` points at the shared `instrument` table rather than at a free-text company name:
one list of companies, one import target, no second list to drift from the first. `unit` is kept
separately from `instrument.currency` on purpose — a broker converting a US payout into HUF
before it lands is the normal case, not an anomaly.

| Method   | Path                     | Notes                                                                                       |
|----------|--------------------------|---------------------------------------------------------------------------------------------|
| `GET`    | `/dividends`             | Paged, newest payment first. Filters: `from`, `to`, `instrumentId`, `unit`, `valueIn`.       |
| `GET`    | `/dividends/summary`     | The same filters, summed over **every** matching row rather than the page being shown.       |
| `GET`    | `/dividends/{id}`        | Optional `valueIn` (defaults to `HUF`).                                                     |
| `POST`   | `/dividends`             | Records one. `instrumentId` must resolve to a known instrument.                              |
| `POST`   | `/dividends/import`      | Multipart CSV upload, by ticker rather than id. See [CSV import](#csv-import).               |
| `GET`    | `/dividends/import/sample` | The sample file the import expects.                                                        |
| `PUT`    | `/dividends/{id}`        | Full update.                                                                                |
| `DELETE` | `/dividends/{id}`        | Removes it.                                                                                  |

`currentValue` is `null` — never `0` — when no rate connects the payment currency to `valueIn`,
and `/dividends/summary` reports `totalPartial: true` when any matching row was in that state, so
the UI can render `≥ 228 199 HUF` instead of passing an undercount off as the whole answer.

**Deleting an instrument somebody has been paid by is a `409`.** `price_bar` cascades away with
its instrument because market data can be refetched; `dividend.instrument_id` is `ON DELETE
RESTRICT` because a dividend was typed in by hand.

```bash
# The instrument first -- shared reference data, so this one needs an ADMIN token.
curl -s -X POST localhost:8080/api/v1/instruments \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"symbol":"AAPL","name":"Apple Inc.","exchange":"NASDAQ","currency":"USD","assetType":"STOCK"}'

curl -s -X POST localhost:8080/api/v1/dividends \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"instrumentId":1,"amount":42.75,"unit":"USD","paymentDate":"2026-08-15"}'

curl -s 'localhost:8080/api/v1/dividends/summary?from=2026-01-01&to=2026-12-31' \
  -H "Authorization: Bearer $TOKEN"
```

### Rate feed

`POST /api/v1/wealth/exchange-rates/refresh` reads today's published rates for the configured
pairs and stores each as the caller's own mark — the same rows the manual form writes, editable
and deletable the same way. Nothing here creates a second, privileged class of rate.

The source is [Frankfurter](https://frankfurter.dev): no API key, no quota, open source, and
self-hostable with Docker if the dependency ever needs removing. Configuration lives under
`app.rate-feed.*`:

```yaml
app:
  rate-feed:
    enabled: true
    base-url: https://api.frankfurter.dev/v2
    provider: MNB          # blank lets the aggregator choose
    pairs: [USD:HUF, EUR:HUF, EUR:USD]
    connect-timeout: 5s
    read-timeout: 10s
```

Three things about this are load-bearing:

- **The provider is pinned.** Frankfurter aggregates 84 central banks; unpinned, it picks one, so
  the same pair on the same day can come back as a different number. `MNB` is the Hungarian
  central bank's *official* rate — the right mark for this ledger. It is also the working one:
  as of 2026-09-08 a request pinned to `ECB` returned rates dated **2026-08-13**, about four
  weeks stale, while `MNB` returned that day's.
- **Each quote is stored under the feed's own `date`, never today's.** Asked on a Sunday the feed
  answers with Friday's rate; stamping it Sunday would record a quote that was never published.
  Getting this right is also what makes the button idempotent — the store is an upsert on
  (pair, date), so pressing it three times over a weekend rewrites one row instead of inventing
  three.
- **`EUR:HUF` is not redundant.** Valuation resolves a factor by identity, a direct quote, or the
  reciprocal of the opposite quote — it **does not chain**. Without a direct quote, EUR holdings
  are unconvertible no matter how many other pairs are stored.

Requests are grouped by base currency, so those three pairs cost two round trips. The fetch
happens with no transaction open and no database connection held; each pair is then stored by the
same upsert the form uses. A failure part-way therefore leaves the earlier pairs stored, and
pressing the button again completes the job.

An unreachable, slow or nonsensical feed is a **`502`**, not a 400 or a 500 — nothing is the
caller's fault and nothing is wrong with this application.

```bash
curl -s -X POST localhost:8080/api/v1/wealth/exchange-rates/refresh -H "Authorization: Bearer $TOKEN"

# {"provider":"MNB","requested":3,"stored":3,"rates":[
#   {"id":41,"sourceUnit":"USD","destinationUnit":"HUF","rate":314.55,"rateDate":"2026-09-08"}, ... ]}
```

There is no scheduled job. Adding one is a `@Scheduled` method calling the same service — but it
would need an owner to store the rates against, and there is no JWT on a scheduler thread, so
that decision is deferred until the server actually runs continuously.

### CSV import

Two endpoints per importable slice: a `GET .../import/sample` that serves the header row plus one
example, and a `POST .../import` that takes that file back as `multipart/form-data` under the
field name `file`. Both sit inside their slice's security prefix, so an ordinary `USER` imports
their own rows with no config change.

| Columns | |
|---|---|
| `/wealth/exchange-rates/import` | `sourceUnit`, `destinationUnit`, `rate`, `rateDate` |
| `/dividends/import` | `symbol`, `amount`, `unit`, `paymentDate` |

Dividends name the company by its **ticker symbol**, not by database id: a file somebody fills in
by hand should not need them to look ids up first. An unknown symbol is a row that fails, never an
instrument the import invents.

What the parser accepts, and why:

- **Comma or semicolon**, sniffed from the header line rather than configured. An English
  spreadsheet writes `USD,HUF,352.4`; a Hungarian one writes `USD;HUF;352,4`, and asking the user
  which one their file is would be asking them something their spreadsheet never told them either.
  The delimiter also settles the decimal separator, since a file cannot use the comma for both —
  except that a group of other than three digits gives a decimal point away regardless, so
  `1234.56` in a semicolon file is still 1234.56 and not 123456.
- **Column order does not matter**, and names are matched with case, spaces and underscores
  removed: `sourceUnit`, `source_unit` and `Source Unit` are the same column.
- **Dates are year-first**: `2026-08-15`, `2026.08.15`, `2026. 8. 15.` all work. Day-first forms
  are refused rather than guessed at — `05/08/2026` is two different days depending on who wrote it.
- **A blank date means today**, exactly as the single-row API does. Quoting, embedded newlines, a
  UTF-8 BOM, CRLF endings and a non-UTF-8 encoding are all handled.

Two kinds of failure, kept apart:

- The **file** is unusable — not named `.csv`, empty, actually a spreadsheet or a PDF, no header,
  or missing a required column. That is a `400` problem detail and **nothing is stored**.
- A **row** is unusable — a blank required cell, an unparseable number or date, an unknown ticker.
  That row is skipped and counted; the rest of the file is stored. One mistyped cell should not
  cost you a year of rates.

The response is the counts, and `processedRows + failedRows + duplicateRows == totalRows`:

```bash
curl -s -X POST localhost:8080/api/v1/dividends/import \
  -H "Authorization: Bearer $TOKEN" -F 'file=@dividends.csv'

# {"totalRows":136,"processedRows":132,"failedRows":3,"duplicateRows":1}
```

`duplicateRows` only ever moves for dividends. An exchange rate has a natural key — the pair and
the date — so each row is an upsert and re-uploading a file overwrites what it stored last time. A
dividend has none, so "the same row again" is defined as every field matching, and a match is
skipped rather than doubling the history. **Which** rows failed is deliberately not reported yet.

### Inflation

Annual inflation per currency, in **percentage points** — `4.6` means 4.6%, negative means
deflation. Unlike the wealth tables this is *shared reference data*: "HUF inflation in 2025" is
the same fact for everyone, so there is no owner column, and it sits outside `/api/v1/wealth/**`
precisely so it picks up the normal rule — any token reads, only `ADMIN` writes.

| Method   | Path                              | Notes                                                                    |
|----------|-----------------------------------|--------------------------------------------------------------------------|
| `GET`    | `/inflation-rates`                | Paged, newest year first. Filters: `unit`, `fromYear`, `toYear`.         |
| `GET`    | `/inflation-rates/{unit}/{year}`  | Lookup by the natural key; the currency is case-insensitive.            |
| `POST`   | `/inflation-rates`                | Upsert — one figure per currency and year, so a provisional estimate can be replaced by the final print. |
| `DELETE` | `/inflation-rates/{id}`           | Removes a figure.                                                        |

```bash
curl -s -X POST localhost:8080/api/v1/inflation-rates \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"unit":"HUF","year":2025,"rate":4.6}'

curl -s localhost:8080/api/v1/inflation-rates/huf/2025 -H "Authorization: Bearer $TOKEN"
```

Actuator lives at `/actuator` (`health`, `info`, `metrics`, `liquibase` exposed).

## Authentication

The backend is a pure OAuth2 **resource server**. It has no user table and no login page —
identity lives entirely in Keycloak, and every request carries a bearer token that the app
validates against the realm's public keys.

### What the realm import creates

`docker/keycloak/realm-stockmarket.json` is mounted into the container and applied on first
start (`--import-realm`). It defines realm **`stockmarket`** containing:

| | |
|---|---|
| Realm roles | `ADMIN`, `USER` (`USER` is the default role for new accounts) |
| Client `stockmarket-api` | Confidential. The resource server, and the audience every token must carry. Direct access grants are on so you can fetch a token with curl. Dev secret: `dev-only-secret-change-me` |
| Client `stockmarket-swagger` | Public, authorization code + PKCE. Used by the Swagger UI *Authorize* button |
| User `sma-admin` / `admin` | Roles `ADMIN`, `USER` — the admin account |
| User `sma-user` / `user` | Role `USER` — for checking that non-admins really are refused |

Separately, `admin` / `admin` is the **Keycloak** administrator for the management console at
http://localhost:8180/admin. That account administers Keycloak itself; `sma-admin` is an
application user inside the realm. They are unrelated.

Every credential above is a development default. Change them before this runs anywhere real,
and note that editing the realm JSON after the first start has no effect — the import only
runs against an empty database, so `docker compose down -v` first, or make the change through
the admin console.

### Who can do what

| Path | Rule |
|---|---|
| `GET /api/v1/**` | Any valid token |
| `POST` / `PUT` / `PATCH` / `DELETE` `/api/v1/**` | Realm role `ADMIN` |
| `/api/v1/wealth/**` (any method) | Any valid token — the data is the caller's own, so an ordinary `USER` writes it. This rule sits **above** the admin-only ones; the first match wins. |
| `/api/v1/dividends/**` (any method) | Same rule, same reason, same placement — a dividend you received is your row, not the installation's. |
| `/actuator/health`, `/actuator/info` | Public |
| `/swagger-ui/**`, `/v3/api-docs/**` | Public — a client has to read the contract before it can authenticate |
| Everything, under the `local` profile | No token — served as a fixed developer identity with `ADMIN`+`USER`. See below. |

Keycloak publishes realm roles under `realm_access.roles`, which Spring Security does not
understand out of the box. `KeycloakRealmRoleConverter` maps them to `ROLE_*` authorities —
without it every token would authenticate with zero roles and every write would 403.

401 and 403 responses are RFC 9457 problem details, matching the rest of the API. That needs
explicit handlers (`ProblemDetailAuthenticationEntryPoint`, `ProblemDetailAccessDeniedHandler`)
because security failures happen in the filter chain, before `@RestControllerAdvice` runs.

### Local development without authentication

Running with the `local` profile replaces the whole filter chain:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
curl -s localhost:8080/api/v1/wealth/entries/latest      # no Authorization header, works
```

`SecurityConfig` is `@Profile("!local")` and `LocalSecurityConfig` is `@Profile("local")`, so
exactly one of them is ever active — and the condition is negative on purpose: a deployment
that sets **no** profile at all gets the real chain. Security here is opt-out, never opt-in.
Startup prints a warning banner naming the identity in use, so a stray `local` in production
is loud rather than silent.

Requests are not merely permitted, they are **authenticated as a fixed developer**:
`LocalUserAuthenticationFilter` attaches a synthetic `Jwt` (never decoded, never verified)
carrying a stable `sub`, `preferred_username`, and the realm roles `ADMIN` + `USER`. That
detail matters — a bare `permitAll` with no principal would 500 on every `/api/v1/wealth/**`
call, because those endpoints key rows on the caller's `sub`, and would quietly change the
meaning of every `hasRole` check. With an identity in place, everything below the filter chain
behaves exactly as it does in production.

| Key | Default | |
|---|---|---|
| `app.local-security.subject` | `00000000-0000-0000-0000-00000000d3v0` | Owner of anything you record locally. **Keep it stable** — changing it hides the wealth rows written under the old value. |
| `app.local-security.username` | `local-dev` | Principal name, i.e. `preferred_username`. |
| `app.local-security.roles` | `ADMIN, USER` | Realm roles, mapped to `ROLE_*` by the same converter production uses. |

Set them in `application-local.yaml`. A real `Authorization` header is *ignored* under this
profile rather than validated — to exercise genuine Keycloak roles, drop the profile and get a
token the normal way.

`LocalProfileSecurityIntegrationTest` pins both halves of the promise: an unauthenticated write
succeeds, and it is still attributed to someone who can read it back.

### Getting a token

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/stockmarket/protocol/openid-connect/token \
  -d grant_type=password \
  -d client_id=stockmarket-api \
  -d client_secret=dev-only-secret-change-me \
  -d username=sma-admin \
  -d password=admin | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

curl -s localhost:8080/api/v1/instruments -H "Authorization: Bearer $TOKEN"
```

Paste the token into https://jwt.io if you want to see the `realm_access.roles` claim the
converter reads.

## API documentation

With the app running:

| What              | URL                                              |
|-------------------|--------------------------------------------------|
| Swagger UI        | http://localhost:8080/swagger-ui.html            |
| OpenAPI JSON      | http://localhost:8080/v3/api-docs/v1             |
| OpenAPI YAML      | http://localhost:8080/v3/api-docs.yaml/v1        |

The document is emitted as a single group, `v1`, matched on `/api/v1/**` — actuator and
error endpoints stay out of the published contract (`springdoc.show-actuator: false`).

**Logging in from the docs page.** Click *Authorize*, and Swagger UI runs Keycloak's
authorization-code + PKCE flow (`sma-admin` / `admin`); from then on every *Try it out* call
carries the bearer token. The authorization and token URLs are derived in `OpenApiConfig`
from the same `issuer-uri` the resource server validates against, so the two cannot drift
apart. Change the port or the realm and both follow automatically.

Where the content comes from:

* Document metadata (title, version, description, contact, licence) is bound from
  `app.openapi.*` in `application.yaml` via `OpenApiProperties`, so it can differ per
  environment without a code change.
* Per-operation summaries, parameter descriptions and response codes are `@Operation`,
  `@Parameter` and `@ApiResponse` annotations on the controllers. Error responses point at
  Spring's `ProblemDetail` so the documented shape matches what `GlobalExceptionHandler`
  actually returns.
* Request/response schemas come from the record DTOs; `@Schema` supplies descriptions and
  examples, and the Jakarta validation annotations (`@NotBlank`, `@Pattern`, `@Size`,
  `@PositiveOrZero`) are picked up automatically as constraints in the spec.

Both endpoints can be switched off without touching code — `OPENAPI_ENABLED=false` and
`SWAGGER_UI_ENABLED=false` — which is what you want once this is deployed anywhere public.

To export the spec as a file for client generation:

```bash
curl -s localhost:8080/v3/api-docs/v1 -o openapi.json
```

CORS covers `/v3/api-docs/**` alongside `/api/**` (see `app.cors.mappings`), so a frontend
on another origin can load the contract directly.

Quick smoke test (`$TOKEN` from the *Getting a token* section above):

```bash
curl -s localhost:8080/actuator/health

curl -s -X POST localhost:8080/api/v1/instruments \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"AAPL","name":"Apple Inc.","exchange":"NASDAQ","currency":"USD","assetType":"STOCK","sector":"Technology"}'

curl -s -X POST localhost:8080/api/v1/instruments/AAPL/prices \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '[{"tradeDate":"2026-08-13","open":214.5,"high":219.0,"low":213.2,"close":218.4,"volume":54210000}]'

curl -s 'localhost:8080/api/v1/instruments/AAPL/prices/summary?from=2026-01-01' \
  -H "Authorization: Bearer $TOKEN"
```

## Database migrations

Liquibase changelogs live under `src/main/resources/db/changelog`:

```
db.changelog-master.yaml        # includeAll of changes/
changes/0001-create-instrument.yaml
changes/0002-create-price-bar.yaml
changes/0003-seed-reference-data.yaml   # context: dev-data
changes/0004-create-wealth-entry.yaml
changes/0005-create-exchange-rate.yaml
changes/0006-create-inflation-rate.yaml
changes/0007-wealth-entry-quantity-nullable.yaml
changes/0008-create-dividend.yaml
```

Add a change by dropping a new `NNNN-description.yaml` into `changes/` — `includeAll`
picks it up in filename order. Keep changesets immutable once they have run anywhere;
write a new one instead of editing an applied one.

Hibernate runs with `ddl-auto: validate`, so the JPA mapping and the Liquibase schema
have to agree — a drifting entity fails fast at startup rather than silently.

Contexts: `default` always runs, `dev-data` only under the `local` profile (or when you
set `LIQUIBASE_CONTEXTS`).

### Applied at every launch

**The `spring-boot-liquibase` module is what makes this happen** — not `liquibase-core`. Boot 4
split the old monolithic `spring-boot-autoconfigure` jar into per-feature modules, so the
library on its own gives you no `LiquibaseAutoConfiguration`. The failure mode is silent: no
error, no `databasechangelog` table, no migrations, and the first thing you see is Hibernate
refusing to start with `Schema validation: missing table [...]`. Confirm the module is on the
classpath with:

```
gradlew dependencies --configuration runtimeClasspath | findstr /i liquibase
```

There is nothing to run by hand. `spring.liquibase.enabled` is `true`, so on every start the
app acquires the changelog lock, applies whatever is pending, and only then lets Hibernate
validate the mapping. Pull a branch with new changesets, start the app, and the database is
current. The startup log says so explicitly (`show-summary: verbose`):

```
UPDATE SUMMARY
Run:                          2
Previously run:               3
Filtered out:                 1
-------------------------------
Total change sets:            6
```

Verify from outside the log:

```bash
# what the database thinks it has applied
curl -s localhost:8080/actuator/liquibase | jq '.contexts[].liquibaseBeans[].changeSets[].id'

# or straight from the bookkeeping table
docker compose -f docker.yaml exec postgres \
  psql -U stockmarket -d stockmarket -c 'SELECT id, dateexecuted, exectype FROM databasechangelog ORDER BY orderexecuted'
```

**If a launch ever hangs on "Waiting for changelog lock":** a previous run was killed
mid-migration and left the lock row set. Clear it and restart:

```bash
docker compose -f docker.yaml exec postgres \
  psql -U stockmarket -d stockmarket -c 'UPDATE databasechangeloglock SET locked = false, lockgranted = null, lockedby = null WHERE id = 1'
```

Deliberately *not* enabled: `spring.liquibase.test-rollback-on-update` (runs every changeset's
rollback then re-applies it — useful in CI, wasteful on every developer launch) and
`drop-first` (recreates the schema from scratch; one stray start against the wrong database
and the data is gone).

## Tests

```bash
./gradlew test
```

Integration tests spin up a real PostgreSQL container and run the migrations against it.
They are skipped automatically when no Docker daemon is reachable.

`OpenApiDocumentationTest` asserts that the contract is actually populated — springdoc
serves an empty-but-valid document when component scanning misfires, which is easy to miss
by eye.

The suite does **not** need Keycloak. `TestSecurityConfiguration` swaps in a stub `JwtDecoder`
that mints real `Jwt` objects with a `realm_access.roles` claim, so the role converter and
every authorization rule run exactly as in production — only the signature check is skipped.
`ApiSecurityIntegrationTest` covers the cases that fail *open* if a rule is wrong: anonymous
reads, garbage tokens, and a non-admin attempting a write.

## Project layout

```
src/main/java/com/stockmarketanalyzer/backend
├── BackendApplication.java
├── common/          # auditing base entity, problem-detail handler, page wrapper
├── config/          # CORS + OpenAPI properties and configuration
├── security/        # filter chain, Keycloak role converter, 401/403 problem details
├── instrument/      # domain, repository (+ specifications), service, web
├── price/           # domain, repository (+ statistics projection), service, web
├── wealth/          # per-user net-worth entries and exchange rates
├── dividend/        # per-user received dividends, valued from those same rates
└── inflation/       # shared annual inflation reference data
```

Docker assets live outside `src`:

```
docker.yaml
docker/keycloak/realm-stockmarket.json   # realm, clients, roles and seed users
```

## Things you may want next

* **Market data ingestion** — add an outbound `RestClient` for a provider (Alpha Vantage,
  Finnhub, Polygon) plus a scheduled job that upserts into `/prices`.
* **Typed clients** — the OpenAPI document feeds `openapi-generator` for a TypeScript or
  Java client, so a frontend never hand-writes request types.
* **Hardening Keycloak for real use** — swap `start-dev` for `start`, set `KC_HOSTNAME` and
  terminate TLS, replace every default credential, and consider whether the contract and
  Swagger UI should stay public (`OPENAPI_ENABLED=false`, `SWAGGER_UI_ENABLED=false`).
* **Per-user data** — the wealth slice already keys its rows on the Keycloak subject (`sub`)
  without a local user table. If preferences or a display name are ever needed, add an
  `app_user` table on the same key and provision a row on first authenticated request.
* **Wealth analytics** — the derived columns of the original spreadsheet (each node's share of
  total wealth, and the change against the previous snapshot) are not computed server-side yet;
  a `/wealth/summary` endpoint that walks the parent tree is the natural next step.
* **Docker image** — `./gradlew bootBuildImage` produces an OCI image via Buildpacks;
  add the app as a service in `docker.yaml` when you want the whole stack containerised.
