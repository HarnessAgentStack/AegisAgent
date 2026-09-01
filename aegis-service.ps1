<#
.SYNOPSIS
    Aegis Platform Service Manager (start/stop/status/build/restart)
.DESCRIPTION
    环境变量约定（未设置时尝试自动探测，探测失败则报错退出）：
      JAVA_HOME   - JDK 安装目录（或 PATH 含 java）
      MVN_CMD     - mvn 可执行文件路径（或 PATH 含 mvn）
      AEGIS_HOME  - 工程根目录（默认脚本所在目录）
      DOCKER_EXE  - Docker Desktop 可执行文件路径（默认探测 Program Files）
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet("start", "stop", "status", "build", "restart")]
    [string]$Action = "status"
)

$ErrorActionPreference = "Continue"

# ============================================================================
# Config（环境变量 + 优雅回退，避免硬编码开发机绝对路径）
# ============================================================================
function Resolve-JavaHome {
    if ($env:JAVA_HOME) { return $env:JAVA_HOME }
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd) {
        $javaBin = (Get-Item $javaCmd.Source).Directory.FullName
        if (Test-Path (Join-Path $javaBin "java.exe")) { return (Split-Path $javaBin -Parent) }
    }
    return $null
}
function Resolve-MvnCmd {
    if ($env:MVN_CMD -and (Test-Path $env:MVN_CMD)) { return $env:MVN_CMD }
    $mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvnCmd) { return $mvnCmd.Source }
    return $null
}

$JAVA_HOME     = Resolve-JavaHome
if (-not $JAVA_HOME) { Write-Host "[ERROR] JAVA_HOME 未设置且 PATH 无 java，请设置 JAVA_HOME 环境变量" -ForegroundColor Red; exit 1 }
$JAVA_EXE      = "$JAVA_HOME\bin\java.exe"
if (-not (Test-Path $JAVA_EXE)) { Write-Host "[ERROR] java.exe 未找到: $JAVA_EXE" -ForegroundColor Red; exit 1 }

$MVN_CMD       = Resolve-MvnCmd
if (-not $MVN_CMD) { Write-Host "[ERROR] MVN_CMD 未设置且 PATH 无 mvn，请设置 MVN_CMD 环境变量" -ForegroundColor Red; exit 1 }

$PROJECT_ROOT  = $env:AEGIS_HOME
if (-not $PROJECT_ROOT) { $PROJECT_ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path }
$BACKEND_ROOT  = "$PROJECT_ROOT\aegis-platform-backend"
$FRONTEND_ROOT = "$PROJECT_ROOT\aegis-platform-web"
$INFRA_ROOT    = "$PROJECT_ROOT\infra"
$LOG_DIR       = "$PROJECT_ROOT\logs"

$BACKEND_SERVICES = @(
    @{ Name = "gateway";  Jar = "$BACKEND_ROOT\aegis-gateway\target\aegis-gateway-0.1.0-alpha.1.jar";     Port = 8080 }
    @{ Name = "admin";    Jar = "$BACKEND_ROOT\aegis-admin\target\aegis-admin-0.1.0-alpha.1.jar";        Port = 8082 }
    @{ Name = "runtime";  Jar = "$BACKEND_ROOT\aegis-runtime\target\aegis-runtime-0.1.0-alpha.1-exec.jar"; Port = 8081 }
    @{ Name = "mcp-demo"; Jar = "$BACKEND_ROOT\aegis-mcp-demo\target\aegis-mcp-demo-0.1.0-alpha.1.jar";    Port = 8084 }
)

$DOCKER_CONTAINERS = @("aegis-mysql", "aegis-redis", "aegis-nacos", "aegis-minio", "aegis-etcd", "aegis-milvus", "aegis-paddleocr")

# ============================================================================
# Helpers
# ============================================================================
function Write-Info($m)  { Write-Host "[INFO]  $m" -ForegroundColor Cyan }
function Write-Ok($m)    { Write-Host "[OK]    $m" -ForegroundColor Green }
function Write-Warn2($m) { Write-Host "[WARN]  $m" -ForegroundColor Yellow }
function Write-Err2($m)  { Write-Host "[ERROR] $m" -ForegroundColor Red }

