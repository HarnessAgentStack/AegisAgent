#!/usr/bin/env bash
# =============================================================================
# Aegis Platform - Unified Service Manager (macOS / Linux)
# =============================================================================
# One script rules all: infra (Docker Compose) + apps (local Java / Vite)
# Windows users: use aegis.ps1 (PowerShell version)
#
# Pre-requisites (auto-detected on first run):
#   - Docker Engine + Compose v2 (docker compose plugin)
#   - JDK 21+    (detect: JAVA_HOME or PATH java)
#   - Maven 3.9+ (detect: MVN_CMD or PATH mvn)
#   - Node.js 18+ (detect: PATH node / npm)
#
# Optional environment variables (override auto-detection):
#   JAVA_HOME   - JDK install dir  (no trailing /bin)
#   MVN_CMD     - full path to mvn executable
#
# Usage:
#   ./aegis.sh help                 Print help
#   ./aegis.sh start                Infra + local apps (frontend dev)
#   ./aegis.sh start frontend=prod  Frontend prod (build + serve)
#   ./aegis.sh stop                 Stop everything
#   ./aegis.sh appstop              Stop apps only, keep infra containers
#   ./aegis.sh status               Show status
#   ./aegis.sh build                Build backend JAR + frontend dist
#   ./aegis.sh restart              appstop + build + start
#   ./aegis.sh infra                Toggle infra containers only
#
# Architecture:
#   Infra = Docker Compose (MySQL / Redis / Nacos / MinIO / etcd / Milvus)
#   Apps  = local java -jar (gateway/admin/runtime/mcp-demo) + vite dev/build
#   mcp-demo registers itself to admin on startup (auto-registrar), no DB seed needed
#   DB init: MySQL container auto-runs infra/ddl/*.sql on FIRST empty volume boot
#   OCR   = ONNX Runtime Java 进程内推理（零外部依赖，已移除 PaddleOCR Docker 服务）
#   DB init: MySQL container auto-runs infra/ddl/*.sql on FIRST empty volume boot
# =============================================================================

set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
BACKEND_ROOT="$PROJECT_ROOT/aegis-platform-backend"
FRONTEND_ROOT="$PROJECT_ROOT/aegis-platform-web"
INFRA_ROOT="$PROJECT_ROOT/infra"
LOG_DIR="$PROJECT_ROOT/logs"

# --- Action parsing ---
ACTION="${1:-status}"
FRONTEND_MODE="${2:-dev}"
case "$FRONTEND_MODE" in
    frontend=prod) FRONTEND_MODE="prod" ;;
    frontend=dev)  FRONTEND_MODE="dev" ;;
esac

# --- Colors ---
CYAN='\033[36m'; GREEN='\033[32m'; YELLOW='\033[33m'; RED='\033[31m'; GRAY='\033[90m'; WHITE='\033[37m'; NC='\033[0m'
info()  { echo -e "[INFO]  $*" | sed "s/.*/${CYAN}&${NC}/"; }
ok()    { echo -e "[OK]    $*" | sed "s/.*/${GREEN}&${NC}/"; }
warn()  { echo -e "[WARN]  $*" | sed "s/.*/${YELLOW}&${NC}/"; }
err()   { echo -e "[ERROR] $*" | sed "s/.*/${RED}&${NC}/"; }
hd()    { echo -e "$*" | sed "s/.*/${GRAY}&${NC}/"; }

# --- Path helpers ---
ensure_log_dir() { mkdir -p "$LOG_DIR"; }

# --- Load aegis.conf (optional override for env vars) ---
load_aegis_conf() {
    local conf_file="$SCRIPT_DIR/aegis.conf"
    [ -f "$conf_file" ] || return 0
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%%#*}"                  # strip comment
        line="$(echo "$line" | xargs)"      # trim whitespace
        [ -z "$line" ] && continue
        if echo "$line" | grep -qE '^[A-Z_][A-Z_0-9]*='; then
            local key="${line%%=*}"
            local val="${line#*=}"
            val="${val%\"}"
            val="${val#\"}"
            val="${val%\'}"
            val="${val#\'}"
            [ -n "$val" ] && export "$key=$val"
        fi
    done < "$conf_file"
}
load_aegis_conf

