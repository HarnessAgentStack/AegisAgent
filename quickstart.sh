#!/usr/bin/env bash
# =============================================================================
# Aegis Platform - 一键启动脚本 (Docker 全栈)
# -----------------------------------------------------------------------------
# 用途：拉起基础设施 + 应用四件（gateway/admin/runtime/web）
# 前置：已安装 Docker + Docker Compose v2
# 用法：
#   ./quickstart.sh         # 全栈构建并启动
#   ./quickstart.sh infra   # 仅基础设施
#   ./quickstart.sh app     # 仅应用（前提：基础设施已起）
#   ./quickstart.sh down    # 停止全部
#   ./quickstart.sh logs    # 查看应用日志
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$ROOT/infra"

# 颜色
G="\033[32m"; Y="\033[33m"; R="\033[31m"; N="\033[0m"
log()  { echo -e "${G}[aegis]${N} $*"; }
warn() { echo -e "${Y}[aegis]${N} $*"; }
err()  { echo -e "${R}[aegis]${N} $*" >&2; }

# 检查 .env
check_env() {
  if [ ! -f "$INFRA_DIR/.env" ]; then
    warn "未发现 infra/.env，正在从 .env.example 复制..."
    cp "$INFRA_DIR/.env.example" "$INFRA_DIR/.env"
    warn "请编辑 infra/.env 修改密钥后重跑，现以示例值继续（仅限本地体验）"
  fi
}

cmd_infra() {
  log "启动基础设施（MySQL/Redis/Nacos/MinIO/etcd/Milvus）..."
  docker compose -f "$INFRA_DIR/docker-compose.yml" up -d
  log "等待基础设施健康..."
  sleep 8
  docker compose -f "$INFRA_DIR/docker-compose.yml" ps
}

cmd_app() {
  check_env
  log "构建并启动应用（gateway/admin/runtime/web）..."
  docker compose -f "$INFRA_DIR/docker-compose.app.yml" up -d --build
  log "应用启动中，日志：docker compose -f infra/docker-compose.app.yml logs -f"
  echo
  log "前端:    http://localhost"
  log "网关:    http://localhost:8080"
  log "Admin:  http://localhost:8082"
  log "Runtime:http://localhost:8081"
  echo
  log "初始登录: 租户 DEFAULT / 用户名 admin / 密码 aegis@123"
}

cmd_all() {
  check_env
  log "全栈构建并启动（基础设施 + 应用）..."
  docker compose -f "$INFRA_DIR/docker-compose.yml" -f "$INFRA_DIR/docker-compose.app.yml" up -d --build
  echo
  log "✅ 全栈已启动"
  log "前端: http://localhost （初始登录 DEFAULT / admin / aegis@123）"
}

cmd_down() {
  log "停止应用..."
  docker compose -f "$INFRA_DIR/docker-compose.app.yml" down 2>/dev/null || true
  log "停止基础设施..."
  docker compose -f "$INFRA_DIR/docker-compose.yml" down
  log "已停止全部（数据卷保留）"
}

cmd_logs() {
  docker compose -f "$INFRA_DIR/docker-compose.app.yml" logs -f
}

case "${1:-all}" in
  infra) cmd_infra ;;
  app)   cmd_app ;;
  all)   cmd_all ;;
  down)  cmd_down ;;
  logs)  cmd_logs ;;
  *) echo "用法: $0 {all|infra|app|down|logs}"; exit 1 ;;
esac
