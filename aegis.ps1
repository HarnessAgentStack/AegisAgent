# =============================================================================
# Aegis Platform - Unified Service Manager (Windows)
# =============================================================================
# One script rules all: infra (Docker Compose) + apps (local Java / Vite)
# macOS / Linux users: use aegis.sh (bash version)
#
# Pre-requisites (auto-detected on first run):
#   - Docker Desktop or Engine + Compose v2
#   - JDK 21+    (detect: JAVA_HOME or PATH java)
#   - Maven 3.9+ (detect: MVN_CMD or PATH mvn)
#   - Node.js 18+ (detect: PATH node / npm)
#
# Optional environment variables (override auto-detection):
#   JAVA_HOME   - JDK install dir  (no trailing \bin)
#   MVN_CMD     - full path to mvn(.cmd) executable
#
# Usage:
#   .\aegis.ps1 help                 Print help
#   .\aegis.ps1 start                Infra + local apps (frontend dev)
#   .\aegis.ps1 start frontend=prod  Frontend prod (build + serve)
#   .\aegis.ps1 stop                 Stop everything
#   .\aegis.ps1 appstop              Stop apps only, keep infra containers
#   .\aegis.ps1 status               Show status
#   .\aegis.ps1 build                Build backend JAR + frontend dist
#   .\aegis.ps1 restart              appstop + build + start
#   .\aegis.ps1 infra                Toggle infra containers only
#
# Architecture:
#   Infra = Docker Compose (MySQL / Redis / Nacos / MinIO / etcd / Milvus)
#   Apps  = local java -jar (gateway/admin/runtime/mcp-demo) + vite dev/build
#   mcp-demo registers itself to admin on startup (auto-registrar), no DB seed needed
#   DB init: MySQL container auto-runs infra/ddl/*.sql on FIRST empty volume boot
# =============================================================================
param(
    [Parameter(Position = 0)]
    [ValidateSet("start", "stop", "appstop", "status", "build", "restart", "infra", "help")]
    [string]$Action = "status",

    [string]$Frontend = "dev"
)

$ErrorActionPreference = "Continue"

# --- Paths (all relative to script location, NO absolute paths) ---
$PROJECT_ROOT  = $PSScriptRoot
$BACKEND_ROOT  = Join-Path $PROJECT_ROOT "aegis-platform-backend"
$FRONTEND_ROOT = Join-Path $PROJECT_ROOT "aegis-platform-web"
$INFRA_ROOT    = Join-Path $PROJECT_ROOT "infra"
$LOG_DIR       = Join-Path $PROJECT_ROOT "logs"

# --- App definitions (all paths relative, jar version auto-detected) ---
function Resolve-Jar([string]$moduleName) {
    # jar name pattern: <module>-0.1.0-alpha.1(-exec)?.jar
    $dir = Join-Path $BACKEND_ROOT "$moduleName\target"
    if (-not (Test-Path $dir)) { return $null }
    $jar = Get-ChildItem -Path $dir -Filter "*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "^original-" } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($jar) { return $jar.FullName }
    return $null
}

$BACKEND_SERVICES = @(
    @{ Name = "gateway";  Jar = (Resolve-Jar "aegis-gateway");  Port = 8080 }
    @{ Name = "admin";    Jar = (Resolve-Jar "aegis-admin");    Port = 8082 }
    @{ Name = "runtime";  Jar = (Resolve-Jar "aegis-runtime");  Port = 8081 }
    @{ Name = "mcp-demo"; Jar = (Resolve-Jar "aegis-mcp-demo"); Port = 8084 }
)

$INFRA_CONTAINERS = @(
    "aegis-mysql", "aegis-redis", "aegis-nacos", "aegis-minio",
    "aegis-etcd", "aegis-milvus"
)