# --- Env detection ---
JAVA_EXE=""
MVN_CMD=""
NODE_EXE=""
NPM_CMD=""

resolve_env() {
    info "Detecting environment..."
    local ok_flag=1

    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        JAVA_EXE="$JAVA_HOME/bin/java"
    elif command -v java >/dev/null 2>&1; then
        JAVA_EXE="$(command -v java)"
    fi
    if [ -z "$JAVA_EXE" ]; then
        err "JDK not found. Set JAVA_HOME or install JDK 21+"
        ok_flag=0
    else
        local jv
        jv="$($JAVA_EXE -version 2>&1 | head -1)"
        ok "JDK: $JAVA_EXE  $jv"
    fi

    if [ -n "$MVN_CMD" ] && [ -x "$MVN_CMD" ]; then
        : # keep
    elif command -v mvn >/dev/null 2>&1; then
        MVN_CMD="$(command -v mvn)"
    fi
    if [ -z "$MVN_CMD" ]; then
        err "Maven not found. Set MVN_CMD or install Maven 3.9+"
        ok_flag=0
    else
        ok "Maven: $MVN_CMD"
    fi

    if command -v node >/dev/null 2>&1; then
        NODE_EXE="$(command -v node)"
        NODE_EXE="$NODE_EXE"
        NPM_CMD="$(command -v npm)"
        local nv
        nv="$($NODE_EXE --version 2>/dev/null)"
        ok "Node: $NODE_EXE  $nv"
    else
        warn "Node.js not found (frontend unavailable)"
        NODE_EXE="node"; NPM_CMD="npm"
    fi
    return $ok_flag
}

# --- Docker check ---
test_docker() { docker info >/dev/null 2>&1; }

# --- Port helpers ---
port_listening() {
    lsof -i ":$1" -sTCP:LISTEN >/dev/null 2>&1 || ss -tlnp 2>/dev/null | grep -q ":$1 "
}
wait_port() {
    local port=$1 timeout=$2 name=$3 waited=0
    while [ $waited -lt $timeout ]; do
        port_listening "$port" && return 0
        sleep 2; waited=$((waited+2))
    done
    return 1
}
pid_on_port() {
    local p
    p="$(lsof -t -i ":$1" -sTCP:LISTEN 2>/dev/null | head -1)"
    echo "$p"
}

# --- Jar resolver ---
BACKEND_SERVICES=("gateway:8080" "admin:8082" "runtime:8081" "mcp-demo:8084")

find_jar() {
    local mod=$1
    local dir="$BACKEND_ROOT/$mod/target"
    [ -d "$dir" ] || return 1
    local j
    j="$(ls -t "$dir"/*.jar 2>/dev/null | grep -v '^original-' | head -1)"
    echo "$j"
    [ -n "$j" ]
}

# --- Sandbox host (unix socket on Mac/Linux) ---
get_docker_host() { echo "unix:///var/run/docker.sock"; }

# =============================================================================
# Infra
# =============================================================================
start_infra() {
    local skip_hc=$1
    if ! test_docker; then
        err "Docker not running. Start Docker Desktop / Engine and retry."
        return 1
    fi

    info "Starting Docker containers..."
    cd "$INFRA_ROOT"
    docker compose up -d mysql redis nacos minio etcd milvus 2>/dev/null
    cd "$PROJECT_ROOT"

    if [ "$skip_hc" != "skip" ]; then
        info "Waiting for infra services..."
        # MySQL 120, Redis 30, Nacos 120, Milvus 60
        if wait_port 3306 120 "MySQL";   then ok "MySQL ready (port 3306)";   else warn "MySQL not ready (port 3306)"; fi
        if wait_port 6379 30  "Redis";   then ok "Redis ready (port 6379)";   else warn "Redis not ready (port 6379)"; fi
        if wait_port 8848 120 "Nacos";   then ok "Nacos ready (port 8848)";   else warn "Nacos not ready (port 8848)"; fi
        if wait_port 19530 60 "Milvus";  then ok "Milvus ready (port 19530)"; else warn "Milvus not ready (port 19530)"; fi
    fi
    return 0
}

