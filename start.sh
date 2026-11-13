#!/usr/bin/env bash
# WorkDSH Admin local launcher.
#
# Usage:
#   ./start.sh                 build if needed, run in the foreground
#   ./start.sh --daemon        build if needed, run in the background
#   ./start.sh --stop          stop the background instance
#   ./start.sh --status        show background instance status
#   ./start.sh --logs          follow the background log
#   ./start.sh --rebuild       force a backend + frontend rebuild
#   ./start.sh --port 18890    change the listening port
#   ./start.sh --bind 0.0.0.0  listen on all interfaces
#   ./start.sh --tailscale     listen on all interfaces and allow localhost + Tailscale
#
# The first run downloads a local JDK 17 and Maven into .tools/ when the
# machine has no suitable toolchain (no root required), then creates .env with
# a random bootstrap administrator password and the required server secrets.
# .env is chmod 600 and git-ignored. Editing it later does not reset an
# already-initialised database.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$ROOT/.tools"
RUN_DIR="$ROOT/.run"
ENV_FILE="$ROOT/.env"
LOG_FILE="$RUN_DIR/admin.log"
PID_FILE="$RUN_DIR/admin.pid"
WEB_DIR="$ROOT/web"
SERVER_DIR="$ROOT/server"
JAR="$SERVER_DIR/target/workdsh-admin-server-0.1.0-SNAPSHOT.jar"
JDK_VERSION="17"
MAVEN_VERSION="3.9.16"

PORT="${PORT:-18890}"
BIND_ADDRESS="${BIND_ADDRESS:-127.0.0.1}"
PORT_FROM_CLI=""
BIND_FROM_CLI=""
MODE="foreground"
FORCE_REBUILD="false"
ACTION="start"

log()  { printf '[workdsh-admin] %s\n' "$*"; }
warn() { printf '[workdsh-admin] WARN: %s\n' "$*" >&2; }
die()  { printf '[workdsh-admin] ERROR: %s\n' "$*" >&2; exit 1; }

usage() {
    awk 'NR > 1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        -d|--daemon)      MODE="daemon" ;;
        -f|--foreground)  MODE="foreground" ;;
        --stop)           ACTION="stop" ;;
        --status)         ACTION="status" ;;
        --logs)           ACTION="logs" ;;
        --rebuild)        FORCE_REBUILD="true" ;;
        --port)           [ $# -ge 2 ] || die "--port needs a value"; PORT="$2"; PORT_FROM_CLI="$2"; shift ;;
        --port=*)         PORT="${1#*=}"; PORT_FROM_CLI="$PORT" ;;
        --bind)           [ $# -ge 2 ] || die "--bind needs a value"; BIND_ADDRESS="$2"; BIND_FROM_CLI="$2"; shift ;;
        --bind=*)         BIND_ADDRESS="${1#*=}"; BIND_FROM_CLI="$BIND_ADDRESS" ;;
        --tailscale)      BIND_ADDRESS="0.0.0.0"; BIND_FROM_CLI="0.0.0.0" ;;
        -h|--help)        usage; exit 0 ;;
        *)                die "unknown option: $1 (try --help)" ;;
    esac
    shift
done

[[ "$PORT" =~ ^[0-9]+$ ]] || die "port must be numeric: $PORT"

# ---------------------------------------------------------------- toolchain

java_major() {
    "$1" -version 2>&1 | awk -F'"' '/version/ {print $2}' \
        | awk -F. '{print ($1 == "1") ? $2 : $1}'
}

find_java() {
    if [ -x "$TOOLS_DIR/jdk/bin/java" ]; then
        printf '%s\n' "$TOOLS_DIR/jdk/bin/java"
    elif command -v java >/dev/null 2>&1; then
        command -v java
    fi
}

find_node() {
    if command -v node >/dev/null 2>&1; then
        command -v node
        return 0
    fi
    local candidate
    for candidate in \
        "$HOME/.nvm/versions/node"/*/bin/node \
        /home/*/.nvm/versions/node/*/bin/node \
        "$HOME/.local/share/fnm/node-versions"/*/installation/bin/node \
        /usr/local/bin/node \
        /usr/bin/node \
        /opt/node/bin/node; do
        [ -x "$candidate" ] || continue
        printf '%s\n' "$candidate"
        return 0
    done
    return 1
}