# =============================================================================
# Helpers
# =============================================================================
function Write-Info($m) { Write-Host "[INFO]  $m" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "[OK]    $m" -ForegroundColor Green }
function Write-Warn($m) { Write-Host "[WARN]  $m" -ForegroundColor Yellow }
function Write-Err($m)  { Write-Host "[ERROR] $m" -ForegroundColor Red }

function Test-Port($port) {
    return $null -ne (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

function Get-ProcIdOnPort($port) {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($c) { return $c.OwningProcess }
    return $null
}

function Wait-PortReady($port, $timeoutSec, $name) {
    $elapsed = 0.0
    while ($elapsed -lt $timeoutSec) {
        if (Test-Port $port) { return $true }
        Start-Sleep -Milliseconds 1500
        $elapsed += 1.5
    }
    return $false
}

function Ensure-LogDir {
    if (-not (Test-Path $LOG_DIR)) { New-Item -ItemType Directory -Path $LOG_DIR -Force | Out-Null }
}

# =============================================================================
# Load aegis.conf (optional override for env vars)
# =============================================================================
function Load-AegisConf {
    $confFile = Join-Path $PROJECT_ROOT "aegis.conf"
    if (-not (Test-Path $confFile)) { return }
    foreach ($line in Get-Content $confFile) {
        $l = $line.Trim()
        if (-not $l -or $l.StartsWith("#")) { continue }
        if ($l -match '^([A-Z_][A-Z_0-9]*)=(.*)$') {
            $key = $Matches[1]
            $val = $Matches[2].Trim().Trim('"').Trim("'")
            if (-not [string]::IsNullOrWhiteSpace($val)) {
                Set-Item -Path "env:$key" -Value $val
            }
        }
    }
}

# =============================================================================
# Env detection
# =============================================================================
$JAVA_EXE  = $null
$MVN_CMD   = $null
$NODE_EXE  = $null
$NPM_CMD   = $null

Load-AegisConf

function Test-Docker {
    try { docker info 2>$null | Out-Null; return $true }
    catch { return $false }
}
function Resolve-Env {
    Write-Info "Detecting environment..."
    $ok = $true

    # Java
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $script:JAVA_EXE = "$env:JAVA_HOME\bin\java.exe"
    } else {
        $j = Get-Command java -ErrorAction SilentlyContinue
        if ($j) { $script:JAVA_EXE = $j.Source }
    }
    if (-not $script:JAVA_EXE) { Write-Err "JDK not found. Set JAVA_HOME or install JDK 21+"; $ok = $false }
    else {
        try { $v = & $script:JAVA_EXE -version 2>&1 | Select-Object -First 1 } catch { $v = "" }
        Write-Ok "JDK: $script:JAVA_EXE  $v"
    }

    # Maven
    if ($env:MVN_CMD -and (Test-Path $env:MVN_CMD)) {
        $script:MVN_CMD = $env:MVN_CMD
    } else {
        $m = Get-Command mvn -ErrorAction SilentlyContinue
        if ($m) { $script:MVN_CMD = $m.Source }
    }
    if (-not $script:MVN_CMD) { Write-Err "Maven not found. Set MVN_CMD or install Maven 3.9+"; $ok = $false }
    else { Write-Ok "Maven: $script:MVN_CMD" }

    # Node (not blocking - frontend is optional)
    $script:NODE_EXE = (Get-Command node -ErrorAction SilentlyContinue).Source
    $script:NPM_CMD  = (Get-Command npm  -ErrorAction SilentlyContinue).Source
    if (-not $script:NODE_EXE) { Write-Warn "Node.js not found (frontend unavailable)"; $script:NODE_EXE = "node"; $script:NPM_CMD = "npm" }
    else { $nv = & $script:NODE_EXE --version 2>$null; Write-Ok "Node: $script:NODE_EXE  $nv" }

    return $ok
}

# =============================================================================
# Infra
# =============================================================================
function Start-Infra {
    param([switch]$SkipHealthyCheck)
    if (-not (Test-Docker)) {
        Write-Err "Docker not running. Start Docker Desktop and retry."
        return $false
    }

    # --- 核心基础设施先行启动 ---
    Write-Info "Starting core Docker containers (MySQL/Redis/Nacos/MinIO/etcd/Milvus)..."
    Push-Location $INFRA_ROOT
    docker compose up -d mysql redis nacos minio etcd milvus 2>&1 | Out-Null
    Pop-Location

    # --- 先等核心服务就绪，再处理 PaddleOCR（避免 OCR 构建阻塞/掩盖核心启动进度）---
    if (-not $SkipHealthyCheck) {
        Write-Info "Waiting for core infra services..."
        $coreChecks = @(
            @{ Port = 3306; Name = "MySQL";    Timeout = 120 },
            @{ Port = 6379; Name = "Redis";    Timeout = 30 },
            @{ Port = 8848; Name = "Nacos";    Timeout = 120 },
            @{ Port = 19530; Name = "Milvus";  Timeout = 60 }
        )
        foreach ($c in $coreChecks) {
            if (Wait-PortReady $c.Port $c.Timeout $c.Name) {
                Write-Ok "$($c.Name) ready (port $($c.Port))"
            } else {
                Write-Warn "$($c.Name) not ready (port $($c.Port))"
            }
        }
    }

    # --- PaddleOCR 已移除：改用 ONNX Runtime Java 进程内推理（零外部服务依赖） ---

    # ========== 预拉沙箱基础镜像（防御 Docker Desktop K8s containerd 镜像代理故障） ==========
    # Docker Desktop K8s 模式下，K8s 内嵌的 containerd 与宿主 Docker daemon 是两套独立 runtime。
    # 宿主 docker pull 用的是 Settings 里配的 Registry Mirrors（通常是好的），
    # 但 containerd 不继承，它有自己的 /etc/containerd/certs.d/_default/hosts.toml，
    # 里面经常被写死一个坏掉的 registry-mirror 代理 → Pod ImagePullBackOff 120s 超时重建循环。
    # 这里预拉 + 利用宿主 containerd 缓存机制，确保 admin 沙箱池首次预热 Pod 能秒起。
    Preload-SandboxImages

    return $true
}


function Preload-SandboxImages {
    <#
    预拉沙箱基础镜像到宿主 Docker daemon。
    Docker Desktop K8s 内嵌的 containerd 共享宿主镜像缓存，
    提前 docker pull 可避免 Pod 首次启动时 containerd 因 registry-mirror 代理故障拉取失败。
    #>
    $sandboxImages = @("library/python:3.11-slim", "library/python:3.9-slim")
    foreach ($img in $sandboxImages) {
        $exists = docker image inspect $img 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Info "Sandbox image already cached: $img"
        } else {
            Write-Info "Preloading sandbox image: $img ..."
            docker pull $img 2>&1 | Select-Object -Last 3
            if ($LASTEXITCODE -ne 0) {
                Write-Warn "Sandbox image pull FAILED: $img (Pod may ImagePullBackOff; check Docker Desktop Settings → Docker Engine → registry-mirrors)"
            } else {
                Write-Ok "Sandbox image ready: $img"
            }
        }
    }
}
function Stop-Infra {
    Write-Info "Stopping Docker containers..."
    Push-Location $INFRA_ROOT
    docker compose down 2>&1 | Out-Null
    Pop-Location
    Write-Ok "Infra stopped"
}