stop_infra() {
    info "Stopping Docker containers..."
    cd "$INFRA_ROOT"
    docker compose down 2>/dev/null
    cd "$PROJECT_ROOT"
    ok "Infra stopped"
}

show_infra_status() {
    info "Infra containers:"
    local items
    items="$(docker ps -a --format '{{.Names}}\t{{.Status}}' 2>/dev/null | grep aegis || true)"
    if [ -z "$items" ]; then
        hd "  (no aegis containers)"
        return
    fi
    while IFS=$'\t' read -r name status; do
        local color="$GREEN"; echo "$status" | grep -q "Up" || color="$RED"
        printf "  ${WHITE}%s${NC}  ${color}%s${NC}\n" "$(printf '%-22s' "$name")" "$status"
    done <<< "$items"
}

# =============================================================================
# Backend
# =============================================================================
start_backend() {
    info "Starting backend services (local processes)..."
    ensure_log_dir
    local sandbox_host
    sandbox_host="$(get_docker_host)"
    info "SANDBOX_DOCKER_HOST = $sandbox_host"

    for entry in "${BACKEND_SERVICES[@]}"; do
        local name="${entry%%:*}"
        local port="${entry##*:}"
        if port_listening "$port"; then
            warn "$name port $port in use, skip"
            continue
        fi
        local jar
        jar="$(find_jar "$name")"
        if [ -z "$jar" ]; then
            err "$name JAR not found. Run: ./aegis.sh build"
            continue
        fi

        local logf="$LOG_DIR/${name}.log"
        local errf="$LOG_DIR/${name}.err.log"
        nohup "$JAVA_EXE" \
            -Xms256m -Xmx512m \
            -jar "$jar" \
            --spring.profiles.active=local \
            --spring.cloud.nacos.config.enabled=false \
            --networkaddress.cache.ttl=10 \
            --aegis.runtime.sandbox.docker.host="$sandbox_host" \
            >"$logf" 2>"$errf" &
        info "  $name starting (port $port)..."
    done

    info "Waiting for backend..."
    for entry in "${BACKEND_SERVICES[@]}"; do
        local name="${entry%%:*}"
        local port="${entry##*:}"
        if wait_port "$port" 90 "$name"; then
            ok "$name ready (port $port)"
        else
            err "$name timeout. Log: $LOG_DIR/${name}.log"
        fi
    done
}

stop_backend() {
    info "Stopping backend processes..."
    for entry in "${BACKEND_SERVICES[@]}"; do
        local name="${entry%%:*}"
        local port="${entry##*:}"
        local pid
        pid="$(pid_on_port "$port")"
        if [ -n "$pid" ]; then
            kill -9 "$pid" 2>/dev/null
            hd "  Stopped: $name (PID $pid)"
        fi
    done
    sleep 1
    ensure_backend_stopped
}

