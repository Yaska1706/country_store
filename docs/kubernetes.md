# Kubernetes Guide

**countriesInfo** deploys as a single manifest, [k8s.yaml](../k8s.yaml),
rendered and applied by one script, [k8s-deploy.sh](../k8s-deploy.sh). The
manifest contains the namespace, ConfigMap (non-secret configuration),
Secret (credentials), Deployment (with `/ready` readiness and `/health`
liveness probes plus resource requests/limits), and a NodePort service
(port `8000`, node port `30080`).

## Prerequisites

- A Kubernetes cluster: minikube, kind, Docker Desktop, or any
  kubectl-reachable cluster.
- `kubectl` configured against that cluster.
- A MySQL instance reachable from the cluster (see [configuration](#configuration-management)).

## Configuration management

The app reads everything from environment variables (the same contract as
local/Compose — see the README [configuration table](../README.md#configuration)):

- **ConfigMap** — `PORT`, `SYNC_INTERVAL`, `CACHE_TTL`, `VACUUM_INTERVAL`,
  `VACUUM_RETENTION`, `DB_HOST`, `DB_PORT`, `DB_NAME`, and pool sizes.
- **Secret** — `DB_USERNAME`, `DB_PASSWORD`.

The manifest uses `__SERVICE_NAME__`, `__DB_HOST__`, `__DB_USERNAME__`,
`__DB_PASSWORD__`, and `__IMAGE__` placeholders; the deploy script renders
them (`SERVICE_NAME` defaults to `countriesinfo`). Credentials come from
`DB_USERNAME` / `DB_PASSWORD` environment variables and are never
committed. For production, I will prefer Sealed Secrets or External Secrets.

Inspect what would be applied without touching the cluster:

```sh
./k8s-deploy.sh --render
```

### Deployment spec (Java-tuned)

| Field | Value | Why |
| ----- | ----- | --- |
| `replicas` | `1` | Start small; scale out with `kubectl scale` |
| `strategy` | `RollingUpdate`, `maxSurge`/`maxUnavailable` 25% | Zero-downtime updates |
| `progressDeadlineSeconds` | `600` | A rollout that stalls is marked failed after 10 min |
| `terminationGracePeriodSeconds` | `120` | Room for Spring Boot's graceful shutdown (`server.shutdown: graceful`, 30 s phase timeout) plus drain time |
| Liveness probe | `GET /health`, port `8000`, `initialDelay 90s`, `period 10s`, `timeout 1s`, `failureThreshold 3` | A Spring Boot JVM needs 45–90 s to start; the delay prevents the probe from killing it mid-startup |
| Readiness probe | `GET /ready`, port `8000`, `initialDelay 60s`, `period 10s`, `timeout 3s`, `failureThreshold 3` | `/ready` pings the database, so a pod with a broken DB connection leaves the load balancer |
| Resources | requests `100m`/`256Mi`, limits `500m`/`512Mi` | JVM startup is CPU-hungry — a 200m cap throttles boot enough to trip the liveness probe; the heap is sized via `JAVA_OPTS=-XX:MaxRAMPercentage=75.0` |
| `imagePullPolicy` | `IfNotPresent` | Local images are loaded by the deploy script |


## Deploying

`k8s-deploy.sh` auto-detects the platform from the current kubectl context
(`minikube*`, `kind-*`, anything else = plain kubectl):

| Platform      | Detection | Image delivery | Default `DB_HOST` |
| ------------- | --------- | -------------- | ----------------- |
| minikube      | context `minikube*` | `minikube image load` | `host.minikube.internal` |
| kind          | context `kind-*` | `kind load docker-image` | `host.docker.internal` |
| remote cluster | anything else | push via `REGISTRY=…` | `localhost` (override with `DB_HOST`) |

```sh
# minikube
minikube start
./k8s-deploy.sh

# kind
kind create cluster
./k8s-deploy.sh

# remote cluster (GKE/EKS/…): push to its registry and point at reachable MySQL
REGISTRY=your-registry.io/app DB_HOST=10.0.0.5 ./k8s-deploy.sh
```

## Verify

```sh
kubectl -n countriesinfo get pods
kubectl -n countriesinfo logs deployment/countriesinfo
kubectl -n countriesinfo get svc

# From inside the cluster network:
kubectl -n countriesinfo port-forward svc/countriesinfo 8000:8000 &
curl localhost:8000/health
curl "localhost:8000/countries?page=1&limit=5"

# Or through the NodePort (default 30080):
curl localhost:30080/health
```

> **kind on Docker Desktop** does not map NodePorts to the host by default,
> so `localhost:30080` may refuse connections even though the pod is
> healthy. Use `kubectl port-forward` (above), or create the cluster with
> the port mapped:
>
> ```sh
> cat <<'EOF' | kind create cluster --config -
> kind: Cluster
> nodes:
>   - role: control-plane
>     extraPortMappings:
>       - containerPort: 30080
>         hostPort: 30080
> EOF
> ```

> **MySQL must be reachable before deploying.** The pod fails fast and
> crash-loops when the database is unreachable at startup. For Docker
> Desktop, `docker compose up -d mysql` (see [docs/docker.md](docker.md))
> publishes `3306` on the host and is reachable from pods via
> `host.docker.internal`.

## Operations

**Scale out**:

```sh
kubectl -n countriesinfo scale deployment/countriesinfo --replicas=3
```

Each replica runs its own sync and vacuum, which are idempotent by design —
see the README's [trade-offs](../README.md#architecture-trade-offs-and-limitations).

**Rolling update**:

```sh
docker build -t countriesinfo:1.0.1
kubectl -n countriesinfo set image deployment/countriesinfo \
  countriesinfo=countriesinfo:1.0.1
kubectl -n countriesinfo rollout status deployment/countriesinfo
```

**Teardown**:

```sh
./k8s-deploy.sh --delete
```

## Troubleshooting

| Symptom | Likely cause and fix |
| ------- | -------------------- |
| `ImagePullBackOff` | Image not available to the cluster — run the deploy script so it loads/pushes the image |
| `CrashLoopBackOff` (exit code 1, logs stop before Hikari/Flyway) | Database unreachable — MySQL is down or `DB_HOST` is wrong. Check `kubectl -n countriesinfo logs deployment/countriesinfo \| grep -i mysql` and test from a pod: `kubectl -n countriesinfo run dbg --rm -it --restart=Never --image=busybox:1.36 -- nc -z -w 3 <DB_HOST> 3306` |
| Pod restarts during startup | JVM slow under the memory limit — raise the limit or the liveness `initialDelaySeconds` |
| Ready is `false` (`/ready` 503) | Database connection failing — check MySQL reachability and `kubectl logs` |
| Flyway migration errors | Schema drift — `kubectl -n countriesinfo logs deployment/countriesinfo \| grep -i flyway` |

Next: [Docker Guide](docker.md) · [README](../README.md)