function Show-InfraStatus {
    Write-Info "Infra containers:"
    $items = docker ps -a --format "{{.Names}}`t{{.Status}}" 2>$null | Where-Object { $_ -match "aegis" }
    if (-not $items) { Write-Host "  (no aegis containers)" -ForegroundColor DarkGray; return }
    foreach ($line in $items) {
        $parts = $line -split "`t"
        $color = if ($parts[1] -match "Up") { "Green" } else { "Red" }
        Write-Host "  $($parts[0].PadRight(22)) " -NoNewline -ForegroundColor White
        Write-Host $parts[1] -ForegroundColor $color
    }
}

# =============================================================================
# Backend
# =============================================================================
# 4 slashes required: docker-java parses URI and needs npipe:////./pipe/docker_engine
# 3 slashes (npipe://./...) → docker-java treats "." as host → fallback to npipe://localhost:2375
function Get-DockerHost { return "npipe:////./pipe/docker_engine" }

function Start-Backend {
    Write-Info "Starting backend services (local processes)..."
    Ensure-LogDir
    $sandboxHost = Get-DockerHost
    Write-Info "SANDBOX_DOCKER_HOST = $sandboxHost"

    foreach ($svc in $BACKEND_SERVICES) {
        if (Test-Port $svc.Port) {
            Write-Warn "$($svc.Name) port $($svc.Port) in use, skip"
            continue
        }
        if (-not $svc.Jar -or -not (Test-Path $svc.Jar)) {
            Write-Err "$($svc.Name) JAR not found. Run: .\aegis.ps1 build"
            continue
        }

        $jvmArgs = @(
            "-Xms256m",
            "-Xmx512m",
            "-jar",
            $svc.Jar,
            "--spring.profiles.active=local",
            "--spring.cloud.nacos.config.enabled=false",
            "--networkaddress.cache.ttl=10",
            "--aegis.runtime.sandbox.docker.host=$sandboxHost"
        )

        $logFile = Join-Path $LOG_DIR "$($svc.Name).log"
        $errFile = Join-Path $LOG_DIR "$($svc.Name).err.log"
        Start-Process -FilePath $JAVA_EXE -ArgumentList $jvmArgs -WindowStyle Hidden `
            -RedirectStandardOutput $logFile -RedirectStandardError $errFile
        Write-Info "  $($svc.Name) starting (port $($svc.Port))..."
    }

    Write-Info "Waiting for backend..."
    foreach ($svc in $BACKEND_SERVICES) {
        if (Wait-PortReady $svc.Port 90 $svc.Name) {
            Write-Ok "$($svc.Name) ready (port $($svc.Port))"
        } else {
            Write-Err "$($svc.Name) timeout. Log: $LOG_DIR\$($svc.Name).log"
        }
    }
}

function Stop-Backend {
    Write-Info "Stopping backend processes..."
    foreach ($svc in $BACKEND_SERVICES) {
        $procHandle = Get-ProcIdOnPort $svc.Port
        if ($procHandle) {
            Stop-Process -Id $procHandle -Force -ErrorAction SilentlyContinue
            Write-Host "  Stopped: $($svc.Name) (PID $procHandle)" -ForegroundColor DarkGray
        }
    }
    Start-Sleep -Milliseconds 500
    Ensure-BackendStopped
}

function Ensure-BackendStopped {
    <#
    彻底杀干净所有 aegis 相关 Java 进程。
    按端口杀可能漏（进程卡死不响应但还占 jar），这里命令行匹配兜底 + 验证 jar 已释放。
    #>
    $killed = 0
    try {
        # 按命令行参数匹配：所有启动过 aegis-*-exec.jar 的 java.exe
        $allJava = Get-CimInstance Win32_Process -Filter "name = 'java.exe'" -ErrorAction SilentlyContinue
        foreach ($p in $allJava) {
            if ($p.CommandLine -match "aegis-(gateway|admin|runtime|mcp-demo)") {
                Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
                Write-Host "  Force-killed: PID $($p.ProcessId) ($($p.CommandLine.Substring(0, [Math]::Min(80, $p.CommandLine.Length))))" -ForegroundColor DarkGray
                $killed++
            }
        }
    } catch {
        # Win32_Process 不可用时回退
        $allJava = Get-Process java -ErrorAction SilentlyContinue
        foreach ($p in $allJava) {
            try {
                $cmdLine = (Get-CimInstance Win32_Process -Filter "ProcessId = $($p.Id)" -ErrorAction SilentlyContinue).CommandLine
                if ($cmdLine -match "aegis-(gateway|admin|runtime|mcp-demo)") {
                    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
                    $killed++
                }
            } catch {}
        }
    }

    if ($killed -gt 0) {
        Write-Info "Force-killed $killed aegis Java process(es)"
    }

    # 验证 target jar 已释放（Windows 下被独占锁定时 File.OpenWrite 抛 IOException）
    $jarLocked = $false
    foreach ($svc in $BACKEND_SERVICES) {
        $jar = Join-Path $BACKEND_ROOT "$($svc.Name)\target\aegis-$($svc.Name)-*-exec.jar"
        $match = Get-ChildItem -Path $jar -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($match) {
            try {
                $fs = [System.IO.File]::OpenWrite($match.FullName)
                $fs.Dispose()
            } catch {
                Write-Err "  $($svc.Name) jar LOCKED: $($match.Name)"
                $jarLocked = $true
            }
        }
    }

    Start-Sleep -Milliseconds 500
    if ($jarLocked) {
        Write-Err "One or more backend JARs are LOCKED. Build will likely fail. Check task manager."
        return $false
    }
    Write-Ok "All backend processes cleanly stopped (jar files released)"
    return $true
}

function Show-BackendStatus {
    Write-Info "Backend services:"
    foreach ($svc in $BACKEND_SERVICES) {
        if (Test-Port $svc.Port) {
            $procHandle = Get-ProcIdOnPort $svc.Port
            Write-Host "  $($svc.Name.PadRight(10)) port $($svc.Port)  " -NoNewline -ForegroundColor White
            Write-Host "RUNNING (PID $procHandle)" -ForegroundColor Green
        } else {
            Write-Host "  $($svc.Name.PadRight(10)) port $($svc.Port)  " -NoNewline -ForegroundColor White
            Write-Host "STOPPED" -ForegroundColor Red
        }
    }
}

# =============================================================================
# Frontend
# =============================================================================

function Invoke-FrontendBuild {
    param(
        [string]$Mode = "prod"  # prod = vite build; dev 时不 build
    )
    if ($Mode -eq "dev") {
        Write-Info "Frontend dev mode → 跳过 vite build（dev server 热更新）"
        return $true
    }
    Write-Info "Frontend build (prod)..."
    Push-Location $FRONTEND_ROOT
    if (-not (Test-Path "node_modules")) {
        Write-Info "Installing frontend deps (first run)..."
        & $NPM_CMD install 2>&1 | Tee-Object -FilePath (Join-Path $LOG_DIR "npm-install.log")
        if ($LASTEXITCODE -ne 0) {
            Write-Err "npm install FAILED. See: $LOG_DIR\npm-install.log"
            Pop-Location; return $false
        }
    }
    & npx vite build 2>&1 | Tee-Object -FilePath (Join-Path $LOG_DIR "vite-build.log")
    $feRc = $LASTEXITCODE
    Pop-Location
    if ($feRc -ne 0 -or -not (Test-Path (Join-Path $FRONTEND_ROOT "dist"))) {
        Write-Err "Frontend build FAILED (exit=$feRc). See: $LOG_DIR\vite-build.log"
        return $false
    }
    Write-Ok "Frontend build done"
    return $true
}

function Start-Frontend {
    param([string]$Mode = "dev")
    Write-Info "Starting frontend ($Mode mode)..."
    Ensure-LogDir

    if ($Mode -eq "dev") {
        if (Test-Port 5173) { Write-Warn "Frontend port 5173 in use, skip"; return }
        $nm = Join-Path $FRONTEND_ROOT "node_modules"
        if (-not (Test-Path $nm)) {
            Write-Info "Installing frontend deps..."
            Push-Location $FRONTEND_ROOT; & $NPM_CMD install 2>&1 | Out-Null; Pop-Location
        }
        $logFile = Join-Path $LOG_DIR "frontend.log"
        $cmdLine = "cd /d `"$FRONTEND_ROOT`" && npx vite --host"
        Start-Process -FilePath "cmd.exe" -ArgumentList "/c", $cmdLine -WindowStyle Hidden -RedirectStandardOutput $logFile
        if (Wait-PortReady 5173 30 "Frontend") {
            Write-Ok "Frontend ready: http://localhost:5173"
        } else {
            Write-Err "Frontend failed. Log: $logFile"
        }
    } else {
        Write-Info "Frontend build (prod)..."
        Push-Location $FRONTEND_ROOT
        if (-not (Test-Path "node_modules")) {
            & $NPM_CMD install 2>&1 | Tee-Object -FilePath (Join-Path $LOG_DIR "npm-install.log")
        }
        & npx vite build 2>&1 | Tee-Object -FilePath (Join-Path $LOG_DIR "vite-build.log")
        Pop-Location
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path (Join-Path $FRONTEND_ROOT "dist"))) {
            Write-Err "Frontend build FAILED (exit=$LASTEXITCODE). See: $LOG_DIR\vite-build.log"
            return
        }

        $port = 80
        if (Test-Port $port) { $port = 8088; Write-Warn "Port 80 occupied, serve on $port" }
        $logFile = Join-Path $LOG_DIR "frontend.log"
        $cmdLine = "cd /d `"$FRONTEND_ROOT`" && npx serve -s dist -l $port"
        Start-Process -FilePath "cmd.exe" -ArgumentList "/c", $cmdLine -WindowStyle Hidden -RedirectStandardOutput $logFile
        if (Wait-PortReady $port 30 "Frontend") {
            Write-Ok "Frontend ready: http://localhost:$port (prod)"
        } else {
            Write-Err "Frontend serve failed. Log: $logFile"
        }
    }
}

function Stop-Frontend {
    Write-Info "Stopping frontend..."
    foreach ($p in @(5173, 80, 8088)) {
        $procHandle = Get-ProcIdOnPort $p
        if ($procHandle) {
            Stop-Process -Id $procHandle -Force -ErrorAction SilentlyContinue
            Write-Host "  Stopped: Frontend (PID $procHandle)" -ForegroundColor DarkGray
        }
    }
    Write-Ok "Frontend stopped"
}

function Show-FrontendStatus {
    Write-Info "Frontend:"
    foreach ($p in @(5173, 80, 8088)) {
        if (Test-Port $p) {
            $procHandle = Get-ProcIdOnPort $p
            $mode = if ($p -eq 5173) { "dev" } else { "prod" }
            Write-Host "  Frontend  port $p ($mode)  " -NoNewline -ForegroundColor White
            Write-Host "RUNNING (PID $procHandle)" -ForegroundColor Green
            return
        }
    }
    Write-Host "  Frontend  port 5173         " -NoNewline -ForegroundColor White
    Write-Host "STOPPED" -ForegroundColor Red
}

# =============================================================================
# Build
# =============================================================================
function Build-Backend {
    param([switch]$Clean)
    Write-Info "Building backend JAR $(if($Clean){'(clean)'}else{'(incremental)'})..."
    $goal = if ($Clean) { @("clean", "package") } else { @("package") }
    Push-Location $BACKEND_ROOT
    & $MVN_CMD @goal "-DskipTests" 2>&1 | Tee-Object -FilePath (Join-Path $LOG_DIR "mvn-build.log")
    $rc = $LASTEXITCODE
    Pop-Location
    if ($rc -ne 0) {
        Write-Err "Maven build FAILED (exit=$rc). See: $LOG_DIR\mvn-build.log"
        return $false
    }
    Write-Ok "Backend build done"
    return $true
}

# =============================================================================
# Help
# =============================================================================
function Show-Help {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor DarkCyan
    Write-Host "  Aegis Platform - Unified Manager (Win)" -ForegroundColor DarkCyan
    Write-Host "========================================" -ForegroundColor DarkCyan
    Write-Host ""
    Write-Host "Infra = Docker Compose (MySQL/Redis/Nacos/MinIO/Milvus/etcd)" -ForegroundColor DarkGray
    Write-Host "OCR   = ONNX Runtime Java (进程内推理，零外部依赖)" -ForegroundColor DarkGray
    Write-Host "Apps  = local java -jar + vite (no container isolation issues)" -ForegroundColor DarkGray
    Write-Host ""
    Write-Host "Usage:" -ForegroundColor White
    Write-Host "  .\aegis.ps1 help                    This help"
    Write-Host "  .\aegis.ps1 start                   Infra + apps (frontend dev)"
    Write-Host "  .\aegis.ps1 start frontend=prod     Frontend prod (build + serve)"
    Write-Host "  .\aegis.ps1 stop                    Stop everything"
    Write-Host "  .\aegis.ps1 appstop                 Stop apps only (keep infra)"
    Write-Host "  .\aegis.ps1 status                  Show status"
    Write-Host "  .\aegis.ps1 build                   Build JAR + frontend dist"
    Write-Host "  .\aegis.ps1 restart                 appstop + build + start"
    Write-Host "  .\aegis.ps1 infra                   Toggle infra only"
    Write-Host ""
    Write-Host "Pre-requisites (auto-detected, set env vars to override):" -ForegroundColor White
    Write-Host "  Docker Desktop + Compose v2"
    Write-Host "  JDK 21+    (JAVA_HOME or PATH)"
    Write-Host "  Maven 3.9+ (MVN_CMD or PATH)"
    Write-Host "  Node.js 18+ (PATH)"
    Write-Host ""
    Write-Host "URLs:" -ForegroundColor White
    Write-Host "  Frontend: http://localhost:5173  (dev) / http://localhost:80  (prod)"
    Write-Host "  Gateway:  http://localhost:8080"
    Write-Host "  Admin:    http://localhost:8082"
    Write-Host "  Runtime:  http://localhost:8081"
    Write-Host "  MCP-Demo: http://localhost:8084/sse"
    Write-Host "  Nacos:    http://localhost:8848/nacos  (nacos/nacos)"
    Write-Host "  MinIO:    http://localhost:9001  (aegis/aegis12345)"
    Write-Host "  MySQL:    localhost:3306  (root/root123)"
    Write-Host ""
    Write-Host "Default login: DEFAULT / admin / aegis@123" -ForegroundColor DarkGray
    Write-Host ""
}

# =============================================================================
# Main (each action is an independent if-block, exits after execution)
# =============================================================================
if ($Action -eq "help") { Show-Help; exit 0 }

Write-Host ""
Write-Host "========================================" -ForegroundColor DarkCyan
Write-Host "  Aegis Platform Unified Manager (Win)" -ForegroundColor DarkCyan
Write-Host "========================================" -ForegroundColor DarkCyan
Write-Host ""

if ($Action -eq "status") {
    Show-InfraStatus
    Write-Host ""
    Show-BackendStatus
    Write-Host ""
    Show-FrontendStatus
    exit 0
}

if ($Action -eq "infra") {
    $allUp = $true
    foreach ($c in $INFRA_CONTAINERS) {
        $r = docker inspect -f "{{.State.Running}}" $c 2>$null
        if ($r -ne "true") { $allUp = $false; break }
    }
    if ($allUp) {
        Write-Info "All infra running, stopping..."
        Stop-Infra
    } else {
        Resolve-Env | Out-Null
        Start-Infra
    }
    exit 0
}

if ($Action -eq "build") {
    Resolve-Env | Out-Null
    if (-not (Build-Backend -Clean)) {
        Write-Err "Backend build failed. Skipping frontend."
        exit 1
    }

    Write-Info "Building frontend dist..."
    Push-Location $FRONTEND_ROOT
    if (-not (Test-Path "node_modules")) {
        Write-Info "Installing frontend deps (first run)..."
        & $NPM_CMD install 2>&1 | Tee-Object -FilePath (Join-Path $LOG_DIR "npm-install.log")
        if ($LASTEXITCODE -ne 0) {
            Write-Err "npm install FAILED. See: $LOG_DIR\npm-install.log"
            Pop-Location; exit 1
        }
    }
    & npx vite build 2>&1 | Tee-Object -FilePath (Join-Path $LOG_DIR "vite-build.log")
    $feRc = $LASTEXITCODE
    Pop-Location
    if ($feRc -ne 0 -or -not (Test-Path (Join-Path $FRONTEND_ROOT "dist"))) {
        Write-Err "Frontend build FAILED (exit=$feRc). See: $LOG_DIR\vite-build.log"
        exit 1
    }
    Write-Ok "Frontend build done"
    exit 0
}

if ($Action -eq "stop") {
    Stop-Frontend
    Stop-Backend
    Stop-Infra
    Write-Host ""
    Write-Ok "All stopped"
    exit 0
}

if ($Action -eq "appstop") {
    Write-Info "Stopping local apps only (keeping infra containers)..."
    Stop-Frontend
    Stop-Backend
    Write-Host ""
    Write-Ok "Local apps stopped, infra kept running"
    exit 0
}

if ($Action -eq "start") {
    Resolve-Env | Out-Null
    $infraOk = Start-Infra
    if ($infraOk) {
        Write-Info "DB init note: MySQL container auto-runs infra/ddl/*.sql on FIRST empty boot"
        Write-Info "If infra was already up, DB is already initialized."
        if (-not (Build-Backend -Clean)) {
            Write-Err "Backend build failed. Aborting start."
            exit 1
        }
        if (-not (Invoke-FrontendBuild -Mode $Frontend)) {
            Write-Err "Frontend build failed. Aborting start."
            exit 1
        }
        Start-Backend
        Start-Frontend -Mode $Frontend
        Write-Host ""
        Write-Ok "All services started!"
        Write-Host ""
        $fePort = if ($Frontend -eq "prod") { 80 } else { 5173 }
        Write-Host "  Frontend: http://localhost:$fePort" -ForegroundColor DarkGray
        Write-Host "  Gateway:  http://localhost:8080" -ForegroundColor DarkGray
        Write-Host "  Admin:    http://localhost:8082" -ForegroundColor DarkGray
        Write-Host "  Runtime:  http://localhost:8081" -ForegroundColor DarkGray
        Write-Host "  MCP-Demo: http://localhost:8084/sse" -ForegroundColor DarkGray
        Write-Host "  Nacos:    http://localhost:8848/nacos" -ForegroundColor DarkGray
        Write-Host "  MinIO:    http://localhost:9001" -ForegroundColor DarkGray
        Write-Host "  Logs:     $LOG_DIR" -ForegroundColor DarkGray
        Write-Host ""
        Write-Host "  Login: DEFAULT / admin / aegis@123" -ForegroundColor DarkGray
    } else {
        Write-Err "Infra startup failed, check Docker Desktop"
    }
    exit 0
}

if ($Action -eq "restart") {
    Write-Info "Restart: appstop -> build -> start"
    Stop-Frontend
    Stop-Backend
    Start-Sleep -Seconds 2
    Resolve-Env | Out-Null
    if (-not (Build-Backend -Clean)) {
        Write-Err "Backend build failed. Aborting restart."
        exit 1
    }
    if (-not (Invoke-FrontendBuild -Mode $Frontend)) {
        Write-Err "Frontend build failed. Aborting restart."
        exit 1
    }
    Push-Location $INFRA_ROOT
    docker compose up -d mysql redis nacos minio etcd milvus 2>&1 | Out-Null
    Pop-Location

    Start-Backend
    Start-Frontend -Mode $Frontend
    Write-Host ""
    Write-Ok "Restart done"
    exit 0
}

Write-Err "Unknown action: $Action"
Show-Help
exit 1