ensure_backend_stopped() {
    # 兜底：按命令行匹配杀所有 aegis 相关 Java 进程（pkill -f 在 Linux/macOS 可用）
    local killed=0
    if command -v pkill >/dev/null 2>&1; then
        local before
        before=$(pgrep -f "aegis-(gateway|admin|runtime|mcp-demo).*-exec.jar" 2>/dev/null | wc -l)
        pkill -9 -f "aegis-(gateway|admin|runtime|mcp-demo).*-exec.jar" 2>/dev/null
        sleep 1
        killed=$(( before - $(pgrep -f "aegis-(gateway|admin|runtime|mcp-demo).*-exec.jar" 2>/dev/null | wc -l) ))
    fi
    if [ "$killed" -gt 0 ]; then
        info "Force-killed $killed aegis Java process(es)"
    fi

    # 验证 target jar 是否还被进程占用（lsof 可检测）
    local jar_locked=0
    for entry in "${BACKEND_SERVICES[@]}"; do
        local name="${entry%%:*}"
        local jar="$BACKEND_ROOT/$name/target/aegis-$name-*-exec.jar"
        local match
        match=$(ls -1 $jar 2>/dev/null | head -1)
        if [ -n "$match" ] && command -v lsof >/dev/null 2>&1; then
            if lsof "$match" >/dev/null 2>&1; then
                err "  $name jar LOCKED: $(basename "$match")"
                jar_locked=1
            fi
        fi
    done

    if [ "$jar_locked" -eq 1 ]; then
        err "One or more backend JARs are LOCKED. Build will likely fail."
        return 1
    fi
    ok "All backend processes cleanly stopped (jar files released)"
    return 0
}

show_backend_status() {
    info "Backend services:"
    for entry in "${BACKEND_SERVICES[@]}"; do
        local name="${entry%%:*}"
        local port="${entry##*:}"
        if port_listening "$port"; then
            local pid
            pid="$(pid_on_port "$port")"
            printf "  ${WHITE}%s${NC} port %s  ${GREEN}RUNNING (PID %s)${NC}\n" "$(printf '%-10s' "$name")" "$port" "$pid"
        else
            printf "  ${WHITE}%s${NC} port %s  ${RED}STOPPED${NC}\n" "$(printf '%-10s' "$name")" "$port"
        fi
    done
}

# =============================================================================
# Frontend
# =============================================================================

build_frontend() {
    local mode=$1
    [ -z "$mode" ] && mode="prod"
    if [ "$mode" = "dev" ]; then
        info "Frontend dev mode → 跳过 vite build（dev server 热更新）"
        return 0
    fi
    info "Frontend build (prod)..."
    cd "$FRONTEND_ROOT"
    if [ ! -d node_modules ]; then
        info "Installing frontend deps (first run)..."
        $NPM_CMD install 2>&1 | tee "$LOG_DIR/npm-install.log"
        [ $? -ne 0 ] && { err "npm install FAILED. See: $LOG_DIR/npm-install.log"; cd "$PROJECT_ROOT"; return 1; }
    fi
    npx vite build 2>&1 | tee "$LOG_DIR/vite-build.log"
    local fe_rc=${PIPESTATUS[0]}
    cd "$PROJECT_ROOT"
    if [ $fe_rc -ne 0 ] || [ ! -d "$FRONTEND_ROOT/dist" ]; then
        err "Frontend build FAILED (exit=$fe_rc). See: $LOG_DIR/vite-build.log"
        return 1
    fi
    ok "Frontend build done"
    return 0
}

start_frontend() {
    local mode=$1
    info "Starting frontend ($mode mode)..."
    ensure_log_dir

    if [ "$mode" = "dev" ]; then
        if port_listening 5173; then warn "Frontend port 5173 in use, skip"; return; fi
        if [ ! -d "$FRONTEND_ROOT/node_modules" ]; then
            info "Installing frontend deps..."
            cd "$FRONTEND_ROOT" && $NPM_CMD install >/dev/null 2>&1 && cd "$PROJECT_ROOT"
        fi
        local logf="$LOG_DIR/frontend.log"
        nohup npx vite --host >"$logf" 2>&1 &
        if wait_port 5173 30 "Frontend"; then
            ok "Frontend ready: http://localhost:5173"
        else
            err "Frontend failed. Log: $logf"
        fi
    else
        info "Frontend build (prod)..."
        cd "$FRONTEND_ROOT"
        if [ ! -d node_modules ]; then
            $NPM_CMD install 2>&1 | tee "$LOG_DIR/npm-install.log"
            [ $? -ne 0 ] && { err "npm install FAILED. See: $LOG_DIR/npm-install.log"; cd "$PROJECT_ROOT"; return; }
        fi
        npx vite build 2>&1 | tee "$LOG_DIR/vite-build.log"
        local fe_rc=${PIPESTATUS[0]}
        cd "$PROJECT_ROOT"
        if [ $fe_rc -ne 0 ] || [ ! -d "$FRONTEND_ROOT/dist" ]; then
            err "Frontend build FAILED (exit=$fe_rc). See: $LOG_DIR/vite-build.log"
            return
        fi

        local port=80
        port_listening 80 && port=8088 && warn "Port 80 occupied, serve on $port"
        local logf="$LOG_DIR/frontend.log"
        cd "$FRONTEND_ROOT"
        nohup npx serve -s dist -l "$port" >"$logf" 2>&1 &
        cd "$PROJECT_ROOT"
        if wait_port "$port" 30 "Frontend"; then
            ok "Frontend ready: http://localhost:$port (prod)"
        else
            err "Frontend serve failed. Log: $logf"
        fi
    fi
}