function Test-Port($port) {
    return $null -ne (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

function Get-ProcIdOnPort($port) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($conn) { return $conn.OwningProcess }
    return $null
}

function Wait-PortReady($port, $timeoutSec, $name) {
    $elapsed = 0
    while ($elapsed -lt $timeoutSec) {
        if (Test-Port $port) { return $true }
        Start-Sleep -Seconds 2
        $elapsed += 2
    }
    return $false
}

function Ensure-LogDir {
    if (-not (Test-Path $LOG_DIR)) { New-Item -ItemType Directory -Path $LOG_DIR -Force | Out-Null }
}

# ============================================================================
# Docker / Infra
# ============================================================================
function Start-Infra {
    Write-Info "Starting Docker containers..."

    docker info 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Warn2 "Docker Desktop not running. Starting it..."
        $dockerExe = $env:DOCKER_EXE
        if (-not $dockerExe) {
            @("${env:ProgramFiles}\Docker\Docker\Docker Desktop.exe",
              "${env:ProgramFiles(x86)}\Docker\Docker\Docker Desktop.exe") | ForEach-Object {
                if (-not $dockerExe -and $_ -and (Test-Path $_)) { $dockerExe = $_ }
            }
        }
        if ($dockerExe -and (Test-Path $dockerExe)) {
            Start-Process -FilePath $dockerExe
            $elapsed = 0
            while ($elapsed -lt 90) {
                docker info 2>$null | Out-Null
                if ($LASTEXITCODE -eq 0) { break }
                Start-Sleep -Seconds 5
                $elapsed += 5
            }
            if ($elapsed -ge 90) { Write-Err2 "Docker Desktop startup timeout"; return $false }
            Write-Ok "Docker Desktop ready"
        } else {
            Write-Err2 "Docker Desktop not found. 请设置 DOCKER_EXE 环境变量指向 Docker Desktop.exe"
            return $false
        }
    }

    Push-Location $INFRA_ROOT
    docker compose up -d mysql redis nacos minio etcd milvus 2>&1 | Out-Null

    # PaddleOCR 兜底 OCR 服务（首次需要 build，之后可直接 up）
    $paddleocrBuilt = docker images --format "{{.Repository}}:{{.Tag}}" 2>$null | Where-Object { $_ -match "aegis-paddleocr" }
    if (-not $paddleocrBuilt) {
        Write-Info "PaddleOCR image not found, building first time (~2-3 min)..."
        docker compose build paddleocr 2>&1 | Out-Null
        Write-Ok "PaddleOCR image built"
    }
    docker compose up -d paddleocr 2>&1 | Out-Null
    Pop-Location

    Write-Info "Waiting for infra services..."
    $svcList = @(
        @{ Port = 3306; Name = "MySQL" },
        @{ Port = 6379; Name = "Redis" },
        @{ Port = 8848; Name = "Nacos" }
    )
    foreach ($s in $svcList) {
        if (Wait-PortReady $s.Port 60 $s.Name) {
            Write-Ok "$($s.Name) ready (port $($s.Port))"
        } else {
            Write-Warn2 "$($s.Name) not ready (port $($s.Port))"
        }
    }
    if (Wait-PortReady 9848 30 "Nacos-gRPC") {
        Write-Ok "Nacos gRPC ready (port 9848)"
    } else {
        Write-Warn2 "Nacos gRPC not ready (services may retry registration)"
    }
    if (Wait-PortReady 19530 30 "Milvus") {
        Write-Ok "Milvus ready (port 19530)"
    } else {
        Write-Warn2 "Milvus not ready (optional, NoopVectorStore will be used)"
    }
    if (Wait-PortReady 8098 90 "PaddleOCR") {
        Write-Ok "PaddleOCR ready (port 8098)"
    } else {
        Write-Warn2 "PaddleOCR not ready yet (OCR will fallback to vision LLM)"
    }
    return $true
}

function Stop-Infra {
    Write-Info "Stopping Docker containers..."
    foreach ($c in $DOCKER_CONTAINERS) { docker stop $c 2>$null | Out-Null }
    Write-Ok "Infra stopped"
}

function Show-InfraStatus {
    Write-Info "Docker containers:"
    docker ps -a --format "{{.Names}}`t{{.Status}}" 2>$null | Where-Object { $_ -match "aegis" } | ForEach-Object {
        $parts = $_ -split "`t"
        $color = if ($parts[1] -match "Up") { "Green" } else { "Red" }
        Write-Host "  $($parts[0].PadRight(20)) " -NoNewline -ForegroundColor White
        Write-Host $parts[1] -ForegroundColor $color
    }
}