install_jdk() {
    local arch os url tmp extract
    case "$(uname -m)" in
        x86_64|amd64)  arch="x64" ;;
        aarch64|arm64) arch="aarch64" ;;
        *) die "unsupported CPU architecture: $(uname -m)" ;;
    esac
    case "$(uname -s)" in
        Linux)  os="linux" ;;
        Darwin) os="mac" ;;
        *) die "unsupported OS: $(uname -s)" ;;
    esac
    command -v curl >/dev/null 2>&1 || die "curl is required to download a JDK"
    command -v tar  >/dev/null 2>&1 || die "tar is required to unpack a JDK"

    mkdir -p "$TOOLS_DIR"
    url="https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/${os}/${arch}/jdk/hotspot/normal/eclipse"
    log "No Java ${JDK_VERSION}+ found; downloading Temurin ${JDK_VERSION} into .tools/ (one time) ..."
    tmp="$(mktemp "$TOOLS_DIR/jdk.XXXXXX.tar.gz")"
    curl -fL --retry 3 --connect-timeout 20 -o "$tmp" "$url" \
        || { rm -f "$tmp"; die "failed to download JDK from $url"; }
    extract="$(mktemp -d "$TOOLS_DIR/jdk.XXXXXX")"
    tar -xzf "$tmp" -C "$extract" || { rm -rf "$extract" "$tmp"; die "failed to unpack JDK"; }
    rm -f "$tmp"
    local dir
    dir="$(find "$extract" -mindepth 1 -maxdepth 1 -type d -name 'jdk*' | head -1)"
    [ -n "$dir" ] || { rm -rf "$extract"; die "downloaded JDK archive had an unexpected layout"; }
    rm -rf "$TOOLS_DIR"/jdk-* "$TOOLS_DIR/jdk"
    mv "$dir" "$TOOLS_DIR/$(basename "$dir")"
    rm -rf "$extract"
    ln -sfn "$(basename "$dir")" "$TOOLS_DIR/jdk"
}

find_maven() {
    if [ -x "$TOOLS_DIR/maven/bin/mvn" ]; then
        printf '%s\n' "$TOOLS_DIR/maven/bin/mvn"
    elif command -v mvn >/dev/null 2>&1; then
        command -v mvn
    fi
}

install_maven() {
    command -v curl >/dev/null 2>&1 || die "curl is required to download Maven"
    mkdir -p "$TOOLS_DIR"
    local url tmp
    url="https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
    log "No Maven found; downloading Maven ${MAVEN_VERSION} into .tools/ (one time) ..."
    tmp="$(mktemp "$TOOLS_DIR/maven.XXXXXX.tar.gz")"
    curl -fL --retry 3 --connect-timeout 20 -o "$tmp" "$url" \
        || curl -fL --retry 3 --connect-timeout 20 -o "$tmp" \
             "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
        || { rm -f "$tmp"; die "failed to download Maven"; }
    tar -xzf "$tmp" -C "$TOOLS_DIR" || { rm -f "$tmp"; die "failed to unpack Maven"; }
    rm -f "$tmp"
    ln -sfn "apache-maven-${MAVEN_VERSION}" "$TOOLS_DIR/maven"
}