stop_frontend() {
    info "Stopping frontend..."
    for p in 5173 80 8088; do
        local pid
        pid="$(pid_on_port "$p")"
        if [ -n "$pid" ]; then
            kill -9 "$pid" 2>/dev/null
            hd "  Stopped: Frontend (PID $pid)"
        fi
    done
    ok "Frontend stopped"
}

show_frontend_status() {
    info "Frontend:"
    for p in 5173 80 8088; do
        if port_listening "$p"; then
            local pid mode
            pid="$(pid_on_port "$p")"
            mode="prod"; [ "$p" = "5173" ] && mode="dev"
            printf "  ${WHITE}Frontend${NC}  port %s (%s)  ${GREEN}RUNNING (PID %s)${NC}\n" "$p" "$mode" "$pid"
            return
        fi
    done
    printf "  ${WHITE}Frontend${NC}  port 5173         ${RED}STOPPED${NC}\n"
}

# =============================================================================
# Build
# =============================================================================
build_backend() {
    local clean=$1
    info "Building backend JAR $( [ "$clean" = "clean" ] && echo '(clean)' || echo '(incremental)' )..."
    local goal="package"
    [ "$clean" = "clean" ] && goal="clean package"
    cd "$BACKEND_ROOT"
    if $MVN_CMD $goal -DskipTests 2>&1 | tee "$LOG_DIR/mvn-build.log"; then
        ok "Backend build done"
    else
        err "Maven build FAILED (exit=${PIPESTATUS[0]}). See: $LOG_DIR/mvn-build.log"
        cd "$PROJECT_ROOT"
        return 1
    fi
    cd "$PROJECT_ROOT"
    return 0
}

# =============================================================================
# Help
# =============================================================================
show_help() {
    cat <<EOF

========================================
  Aegis Platform - Unified Manager (Mac/Linux)
========================================

Infra = Docker Compose (MySQL/Redis/Nacos/MinIO/Milvus/etcd)
OCR   = ONNX Runtime Java (进程内推理，零外部依赖)
Apps  = local java -jar + vite (no container isolation issues)

Usage:
  ./aegis.sh help                    This help
  ./aegis.sh start                   Infra + apps (frontend dev)
  ./aegis.sh start frontend=prod     Frontend prod (build + serve)
  ./aegis.sh stop                    Stop everything
  ./aegis.sh appstop                 Stop apps only (keep infra)
  ./aegis.sh status                  Show status
  ./aegis.sh build                   Build JAR + frontend dist
  ./aegis.sh restart                 appstop + build + start
  ./aegis.sh infra                   Toggle infra only

Pre-requisites (auto-detected, set env vars to override):
  Docker Engine + Compose v2
  JDK 21+    (JAVA_HOME or PATH)
  Maven 3.9+ (MVN_CMD or PATH)
  Node.js 18+ (PATH)

URLs:
  Frontend: http://localhost:5173  (dev) / http://localhost:80  (prod)
  Gateway:  http://localhost:8080
  Admin:    http://localhost:8082
  Runtime:  http://localhost:8081
  MCP-Demo: http://localhost:8084/sse
  Nacos:    http://localhost:8848/nacos  (nacos/nacos)
  MinIO:    http://localhost:9001  (aegis/aegis12345)
  MySQL:    localhost:3306  (root/root123)

Default login: DEFAULT / admin / aegis@123

EOF
}