# ============================================================================
# Backend
# ============================================================================
function Start-Backend {
    Write-Info "Starting backend services..."
    Ensure-LogDir
    $env:JAVA_HOME = $JAVA_HOME

    foreach ($svc in $BACKEND_SERVICES) {
        if (Test-Port $svc.Port) {
            Write-Warn2 "$($svc.Name) port $($svc.Port) already in use, skip"
            continue
        }
        if (-not (Test-Path $svc.Jar)) {
            Write-Err2 "$($svc.Name) JAR not found: $($svc.Jar)"
            Write-Warn2 "Run: .\aegis-service.ps1 build"
            continue
        }

        $jvmArgs = @(
            "-Xms256m",
            "-Xmx512m",
            "-jar",
            $svc.Jar,
            "--spring.profiles.active=dev",
            "--spring.cloud.nacos.config.enabled=false",
            "--networkaddress.cache.ttl=10",
            "--APP_NAME=$($svc.Name)",
            "--LOG_PATH=$LOG_DIR"
        )

        $logFile = "$LOG_DIR\$($svc.Name).log"
        $errFile = "$LOG_DIR\$($svc.Name).err.log"
        Start-Process -FilePath $JAVA_EXE -ArgumentList $jvmArgs -WindowStyle Hidden -RedirectStandardOutput $logFile -RedirectStandardError $errFile
        Write-Info "  $($svc.Name) starting (port $($svc.Port))..."
    }

    Start-Sleep -Seconds 5
    foreach ($svc in $BACKEND_SERVICES) {
        if (Wait-PortReady $svc.Port 60 $svc.Name) {
            Write-Ok "$($svc.Name) ready (port $($svc.Port))"
        } else {
            Write-Err2 "$($svc.Name) failed (port $($svc.Port)). Check: $LOG_DIR\$($svc.Name).log"
        }
    }
}

function Stop-Backend {
    Write-Info "Stopping backend services..."
    foreach ($svc in $BACKEND_SERVICES) {
        $procId = Get-ProcIdOnPort $svc.Port
        if ($procId) {
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            Write-Host "  Stopped: $($svc.Name) (PID $procId)" -ForegroundColor DarkGray
        }
    }
    Start-Sleep -Seconds 2
    Write-Ok "Backend stopped"
}

function Show-BackendStatus {
    Write-Info "Backend services:"
    foreach ($svc in $BACKEND_SERVICES) {
        if (Test-Port $svc.Port) {
            $procId = Get-ProcIdOnPort $svc.Port
            Write-Host "  $($svc.Name.PadRight(10)) port $($svc.Port)  " -NoNewline -ForegroundColor White
            Write-Host "RUNNING (PID $procId)" -ForegroundColor Green
        } else {
            Write-Host "  $($svc.Name.PadRight(10)) port $($svc.Port)  " -NoNewline -ForegroundColor White
            Write-Host "STOPPED" -ForegroundColor Red
        }
    }
}

