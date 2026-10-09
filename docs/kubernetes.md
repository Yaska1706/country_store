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


Inspect what would be applied without touching the cluster:

```sh
./k8s-deploy.sh --render
```

### Deployment spec

The manifest keeps only non-default fields; defaults (rolling update
strategy, 1 replica, 10 min progress deadline, `scheme: HTTP`,
`successThreshold: 1`) are inherited from Kubernetes. The one exception is
`imagePullPolicy: IfNotPresent`, set explicitly because images tagged
`:latest` would otherwise default to `Always` and try to pull from a
registry.

| Field | Value | Why |
| ----- | ----- | --- |
| `strategy.rollingUpdate` | `maxSurge`/`maxUnavailable` 25% | Zero-downtime updates |
| `imagePullPolicy` | `IfNotPresent` | Local clusters use the image loaded by the deploy script instead of pulling `:latest` from a registry |
| `terminationGracePeriodSeconds` | `120` | Room for Spring Boot's graceful shutdown (`server.shutdown: graceful`, 30 s phase timeout) plus drain time |
| Liveness probe | `GET /health`, port `http`, `initialDelay 90s` | A Spring Boot JVM needs 45–90 s to start; the delay prevents the probe from killing it mid-startup |
| Readiness probe | `GET /ready`, port `http`, `initialDelay 60s`, `timeout 3s` | `/ready` pings the database, so a pod with a broken DB connection leaves the load balancer |
| Resources | requests `100m`/`256Mi`, limits `500m`/`512Mi` | JVM startup is CPU-hungry — a 200m cap throttles boot enough to trip the liveness probe; the heap is sized via `JAVA_OPTS=-XX:MaxRAMPercentage=75.0` |


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
kubectl -n countriesinfo get svc

# From inside the cluster network:
kubectl -n countriesinfo port-forward svc/countriesinfo 8000:8000 &
curl localhost:8000/health
curl "localhost:8000/countries?page=1&limit=5"

# Or through the NodePort (default 30080):
curl localhost:30080/health
```

## Logs

With the `k8s` profile the pod writes **structured JSON to stdout**, one
object per line. Follow and filter with `kubectl logs` + `jq`:

```sh
kubectl -n countriesinfo logs deployment/countriesinfo -f
kubectl -n countriesinfo logs deployment/countriesinfo | jq 'select(.level == "ERROR")'
kubectl -n countriesinfo logs deployment/countriesinfo | jq -r 'select(.requestId != null) | [.timestamp, .level, .requestId, .status] | @tsv'
```

Liveness/readiness probe hits are silent while healthy, so the log stream
shows real traffic and failures only. The root level is controlled by
`LOG_LEVEL` in the ConfigMap.

Log retention is handled by the node's container runtime (kubelet) and its
log rotation policy — the app writes no log files. Pod logs disappear when
the pod is deleted; for durable, cluster-wide storage add a log aggregation
stack (e.g. Loki) as a future improvement.

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