ensure_toolchain() {
    local java mvn major
    if [ "$(id -u)" -eq 0 ]; then
        warn "Running as root; prefer the project owner (e.g. sudo -u $(stat -c %U "$ROOT") ./start.sh ...) to avoid root-owned files."
    fi
    java="$(find_java || true)"
    if [ -z "$java" ] || [ "$(java_major "$java")" -lt "$JDK_VERSION" ]; then
        [ -n "$java" ] && log "Existing Java at $java is older than ${JDK_VERSION}; using a local JDK instead."
        install_jdk
        java="$TOOLS_DIR/jdk/bin/java"
    fi
    local resolved
    resolved="$(readlink -f "$java" 2>/dev/null || printf '%s' "$java")"
    export JAVA_HOME="$(cd "$(dirname "$resolved")/.." && pwd)"
    export PATH="$JAVA_HOME/bin:$PATH"
    export JAVA_BIN="$java"

    mvn="$(find_maven || true)"
    if [ -z "$mvn" ]; then
        install_maven
        mvn="$TOOLS_DIR/maven/bin/mvn"
    fi
    export MVN="$mvn"

    local node_bin npm_bin
    node_bin="$(find_node || true)"
    [ -n "$node_bin" ] || die "Node.js 22.19+ (or 24+) is required. Install Node, or run as the user who installed it."
    export PATH="$(dirname "$node_bin"):$PATH"
    export NODE_BIN="$node_bin"
    npm_bin="$(command -v npm || true)"
    [ -n "$npm_bin" ] || die "npm is required (expected next to $node_bin)."
    local node_major node_minor
    node_major="$(node -p 'process.versions.node.split(".")[0]')"
    node_minor="$(node -p 'process.versions.node.split(".")[1]')"
    if [ "$node_major" -lt 22 ] || { [ "$node_major" -eq 22 ] && [ "$node_minor" -lt 19 ]; }; then
        die "Node.js $(node -v) is too old; this project needs 22.19+ or 24+."
    fi

    log "Java:  $("$java" -version 2>&1 | head -1)"
    log "Maven: $("$mvn" -version 2>&1 | head -1)"
    log "Node:  $(node -v)"
}

# --------------------------------------------------------------- local env

random_password() {
    # 20 URL-safe alphanumerics; always clears the 12-character minimum.
    openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-20
}

create_env() {
    local email password enc_key svc_key
    command -v openssl >/dev/null 2>&1 || die "openssl is required to generate server secrets"
    email="${WORKDSH_BOOTSTRAP_ADMIN_EMAIL:-admin@workdsh.local}"
    password="${WORKDSH_BOOTSTRAP_ADMIN_PASSWORD:-$(random_password)}"
    enc_key="$(openssl rand -base64 32 | tr -d '\n')"
    svc_key="$(openssl rand -base64 48 | tr -d '\n')"

    umask 077
    cat > "$ENV_FILE" <<EOF
# WorkDSH Admin local settings - generated by start.sh on $(date -Iseconds)
# Keep this file private. It is git-ignored.
WORKDSH_BOOTSTRAP_ADMIN_EMAIL="$email"
WORKDSH_BOOTSTRAP_ADMIN_PASSWORD="$password"
WORKDSH_ORGANIZATION_NAME="${WORKDSH_ORGANIZATION_NAME:-WorkDSH 企业}"
WORKDSH_SECRETS_ENCRYPTION_KEY="$enc_key"
WORKDSH_SECRETS_SERVICE_KEY="$svc_key"
JDBC_URL="jdbc:h2:file:$SERVER_DIR/data/workdsh-admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
EOF
    chmod 600 "$ENV_FILE"
    log "Created $ENV_FILE (mode 600) with a generated bootstrap administrator."
    printf '\n  Login URL: http://127.0.0.1:%s/\n  Email:     %s\n  Password:  %s\n\n' "$PORT" "$email" "$password"
    log "The password is saved in .env. It is only applied when the database is first initialised."
}

load_env() {
    [ -f "$ENV_FILE" ] || create_env
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
}

# ---------------------------------------------------------------- building

needs_npm_install() {
    [ -d "$WEB_DIR/node_modules" ] || return 0
    [ ! -f "$WEB_DIR/node_modules/.package-lock.json" ] && return 0
    [ "$WEB_DIR/package-lock.json" -nt "$WEB_DIR/node_modules/.package-lock.json" ] && return 0
    [ "$WEB_DIR/package.json" -nt "$WEB_DIR/node_modules/.package-lock.json" ] && return 0
    return 1
}

needs_web_build() {
    [ -f "$WEB_DIR/dist/index.html" ] || return 0
    [ -n "$(find "$WEB_DIR/src" "$WEB_DIR/index.html" "$WEB_DIR/package.json" \
        "$WEB_DIR/package-lock.json" "$WEB_DIR/vite.config.js" \
        -newer "$WEB_DIR/dist/index.html" -print -quit 2>/dev/null)" ]
}