# ============================================================================
# Frontend
# ============================================================================
function Start-Frontend {
    Write-Info "Starting frontend..."
    if (Test-Port 5173) { Write-Warn2 "Frontend port 5173 already in use, skip"; return }

    if (-not (Test-Path "$FRONTEND_ROOT\node_modules")) {
        Write-Info "Installing frontend deps..."
        Push-Location $FRONTEND_ROOT
        npm install 2>&1 | Out-Null
        Pop-Location
    }

    Ensure-LogDir
    $logFile = "$LOG_DIR\frontend.log"
    $cmdLine = "cd /d `"$FRONTEND_ROOT`" && npx vite --host"
    Start-Process -FilePath "cmd.exe" -ArgumentList "/c", $cmdLine -WindowStyle Hidden -RedirectStandardOutput $logFile

    if (Wait-PortReady 5173 30 "Frontend") {
        Write-Ok "Frontend ready (port 5173)"
    } else {
        Write-Err2 "Frontend failed. Check: $logFile"
    }
}

function Stop-Frontend {
    Write-Info "Stopping frontend..."
    $procId = Get-ProcIdOnPort 5173
    if ($procId) {
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
        Write-Host "  Stopped: Frontend (PID $procId)" -ForegroundColor DarkGray
    }
    Write-Ok "Frontend stopped"
}

function Show-FrontendStatus {
    Write-Info "Frontend:"
    if (Test-Port 5173) {
        $procId = Get-ProcIdOnPort 5173
        Write-Host "  Frontend   port 5173   " -NoNewline -ForegroundColor White
        Write-Host "RUNNING (PID $procId)" -ForegroundColor Green
    } else {
        Write-Host "  Frontend   port 5173   " -NoNewline -ForegroundColor White
        Write-Host "STOPPED" -ForegroundColor Red
    }
}

# ============================================================================
# Build
# ============================================================================
function Build-Backend {
    Write-Info "Building backend..."
    $env:JAVA_HOME = $JAVA_HOME
    Push-Location $BACKEND_ROOT
    & $MVN_CMD clean package -DskipTests -q 2>&1 | ForEach-Object {
        if ($_ -match "ERROR|BUILD FAILURE") { Write-Err2 $_ }
        elseif ($_ -match "BUILD SUCCESS")   { Write-Ok "Build success" }
    }
    Pop-Location
    Write-Ok "Backend build complete"
}

function Build-Frontend {
    Write-Info "Building frontend..."
    Push-Location $FRONTEND_ROOT
    if (-not (Test-Path "$FRONTEND_ROOT\node_modules")) {
        Write-Info "Installing frontend deps..."
        npm install 2>&1 | Out-Null
    }
    & npx vite build 2>&1 | ForEach-Object {
        if ($_ -match "error|ERROR|failed") { Write-Err2 $_ }
        elseif ($_ -match "✓|build complete") { Write-Ok "Build success" }
    }
    Pop-Location
    Write-Ok "Frontend build complete"
}

function Clear-Logs {
    Write-Info "Clearing logs..."
    if (Test-Path $LOG_DIR) {
        Get-ChildItem -Path $LOG_DIR -File -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
        Get-ChildItem -Path $LOG_DIR -Directory -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
        Write-Ok "Logs cleared"
    } else {
        Write-Info "Logs directory does not exist, skip"
    }
}

# ============================================================================
# Main
# ============================================================================
Write-Host ""
Write-Host "========================================" -ForegroundColor DarkCyan
Write-Host "  Aegis Platform Service Manager" -ForegroundColor DarkCyan
Write-Host "========================================" -ForegroundColor DarkCyan
Write-Host ""

switch ($Action) {
    "start" {
        $infraOk = Start-Infra
        if ($infraOk) {
            Start-Backend
            Start-Frontend
            Write-Host ""
            Write-Ok "All services started"
            Write-Host ""
            Write-Host "  Gateway:  http://localhost:8080" -ForegroundColor DarkGray
            Write-Host "  Admin:    http://localhost:8082" -ForegroundColor DarkGray
            Write-Host "  Runtime:  http://localhost:8081" -ForegroundColor DarkGray
            Write-Host "  MCP-Demo: http://localhost:8084" -ForegroundColor DarkGray
            Write-Host "  Frontend: http://localhost:5173" -ForegroundColor DarkGray
            Write-Host "  Nacos:    http://localhost:8848" -ForegroundColor DarkGray
            Write-Host "  MinIO:    http://localhost:9001" -ForegroundColor DarkGray
            Write-Host "  Logs:     $LOG_DIR" -ForegroundColor DarkGray
        } else {
            Write-Err2 "Infra startup failed. Check Docker Desktop."
        }
    }
    "stop" {
        Stop-Frontend
        Stop-Backend
        Stop-Infra
        Write-Host ""
        Write-Ok "All services stopped"
    }
    "restart" {
        Write-Info "Restarting all services (stop -> clear logs -> build -> start)..."
        Stop-Frontend
        Stop-Backend
        Clear-Logs
        Build-Backend
        Build-Frontend
        Start-Sleep -Seconds 3
        $infraOk = Start-Infra
        if ($infraOk) {
            Start-Backend
            Start-Frontend
            Write-Host ""
            Write-Ok "All services restarted"
            Write-Host ""
            Write-Host "  Gateway:  http://localhost:8080" -ForegroundColor DarkGray
            Write-Host "  Admin:    http://localhost:8082" -ForegroundColor DarkGray
            Write-Host "  Runtime:  http://localhost:8081" -ForegroundColor DarkGray
            Write-Host "  MCP-Demo: http://localhost:8084" -ForegroundColor DarkGray
            Write-Host "  Frontend: http://localhost:5173" -ForegroundColor DarkGray
            Write-Host "  Nacos:    http://localhost:8848" -ForegroundColor DarkGray
            Write-Host "  MinIO:    http://localhost:9001" -ForegroundColor DarkGray
            Write-Host "  Logs:     $LOG_DIR" -ForegroundColor DarkGray
        } else {
            Write-Err2 "Infra startup failed. Check Docker Desktop."
        }
    }
    "status" {
        Show-InfraStatus
        Write-Host ""
        Show-BackendStatus
        Write-Host ""
        Show-FrontendStatus
    }
    "build" {
        Build-Backend
    }
}

Write-Host ""