# =============================================================================
# Main
# =============================================================================
case "$ACTION" in
    help|-h|--help) show_help; exit 0 ;;
esac

echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Aegis Platform Unified Manager${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

case "$ACTION" in
    status)
        show_infra_status; echo ""
        show_backend_status; echo ""
        show_frontend_status
        exit 0
        ;;

    infra)
        local all_up=1
        for c in aegis-mysql aegis-redis aegis-nacos aegis-minio aegis-etcd aegis-milvus; do
            local r
            r="$(docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null || echo false)"
            [ "$r" = "true" ] || { all_up=0; break; }
        done
        if [ $all_up -eq 1 ]; then
            info "All infra running, stopping..."
            stop_infra
        else
            resolve_env >/dev/null 2>&1
            start_infra ""
        fi
        exit 0
        ;;

    build)
        resolve_env >/dev/null 2>&1
        build_backend "clean"
        info "Building frontend dist..."
        cd "$FRONTEND_ROOT"
        [ ! -d node_modules ] && $NPM_CMD install >/dev/null 2>&1
        npx vite build >/dev/null 2>&1
        cd "$PROJECT_ROOT"
        ok "Frontend build done"
        exit 0
        ;;

    stop)
        stop_frontend
        stop_backend
        stop_infra
        echo ""; ok "All stopped"
        exit 0
        ;;

    appstop)
        info "Stopping local apps only (keeping infra containers)..."
        stop_frontend
        stop_backend
        echo ""; ok "Local apps stopped, infra kept running"
        exit 0
        ;;

    start)
        resolve_env >/dev/null 2>&1
        start_infra ""
        if [ $? -eq 0 ]; then
            info "DB init note: MySQL container auto-runs infra/ddl/*.sql on FIRST empty boot"
            info "If infra was already up, DB is already initialized."
            if ! build_backend "clean"; then
                err "Backend build failed. Aborting start."
                exit 1
            fi
            if ! build_frontend "$FRONTEND_MODE"; then
                err "Frontend build failed. Aborting start."
                exit 1
            fi
            start_backend
            start_frontend "$FRONTEND_MODE"
            echo ""; ok "All services started!"
            echo ""
            local fe_port=5173; [ "$FRONTEND_MODE" = "prod" ] && fe_port=80
            hd "  Frontend: http://localhost:$fe_port"
            hd "  Gateway:  http://localhost:8080"
            hd "  Admin:    http://localhost:8082"
            hd "  Runtime:  http://localhost:8081"
            hd "  MCP-Demo: http://localhost:8084/sse"
            hd "  Nacos:    http://localhost:8848/nacos"
            hd "  MinIO:    http://localhost:9001"
            hd "  Logs:     $LOG_DIR"
            echo ""; hd "  Login: DEFAULT / admin / aegis@123"
        else
            err "Infra startup failed, check Docker"
        fi
        exit 0
        ;;

    restart)
        info "Restart: appstop -> build -> start"
        stop_frontend
        stop_backend
        sleep 2
        resolve_env >/dev/null 2>&1
        if ! build_backend "clean"; then
            err "Backend build failed. Aborting restart."
            exit 1
        fi
        if ! build_frontend "$FRONTEND_MODE"; then
            err "Frontend build failed. Aborting restart."
            exit 1
        fi
        start_infra "skip" && start_backend && start_frontend "$FRONTEND_MODE"
        echo ""; ok "Restart done"
        exit 0
        ;;

    *)
        err "Unknown action: $ACTION"
        show_help
        exit 1
        ;;
esac