needs_server_build() {
    [ -f "$JAR" ] || return 0
    [ -n "$(find "$SERVER_DIR/src" "$SERVER_DIR/pom.xml" \
        -newer "$JAR" -print -quit 2>/dev/null)" ]
}

build_all() {
    local npm_bin
    npm_bin="$(command -v npm)"

    if [ "$FORCE_REBUILD" = "true" ] || needs_npm_install; then
        log "Installing frontend dependencies (npm ci) ..."
        (cd "$WEB_DIR" && "$npm_bin" ci --no-audit --no-fund)
    fi

    if [ "$FORCE_REBUILD" = "true" ] || needs_web_build; then
        log "Building admin web ..."
        (cd "$WEB_DIR" && "$npm_bin" run build)
    else
        log "Admin web is up to date."
    fi

    if [ "$FORCE_REBUILD" = "true" ] || needs_server_build; then
        log "Building backend (Maven) ..."
        # ~/.m2/settings.xml may pin an old compiler level; force Java 17 here.
        (cd "$ROOT" && "$MVN" -B -f server/pom.xml -DskipTests \
            -Dmaven.compiler.source="$JDK_VERSION" \
            -Dmaven.compiler.target="$JDK_VERSION" \
            -Dmaven.compiler.release="$JDK_VERSION" \
            -Dmaven.compiler.compilerVersion="$JDK_VERSION" \
            package)
    else
        log "Backend JAR is up to date."
    fi
    [ -f "$JAR" ] || die "build finished but $JAR is missing"
}

# ----------------------------------------------------------------- running

tailscale_dns() {
    command -v tailscale >/dev/null 2>&1 || return 0
    tailscale status --json 2>/dev/null | python3 -c 'import json, sys
try:
    print((json.load(sys.stdin).get("Self", {}).get("DNSName") or "").rstrip("."))
except Exception:
    pass' 2>/dev/null || true
}

web_origins() {
    if [ -n "${WORKDSH_ADMIN_WEB_ORIGIN:-}" ]; then
        printf '%s\n' "$WORKDSH_ADMIN_WEB_ORIGIN"
        return
    fi
    local origins=("http://127.0.0.1:$PORT" "http://localhost:$PORT")
    if command -v tailscale >/dev/null 2>&1; then
        local ts_ip ts_ip6 ts_dns
        ts_ip="$(tailscale ip -4 2>/dev/null | head -1 || true)"
        ts_ip6="$(tailscale ip -6 2>/dev/null | head -1 || true)"
        ts_dns="$(tailscale_dns)"
        [ -n "$ts_ip" ] && origins+=("http://$ts_ip:$PORT")
        [ -n "$ts_ip6" ] && origins+=("http://[$ts_ip6]:$PORT")
        [ -n "$ts_dns" ] && origins+=("http://$ts_dns:$PORT" "https://$ts_dns")
    fi
    local IFS=,
    printf '%s\n' "${origins[*]}"
}

health_host() {
    case "$BIND_ADDRESS" in
        0.0.0.0|::|"") printf '127.0.0.1\n' ;;
        *)             printf '%s\n' "$BIND_ADDRESS" ;;
    esac
}

prepare_runtime_env() {
    mkdir -p "$SERVER_DIR/data" "$RUN_DIR"
    WORKDSH_ADMIN_WEB_ORIGIN="$(web_origins)"
    export PORT BIND_ADDRESS WORKDSH_ADMIN_WEB_ORIGIN
    log "Allowed web origins: $WORKDSH_ADMIN_WEB_ORIGIN"
}

log_access_urls() {
    log "  URL:  http://$(health_host):$PORT/"
    if command -v tailscale >/dev/null 2>&1; then
        local ts_dns
        ts_dns="$(tailscale_dns)"
        [ -n "$ts_dns" ] && log "  Tailscale: http://$ts_dns:$PORT/"
    fi
}

