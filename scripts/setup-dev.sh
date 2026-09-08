#!/usr/bin/env bash
# Prepare local development directories and patch hi-repository paths.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER="$ROOT/server"
REPO="$SERVER/hi-repository"
DB="$SERVER/db"
SETTING="$REPO/System/Admin/setting.xml"
GLOBAL_CONN="$REPO/System/Admin/globalConnections.xml"

# shellcheck source=setup-dev.helpers.sh
source "${SCRIPT_DIR}/setup-dev.helpers.sh"

# Invoke via bash so missing +x on a fresh checkout still works.
bash "${SCRIPT_DIR}/print-banner.sh"
echo ""
echo "Development setup"
echo "Repository root: $ROOT"

mkdir -p "$DB" "$REPO/System/Logs"

REPO_ABS="$(cd "$REPO" && pwd)"
DB_ABS="$(cd "$DB" && pwd)"

# Native (non-Docker) path patching is opt-in. Docker entrypoint / compose layouts
# substitute INSTALL_PATH themselves; leaving placeholders keeps the tree portable.
if [ "${NATIVE_HI_SETUP:-}" = "1" ]; then
  if [ -f "$SETTING" ]; then
    if grep -q '\${INSTALL_PATH}' "$SETTING"; then
      sed -i.bak "s|<efwSolution>.*</efwSolution>|<efwSolution>${REPO_ABS}</efwSolution>|" "$SETTING"
      sed -i.bak "s|<BaseUrl>.*</BaseUrl>|<BaseUrl>http://localhost:8080/hi-ee/</BaseUrl>|" "$SETTING"
      rm -f "${SETTING}.bak"
      echo "[OK]   Patched setting.xml for native host paths (NATIVE_HI_SETUP=1)"
    else
      echo "[SKIP] setting.xml already has absolute paths"
    fi
  fi

  if [ -f "$GLOBAL_CONN" ]; then
    if grep -q 'SampleTravelData' "$GLOBAL_CONN"; then
      sed -i.bak "s|<url>.*SampleTravelData</url>|<url>jdbc:derby:${DB_ABS}/SampleTravelData</url>|" "$GLOBAL_CONN"
      rm -f "${GLOBAL_CONN}.bak"
      echo "[OK]   Patched globalConnections.xml for native Derby path (NATIVE_HI_SETUP=1)"
    fi
  fi
else
  echo "[SKIP] Native path patching (set NATIVE_HI_SETUP=1 for host Tomcat installs)"
fi


if [ ! -f "$ROOT/.env" ] && [ -f "$ROOT/.env.example" ]; then
  cp "$ROOT/.env.example" "$ROOT/.env"
  echo "[OK]   Created .env from .env.example"
fi

DOCKER_ENV_EXAMPLE="$ROOT/docker/.env.example"
DOCKER_ENV="$ROOT/docker/.env"
if [ ! -f "$DOCKER_ENV" ] && [ -f "$DOCKER_ENV_EXAMPLE" ]; then
  cp "$DOCKER_ENV_EXAMPLE" "$DOCKER_ENV"
  echo "[OK]   Created docker/.env from docker/.env.example"
fi

# Link hi-repository into the shared Docker layout (same path the package uses).
# Use a path relative to docker/hi so the link is portable across machines.
HI_REPO_LINK="$ROOT/docker/hi/hi-repository"
mkdir -p "$ROOT/docker/hi"
if [ -d "$ROOT/server/hi-repository" ]; then
  ln -sfn ../../server/hi-repository "$HI_REPO_LINK"
  echo "[OK]   Linked docker/hi/hi-repository → ../../server/hi-repository"
fi

# Link hi-ee.war into the shared Docker layout (same path the package uses).
# presentation/target is absent before the first Maven build — do not fail under set -e.
HI_WAR_LINK="$ROOT/docker/hi/hi-ee.war"
HI_WAR_SRC=""
if [ -d "$ROOT/server/presentation/target" ]; then
  HI_WAR_SRC="$(find "$ROOT/server/presentation/target" -maxdepth 1 -name 'hi-ee-*.war' -type f 2>/dev/null | head -n 1 || true)"
