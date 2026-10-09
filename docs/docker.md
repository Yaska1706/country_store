# Docker Guide

This guide covers building and running the **countriesInfo** image, and the
Docker Compose stack that bundles it with MySQL. The image is a two-stage
build on [Eclipse Temurin](https://adoptium.net/): the Maven Wrapper
compiles the Spring Boot jar on `eclipse-temurin:21-jdk`, and the runtime
image is a minimal `eclipse-temurin:21-jre-alpine` running as a non-root
user.

## Prerequisites

- Docker Engine with BuildKit (any recent Docker Desktop or Docker CE).
- A MySQL instance reachable from the container, or use the bundled Compose
  stack (see [host addressing](#host-addressing)).

## Build

```sh
docker build -t countriesinfo:latest .
```

## Run against a host MySQL

```sh
docker run -d --name countriesinfo \
  -p 8000:8000 \
  --env-file .env \
  -e DB_HOST=host.docker.internal \
  countriesinfo:latest
```

Verify:

```sh
curl localhost:8000/health                 # "OK"
curl "localhost:8000/countries?page=1&limit=5"
docker logs -f countriesinfo
```

Stop and remove: `docker rm -f countriesinfo`.

## Docker Compose (self-contained stack)

`docker-compose.yml` starts MySQL 8.4 together with the application, with
the database persisted in a named volume:

```sh
docker compose up -d --build
curl localhost:8000/health
docker compose down                # stop, keep the database volume
docker compose down -v             # also delete the database volume
```

The app waits for MySQL to become healthy before starting; Flyway migrates
the schema on first boot. Credentials come from the same environment
variables as everywhere else — see the [configuration
table](../README.md#configuration) in the README.

## Host addressing

Containers cannot reach the host's MySQL via `127.0.0.1`:

| Environment     | `DB_HOST` to use                                                     |
| --------------- | -------------------------------------------------------------------- |
| Docker Desktop  | `host.docker.internal`                                               |
| Linux Docker    | `--add-host=host.docker.internal:host-gateway` or the host LAN IP    |
| minikube        | `host.minikube.internal`                                             |

## Image contents and tuning

- The container runs as user `app` and exposes port `8000`.
- A built-in `HEALTHCHECK` polls `/health` every 30 s.
- JVM flags go through `JAVA_OPTS`, e.g.:

```sh
docker run ... -e JAVA_OPTS="-Xms256m -Xmx256m" countriesinfo:latest
```

- Configuration is entirely environment-driven; nothing (including
  credentials) is baked into the image — `.env` is excluded by
  `.dockerignore`.

## Publishing to a registry

The GitHub Actions pipeline ([../.github/workflows/ci.yml](../.github/workflows/ci.yml))
already pushes the image to the GitHub Container Registry on every push to
`main` — `ghcr.io/Yaska1706/country_store` with `latest` and `sha-<commit>`
tags. Note that GHCR packages are **private by default**: make the package
public (or configure pull credentials) before a cluster pulls it.

Publishing elsewhere manually:

```sh
docker tag countriesinfo:latest your-registry.example.com/countriesinfo:1.0.0
docker push your-registry.example.com/countriesinfo:1.0.0
```

## Troubleshooting

| Symptom | Likely cause and fix |
| ------- | -------------------- |
| Container restarts with DB errors in the logs | MySQL unreachable or wrong credentials — check `DB_HOST` and `DB_PASSWORD` |
| `Bind for 0.0.0.0:8000 failed: address already in use` | Port 8000 is taken — run with `-p 9000:8000` |
| Healthcheck fails on slow hosts | Give the JVM more time to start (`HEALTHCHECK --start-period`) |

Next: [Kubernetes Guide](kubernetes.md) · [README](../README.md)