is_running() {
    [ -f "$PID_FILE" ] || return 1
    local pid
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

check_port_free() {
    if command -v lsof >/dev/null 2>&1 && lsof -ti "tcp:$PORT" >/dev/null 2>&1; then
        die "port $PORT is already in use (stop the other process or use --port)."
    fi
}

run_foreground() {
    local java_args=()
    [ -n "${JAVA_OPTS:-}" ] && read -r -a java_args <<< "${JAVA_OPTS}"
    if is_running; then
        die "already running in the background (pid $(cat "$PID_FILE")); use --stop first."
    fi
    check_port_free
    log "Starting WorkDSH Admin (Ctrl+C to stop)"
    log_access_urls
    log "Bootstrap credentials and server secrets: $ENV_FILE"
    cd "$ROOT"
    exec "$JAVA_BIN" "${java_args[@]}" -jar "$JAR" \
        --spring.web.resources.static-locations="file:$WEB_DIR/dist/"
}

run_daemon() {
    local java_args=() pid code i
    [ -n "${JAVA_OPTS:-}" ] && read -r -a java_args <<< "${JAVA_OPTS}"

    if is_running; then
        log "Already running (pid $(cat "$PID_FILE")). Use --status or --stop."
        return 0
    fi
    check_port_free
    rm -f "$PID_FILE"
    : > "$LOG_FILE"

    # The inner shell records its own PID, then execs Java, so the PID file is
    # correct even when setsid forks to create a new session.
    setsid bash -c 'echo $$ > "$1"; shift; exec "$@"' _ "$PID_FILE" \
        "$JAVA_BIN" "${java_args[@]}" -jar "$JAR" \
        --spring.web.resources.static-locations="file:$WEB_DIR/dist/" \
        >> "$LOG_FILE" 2>&1 < /dev/null &

    for _ in $(seq 1 50); do
        [ -s "$PID_FILE" ] && break
        sleep 0.2
    done
    [ -s "$PID_FILE" ] || die "server did not start (no PID file); see $LOG_FILE"
    pid="$(cat "$PID_FILE")"

    for i in $(seq 1 90); do
        code="$(curl -s -o /dev/null -w '%{http_code}' "http://$(health_host):$PORT/api/auth/me" 2>/dev/null || true)"
        [ "$code" = "401" ] && break
        if ! kill -0 "$pid" 2>/dev/null; then
            tail -30 "$LOG_FILE" >&2 || true
            die "server exited during startup; see $LOG_FILE"
        fi
        sleep 1
    done
    [ "$code" = "401" ] || { tail -30 "$LOG_FILE" >&2; die "server did not become ready; see $LOG_FILE"; }

    log "WorkDSH Admin is running (pid $pid)"
    log_access_urls
    log "  Log:  $LOG_FILE"
    log "  Stop: ./start.sh --stop"
}

stop_daemon() {
    local pid
    if ! is_running; then
        rm -f "$PID_FILE"
        log "Not running."
        return 0
    fi
    pid="$(cat "$PID_FILE")"
    if [ -r "/proc/$pid/cmdline" ] && ! tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q 'workdsh-admin-server'; then
        die "PID $pid is not the WorkDSH Admin server; refusing to kill it. Remove $PID_FILE manually if stale."
    fi
    log "Stopping pid $pid ..."
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 15); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
        warn "graceful shutdown timed out; sending SIGKILL"
        kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
    log "Stopped."
}

show_status() {
    if is_running; then
        local pid code
        pid="$(cat "$PID_FILE")"
        code="$(curl -s -o /dev/null -w '%{http_code}' "http://$(health_host):$PORT/api/auth/me" 2>/dev/null || true)"
        log "Running (pid $pid), HTTP $code at http://$(health_host):$PORT/"
    else
        log "Not running."
        return 1
    fi
}

show_logs() {
    [ -f "$LOG_FILE" ] || die "no log yet at $LOG_FILE"
    exec tail -n 100 -f "$LOG_FILE"
}

# -------------------------------------------------------------------- main

case "$ACTION" in
    stop)   stop_daemon; exit 0 ;;
    status) if show_status; then exit 0; else exit 1; fi ;;
    logs)   show_logs ;;
esac

ensure_toolchain
load_env
[ -n "$PORT_FROM_CLI" ] && PORT="$PORT_FROM_CLI"
[ -n "$BIND_FROM_CLI" ] && BIND_ADDRESS="$BIND_FROM_CLI"
prepare_runtime_env
build_all

case "$MODE" in
    daemon)   run_daemon ;;
    foreground) run_foreground ;;
esac
