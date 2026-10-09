#!/usr/bin/env sh
set -e

# Deploys countriesinfo to minikube, kind, or any kubectl-reachable cluster.
#
# Usage:
#   ./k8s-deploy.sh                 # auto-detect platform, local defaults
#   ./k8s-deploy.sh --render        # print the rendered manifest, no changes
#   ./k8s-deploy.sh --delete        # remove the application
#   DB_HOST=10.0.0.5 ./k8s-deploy.sh                       # custom MySQL
#   REGISTRY=your-registry.io/app ./k8s-deploy.sh          # remote cluster: push
#   DB_USERNAME=root DB_PASSWORD=secret ./k8s-deploy.sh    # custom credentials

IMAGE=${IMAGE:-countriesinfo:latest}
SERVICE_NAME=${SERVICE_NAME:-countriesinfo}
DB_USERNAME=${DB_USERNAME:-root}
DB_PASSWORD=${DB_PASSWORD:-development}
REGISTRY=${REGISTRY:-}

MODE=deploy
for arg in "$@"; do
  case "$arg" in
    --render) MODE=render ;;
    --delete) MODE=delete ;;
    *) echo "unknown argument: $arg" >&2; exit 1 ;;
  esac
done

# Auto-detect the cluster platform from the current context.
CONTEXT=$(kubectl config current-context 2>/dev/null || true)
case "$CONTEXT" in
  minikube*) PLATFORM=minikube ;;
  kind-*)    PLATFORM=kind ;;
  *)         PLATFORM=kubectl ;;
esac

# Default MySQL address per platform.
if [ -z "${DB_HOST:-}" ]; then
  case "$PLATFORM" in
    minikube) DB_HOST=host.minikube.internal ;;
    kind)     DB_HOST=host.docker.internal ;;
    *)        DB_HOST=localhost ;;
  esac
fi

if [ "$MODE" = delete ]; then
  kubectl delete -f k8s.yaml
  kubectl delete namespace countriesinfo --ignore-not-found
  echo "countriesinfo removed from $PLATFORM"
  exit 0
fi

render() {
  sed \
    -e "s|__SERVICE_NAME__|$SERVICE_NAME|" \
    -e "s|__IMAGE__|$IMAGE|" \
    -e "s|__DB_HOST__|$DB_HOST|" \
    -e "s|__DB_USERNAME__|$DB_USERNAME|" \
    -e "s|__DB_PASSWORD__|$DB_PASSWORD|" \
    k8s.yaml
}

if [ "$MODE" = render ]; then
  render
  exit 0
fi

docker build -t "$IMAGE" .

# Make the image available to the cluster.
case "$PLATFORM" in
  minikube) minikube image load "$IMAGE" ;;
  kind)     kind load docker-image "$IMAGE" ;;
  kubectl)
    if [ -n "$REGISTRY" ]; then
      docker tag "$IMAGE" "$REGISTRY/$IMAGE"
      docker push "$REGISTRY/$IMAGE"
      IMAGE="$REGISTRY/$IMAGE"
    fi
    ;;
esac

render | kubectl apply -f -

kubectl -n countriesinfo rollout status deployment/$SERVICE_NAME
echo "countriesinfo deployed on $PLATFORM (image=$IMAGE, db=$DB_HOST)"
