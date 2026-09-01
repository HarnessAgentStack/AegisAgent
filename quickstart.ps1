<#
.SYNOPSIS
    Aegis Platform 一键启动脚本 (Docker 全栈)
.DESCRIPTION
    拉起基础设施 + 应用四件（gateway/admin/runtime/web）
    前置：已安装 Docker Desktop + Compose v2
.EXAMPLE
    .\quickstart.ps1           # 全栈构建并启动
    .\quickstart.ps1 infra     # 仅基础设施
    .\quickstart.ps1 app       # 仅应用（前提：基础设施已起）
    .\quickstart.ps1 down      # 停止全部
    .\quickstart.ps1 logs      # 查看应用日志
#>
param(
    [Parameter(Position=0)]
    [ValidateSet("all","infra","app","down","logs")]
    [string]$Action = "all"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Infra = Join-Path $Root "infra"
function Log($m){ Write-Host "[aegis] $m" -ForegroundColor Green }
function Warn($m){ Write-Host "[aegis] $m" -ForegroundColor Yellow }

function Check-Env {
    $envf = Join-Path $Infra ".env"
    if (-not (Test-Path $envf)) {
        Warn "未发现 infra/.env，正在从 .env.example 复制..."
        Copy-Item (Join-Path $Infra ".env.example") $envf
        Warn "请编辑 infra/.env 修改密钥后重跑，现以示例值继续（仅限本地体验）"
    }
}

switch ($Action) {
    "infra" {
        Log "启动基础设施..."
        docker compose -f "$Infra/docker-compose.yml" up -d
        Log "等待基础设施健康..."
        Start-Sleep 8
        docker compose -f "$Infra/docker-compose.yml" ps
    }
    "app" {
        Check-Env
        Log "构建并启动应用..."
        docker compose -f "$Infra/docker-compose.app.yml" up -d --build
        Log "初始登录: 租户 DEFAULT / admin / aegis@123"
        Log "前端: http://localhost"
    }
    "all" {
        Check-Env
        Log "全栈构建并启动..."
        docker compose -f "$Infra/docker-compose.yml" -f "$Infra/docker-compose.app.yml" up -d --build
        Write-Host ""
        Log "全栈已启动 - 前端: http://localhost （DEFAULT / admin / aegis@123）"
    }
    "down" {
        Log "停止应用..."
        docker compose -f "$Infra/docker-compose.app.yml" down 2>$null
        Log "停止基础设施..."
        docker compose -f "$Infra/docker-compose.yml" down
        Log "已停止全部（数据卷保留）"
    }
    "logs" {
        docker compose -f "$Infra/docker-compose.app.yml" logs -f
    }
}
