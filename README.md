# countriesInfo

**countriesInfo** is a REST service that maintains a catalog of countries
and their languages. It hydrates its database from an upstream SOAP web
service, stores the data in MySQL, and exposes it through a paginated,
cache-backed API with id-keyed CRUD, soft deletes, and a retention policy
for deleted records.

## Features

- **Upstream integration** — fetches all countries (and single-country
  lookups) from the [oorsprong.org CountryInfoService](http://webservices.oorsprong.org/websamples.countryinfo/CountryInfoService.wso?WSDL)
  with retries, exponential backoff, and a circuit breaker.
- **Background sync** — runs on startup and then every `SYNC_INTERVAL`;
  upserts countries by ISO code and records an audit trail in `sync_logs`.
- **REST API** — list with pagination, get/update/delete by id, and
  name-based lookup.
- **Soft delete + vacuum** — `DELETE` marks rows with `deleted_at`; a
  scheduled vacuum hard-deletes them (languages cascade) after a
  configurable retention period (7 days by default).
- **Snapshot cache** — the full country list is served from an in-memory
  TTL cache; detail reads never touch the upstream once data is stored.
- **Observability** — structured request logs and Prometheus metrics
  (`/metrics`), plus Spring Boot Actuator health endpoints.
- **Container-ready** — Docker image, Docker Compose stack, and a
  single-file Kubernetes deployment.

## Logging

All logs go to **stdout** — containers never write log files.

| Environment | Format |
| ----------- | ------ |
| Local (default profile) | Colorized, human-readable console output |
| Docker (`docker` profile) | Structured JSON, one object per line |
| Kubernetes (`k8s` profile) | Structured JSON, one object per line |

Every request line carries a correlation id and request attributes
(`requestId`, `method`, `route`, `ip`), which appear as top-level fields in
the JSON format via MDC. Liveness/readiness probe hits to `/health`,
`/ready`, and `/metrics` are not logged while they succeed, so container
logs stay readable (failures are still logged). The root level is controlled
by `LOG_LEVEL` (default `INFO`).

Viewing and parsing container logs:

```sh
docker logs -f countriesinfo
docker logs countriesinfo | jq 'select(.level == "ERROR")'

kubectl -n countriesinfo logs deployment/countriesinfo -f
kubectl -n countriesinfo logs deployment/countriesinfo | jq -r 'select(.requestId != null) | [.timestamp, .level, .message] | @tsv'
```

## External dependencies

| Dependency | Purpose | Failure behavior |
| ---------- | ------- | ---------------- |
| MySQL 8+ | Persistence (`country_infos`, `languages`, `sync_logs`) | Startup fails fast; at runtime `/ready` returns 503 and reads return 500 |
| Upstream SOAP service (oorsprong.org) | Source of truth for country data and name → ISO resolution | 3 retries with backoff, then the circuit breaker opens for 30 s; requests fail fast instead of hanging |
| Prometheus (optional) | Scrapes `/metrics` | No impact on the app |
| Docker / Kubernetes (optional) | Containerized deployment | N/A |

There are no other external services: configuration comes from environment
variables and the schema is managed by Flyway on startup.

## Architecture

```
HTTP clients
     │
     ▼
┌──────────────┐   ┌────────────┐   ┌─────────────────┐
│ LoggingFilter│──▶│ Country    │──▶│  CountryService │
│ (log+metrics)│   │ Controller │   │ (business logic)│
└──────────────┘   └────────────┘   └───────┬─────────┘
                                  ┌────────┼──────────────┐
                                  ▼        ▼              ▼
                            ┌───────────┐ ┌────────────┐ ┌──────────┐
                            │CountryRepo│ │CountryStore│ │ SoapClient│──▶ SOAP API
                            │ (JPA read)│ │ (JDBC write│ │ (retry+   │
                            └───────────┘ │  upsert)   │ │  breaker) │
                                          └────────────┘ └──────────┘
                     ┌──────────────────────────────┐
                     │ CountrySyncer + CountryVacuum│  background jobs
                     └──────────────────────────────┘
```

| Layer        | Package                            | Responsibility                              |
| ------------ | ---------------------------------- | ------------------------------------------- |
| Controller   | `controller`                       | Parse/validate HTTP input, map errors       |
| Business     | `service`, `sync`                  | Cache-aside reads, soft-delete writes, sync, vacuum |
| Persistence  | `repository`                       | JPA reads + native JDBC upserts, purge      |
| Infra        | `client`, `config`, `filter`, `exception` | SOAP client, cache, metrics, logging  |
| DTOs/models  | `dto`, `entity`, `converter`       | Wire shapes, mappings                       |

### Data model

The schema is managed by Flyway with a single migration,
[V1__init_schema.sql](src/main/resources/db/migration/V1__init_schema.sql);
Hibernate runs in `validate` mode:

- **`country_infos`** — surrogate auto-increment `id` primary key, unique
  `iso_code`, `deleted_at` timestamp for soft deletes.
- **`languages`** — composite key `(country_id, iso_code)` with a foreign
  key to `country_infos(id)` that cascades on delete.
- **`sync_logs`** — append-only audit log (no foreign key by design, so
  failed syncs are recorded even when the country row could not be written).

### Read and write paths

- **Reads**: `cache → MySQL`. The snapshot cache holds the full country
  list with a TTL; a miss reloads it from the database. Name lookup
  (`POST /country`) always calls the upstream. Soft-deleted rows are
  filtered from every query.
- **Writes**: `PUT` upserts via native SQL (`INSERT ... ON DUPLICATE KEY
  UPDATE`) and rewrites languages only when they changed, using affected
  rows to classify the outcome (insert / update / skip).
- **Soft delete**: `DELETE` stamps `deleted_at`; the row and its languages
  stay in the database until the vacuum purges them after the retention
  period. The sync never resurrects a soft-deleted country.

## API routes

All errors are JSON: `{"error": "human readable message"}`.

| Route | Method | Description |
| ----- | ------ | ----------- |
| `/health` | GET | 200 `"OK"` — liveness |
| `/ready` | GET | 200 `"OK"`, or 503 `{"error":"not ready"}` when the DB is unreachable — readiness |
| `/metrics` | GET | Prometheus text exposition |
| `/country` | POST | Body `{"name":"..."}` — trims and English title-cases the name, resolves it upstream. 400 empty name/invalid body, 404 unresolved, 500 upstream failure |
| `/countries` | GET | Paginated list; `?page=1&limit=20`, `limit ≤ 100`. 400 on invalid page/limit |
| `/countries/{id}` | GET | Detail by database id; 404 when absent or soft-deleted |
| `/countries/{id}` | PUT | Updates an active country; the path id wins over the body (`id` and `country_iso_code` are immutable). 400 empty name/invalid id, 404 unknown id |
| `/countries/{id}` | DELETE | Soft delete; 204 on success, 404 when absent or already deleted |

## Configuration

All runtime configuration comes from environment variables (12-factor
style). See [.env.example](.env.example) for the full set; [.env](.env) is
used for local runs.

| Variable | Default | Description |
| -------- | ------- | ----------- |
| `DB_HOST` | `127.0.0.1` | MySQL host |
| `DB_PORT` | `3306` | MySQL port |
| `DB_NAME` | `country_store` | Database name |
| `DB_USERNAME` | `root` | Database user |
| `DB_PASSWORD` | `development` | Database password |
| `DB_MAX_CONNS` | `25` | HikariCP maximum pool size |
| `DB_MIN_CONNS` | `5` | HikariCP minimum idle connections |
| `DB_MAX_CONN_TTL` | `3600000` | Connection max lifetime (ms) |
| `PORT` | `8000` | HTTP port |
| `SYNC_INTERVAL` | `86400000` | Background sync interval in ms (first run is immediate) |
| `CACHE_TTL` | `300` | Snapshot cache TTL in seconds |
| `VACUUM_INTERVAL` | `3600000` | Vacuum run interval in ms |
| `VACUUM_RETENTION` | `7d` | How long soft-deleted rows are kept before hard delete |

Non-environment settings — upstream SOAP URL, HTTP timeout, Flyway options,
and the resilience4j retry/circuit-breaker policy — live in
[application.yml](src/main/resources/application.yml).

## Running locally

**Prerequisites**: JDK 21+ (no local Maven required — the Maven Wrapper is
committed) and a running MySQL instance.

```sh
# 1. Create the database (schema is migrated automatically on startup)
mysql -e "CREATE DATABASE country_store CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 2. Configure and start
cp .env.example .env          # adjust credentials if needed
./mvnw spring-boot:run

# 3. Smoke test
curl localhost:8000/health                       # "OK"
curl "localhost:8000/countries?page=1&limit=5"
curl localhost:8000/countries/3
curl -X POST localhost:8000/country -H 'Content-Type: application/json' -d '{"name":"Kenya"}'
curl -X DELETE localhost:8000/countries/3        # soft delete
curl localhost:8000/metrics
```

Alternatively, run the whole stack (app + MySQL) with Docker Compose — see
[Deployment](#deployment).

### Tests

```sh
./mvnw test
```

The suite covers the cache-aside read path, id-keyed CRUD with soft delete,
the syncer's stats and sync-log recording, the vacuum retention, the SOAP
client's retries and circuit breaker, and pagination/validation.

## Deployment

Deployment is documented separately:

- **[Docker](docs/docker.md)** — multi-stage image on Eclipse Temurin
  (`21-jdk` build, `21-jre-alpine` runtime, non-root, healthcheck) and a
  Docker Compose stack. Quick start: `docker compose up -d --build`.
- **[Kubernetes](docs/kubernetes.md)** — single manifest, [k8s.yaml](k8s.yaml),
  deployed by [k8s-deploy.sh](k8s-deploy.sh), which auto-detects minikube,
  kind, or any kubectl cluster. Quick start: `./k8s-deploy.sh`.

Configuration is split by platform: `.env` locally / Compose, ConfigMap +
Secret on Kubernetes, all from the same environment-variable contract.

### CI/CD

GitHub Actions ([.github/workflows/ci.yml](.github/workflows/ci.yml)) runs
the full test suite on every push to `main` and every pull request, and
pre-builds the Docker image:

- **Pull requests** — tests + image build (no push) so regressions and
  broken builds are caught before merge.
- **Push to `main`** — tests, then the image is built and pushed to the
  GitHub Container Registry as `ghcr.io/Yaska1706/country_store` with
  `latest` and `sha-<commit>` tags.
- Buildx's GitHub Actions cache is reused across runs, so image builds stay
  fast.

## Architecture trade-offs and limitations

| Trade-off | Consequence |
| --------- | ----------- |
| In-memory snapshot cache per instance | Fast reads, but caches are not shared: every replica re-reads the DB once per TTL, and data can be stale for up to `CACHE_TTL` |
| Background jobs run inside every replica | The sync and vacuum are idempotent, so multiple replicas are safe — but work is duplicated and the jobs are not exactly-once |
| Hard dependency on MySQL | Startup fails without a database and `/ready` goes 503 at runtime; there is no degraded read-only mode |
| Upstream is the sole source for name lookup | `POST /country` depends on the SOAP service; when the breaker is open, lookups fail fast with an error |
| Full-list reloads | The snapshot cache stores the entire country set (~250 rows); fine at this scale, but the model does not scale to very large catalogs |
| Soft-deleted rows retained until vacuum | Deleted data stays in the table for the retention window (7 days), using space and requiring the vacuum job to run |
| Single Flyway migration + `validate` | Any schema change requires a new migration and a deploy; existing databases are baselined rather than migrated in place |
| No authentication | The API is meant to sit behind a gateway/ingress; there is no built-in authN/authZ |

## Future improvements

- **Stateless replicas** — move the snapshot cache to Redis and extract the
  sync and vacuum jobs into a separate scheduler service (or leader-elected
  job) so API replicas hold no state and run no duplicate work.
- **Horizontal autoscaling** — HPA on request rate/latency once the cache
  is shared.
- **Security** — add API keys or OIDC at the ingress, and sign published
  images (e.g. Cosign).
- **Data layer** — read replicas for MySQL and multi-step Flyway migrations
  for safer schema evolution.
- **Observability** — distributed tracing and structured log shipping.