fi
if [ -n "$HI_WAR_SRC" ]; then
  ln -sfn "../../server/presentation/target/$(basename "$HI_WAR_SRC")" "$HI_WAR_LINK"
  echo "[OK]   Linked docker/hi/hi-ee.war → $(basename "$HI_WAR_SRC")"
elif [ -e "$HI_WAR_LINK" ]; then
  echo "[SKIP] docker/hi/hi-ee.war already present"
else
  echo "[SKIP] hi-ee.war not linked yet (build server first, or use docker-compose.dev.yml --build)"
fi


# Link Instant BI into the shared Docker layout (compose mounts ./instantbi/com/helicalinsight/instantbi)
INSTANTBI_LINK="$ROOT/docker/instantbi/com/helicalinsight/instantbi"
INSTANTBI_SRC="$ROOT/instantbi/src/com/helicalinsight/instantbi"
mkdir -p "$ROOT/docker/instantbi/com/helicalinsight"
if [ -d "$INSTANTBI_SRC" ]; then
  ln -sfn ../../../../instantbi/src/com/helicalinsight/instantbi "$INSTANTBI_LINK"
  echo "[OK]   Linked docker/instantbi/.../instantbi → instantbi source (relative)"
fi

# Docker mounts ./hi/hi-repository/System/InstantBI → /app/helicalbi/config.
# YAML source of truth stays helicalbi/config; this link is for Compose only.
INSTANTBI_CONFIG_SRC="$INSTANTBI_SRC/helicalbi/config"
INSTANTBI_CONFIG_LINK="$ROOT/server/hi-repository/System/InstantBI"
if [ -d "$INSTANTBI_CONFIG_LINK" ] && [ ! -L "$INSTANTBI_CONFIG_LINK" ]; then
  rm -rf "$INSTANTBI_CONFIG_LINK"
fi
if [ -d "$INSTANTBI_CONFIG_SRC" ]; then
  # Relative from server/hi-repository/System to instantbi config
  ln -sfn ../../../../instantbi/src/com/helicalinsight/instantbi/helicalbi/config "$INSTANTBI_CONFIG_LINK"
  echo "[OK]   Linked hi-repository/System/InstantBI → helicalbi/config (relative)"
fi

# Remove leftover docker/config/instantbi from the previous layout
DOCKER_INSTANTBI_CONFIG="$ROOT/docker/config/instantbi"
if [ -L "$DOCKER_INSTANTBI_CONFIG" ]; then
  rm -f "$DOCKER_INSTANTBI_CONFIG"
  echo "[OK]   Removed leftover docker/config/instantbi symlink"
elif [ -d "$DOCKER_INSTANTBI_CONFIG" ] && [ -z "$(ls -A "$DOCKER_INSTANTBI_CONFIG" 2>/dev/null)" ]; then
  rmdir "$DOCKER_INSTANTBI_CONFIG"
  echo "[OK]   Removed leftover empty docker/config/instantbi"
fi

echo ""
echo "Setup complete. See README.md and docs/mongodb-setup.md."
echo ""
echo "Recommended reviewer path (builds React + backend from this checkout):"
echo "  bash scripts/setup-dev.sh"
echo "  docker compose -f docker-compose.dev.yml up --build"
echo "  # Open http://localhost:8080/hi-ee/  (login: hiadmin / hiadmin)"
echo "  # Mongo hostname inside that Compose network: mongodb"
echo ""
echo "Optional: package layout under docker/ (needs a prior WAR build):"
echo "  cd docker && docker compose up -d"
echo ""
echo "MongoDB-only demo DB:"
echo "  cd docker/mongodb && docker compose up -d"
echo ""
echo "Per component:"
echo "  Himongo:    bash scripts/build-himongo-jdbc.sh"
echo "  Frontend:   cd client && npm ci --legacy-peer-deps && npm run build18"
echo "              bash scripts/copy-frontend-to-webapp.sh"
echo "  Backend:    cd server && mvn clean package -DskipTests"
echo ""
