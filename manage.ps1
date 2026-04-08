# ============================================================================
# MindGraph-AI Manager (Auto-Path Optimized)
# ============================================================================
param (
    [ValidateSet('Start', 'Stop', 'Logs', 'Backup', 'Init')]
    [string]$Mode = 'Start',
    [string]$Service
)

$WslDistro = "Ubuntu"
$WslUser = "kdw03"

# Auto-detect WSL path from script location
try {
    $WinPath = $PSScriptRoot
    if ([string]::IsNullOrEmpty($WinPath)) { $WinPath = Get-Location }

    # \\wsl.localhost\Ubuntu\... UNC path: convert directly without wslpath
    if ($WinPath -match '^\\\\wsl\.localhost\\[^\\]+(.+)$') {
        $ProjectPathInWsl = $Matches[1].Replace('\', '/')
    } else {
        $ProjectPathInWsl = wsl -d $WslDistro -u $WslUser -- wslpath -u "$WinPath"
        $ProjectPathInWsl = $ProjectPathInWsl.Trim()
    }
} catch {
    Write-Host "[ERROR] WSL connection failed. Make sure WSL is running." -ForegroundColor Red
    Read-Host "Press Enter to exit..."
    exit 1
}

# ============================================================================
# 환경 변수 분리: .env 파일에서 설정값 로드
# .env 파일이 없으면 기본값을 사용하고 경고 출력
# ============================================================================
$EnvFile = Join-Path $PSScriptRoot ".env"
$EnvVars = @{}

if (Test-Path $EnvFile) {
    Write-Host "[ENV] Loading environment variables from .env..." -ForegroundColor DarkGray
    Get-Content $EnvFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line -match "^([^=]+)=(.*)$") {
            $EnvVars[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
    Write-Host "[ENV] Loaded $($EnvVars.Count) variables." -ForegroundColor DarkGray
} else {
    Write-Host "[ENV] .env not found — using defaults." -ForegroundColor Yellow
}

# 환경 변수 우선, 없으면 기본값
$OllamaHost = if ($EnvVars["OLLAMA_HOST"]) { $EnvVars["OLLAMA_HOST"] } else { "localhost" }
$OllamaPort = if ($EnvVars["OLLAMA_PORT"]) { [int]$EnvVars["OLLAMA_PORT"] } else { 11434 }
$ChatModel     = if ($EnvVars["CHAT_MODEL"])      { $EnvVars["CHAT_MODEL"] }      else { "qwen2.5:14b" }
$EmbeddingModel = if ($EnvVars["EMBEDDING_MODEL"]) { $EnvVars["EMBEDDING_MODEL"] } else { "mxbai-embed-large" }

$Services = @{
    PostgreSQL = @{ Port = 5432;  ContainerName = "mindgraph-db" }
    RabbitMQ   = @{ Port = 15672; ContainerName = "mindgraph-rabbitmq" }
    Neo4j      = @{ Port = 7474;  ContainerName = "mindgraph-neo4j" }
    Ollama     = @{ Port = $OllamaPort; ContainerName = "mindgraph-ollama" }
}

function Invoke-WslCommand {
    param([string]$Command, [bool]$Fatal = $true)
    wsl -d $WslDistro -u $WslUser -- bash -c "cd '$ProjectPathInWsl' && $Command"
    if ($LASTEXITCODE -ne 0 -and $Fatal) {
        Write-Host "[ERROR] Command failed: $Command" -ForegroundColor Red
        Read-Host "Press Enter to continue..."
        exit 1
    }
}

switch ($Mode) {
    'Start' {
        Write-Host "`n[1/5] Starting Docker services..." -ForegroundColor Cyan
        Invoke-WslCommand "docker compose up -d"

        Write-Host "`n[2/5] Checking ports (Wait-for-it)..." -ForegroundColor Cyan
        foreach ($s in $Services.GetEnumerator()) {
            Write-Host "  - $($s.Key) waiting..." -NoNewline
            while (-not (Test-NetConnection -ComputerName "localhost" -Port $s.Value.Port -InformationLevel Quiet)) {
                Start-Sleep -Seconds 2; Write-Host "." -NoNewline
            }
            Write-Host " OK!" -ForegroundColor Green
        }

        Write-Host "`n[3/5] Opening dashboards..." -ForegroundColor Cyan
        Start-Process "http://localhost:15672" # RabbitMQ Management UI
        Start-Process "http://localhost:7474"  # Neo4j Browser
        Write-Host "  - RabbitMQ: http://localhost:15672" -ForegroundColor DarkGray
        Write-Host "  - Neo4j:    http://localhost:7474"  -ForegroundColor DarkGray

        Write-Host "`n[4/5] Warming up GPU (loading models into VRAM)..." -ForegroundColor Cyan

        # Chat 모델 워밍업 (Qwen 2.5 14B)
        Write-Host "  - Loading $ChatModel..." -NoNewline
        try {
            Invoke-RestMethod -Uri "http://${OllamaHost}:${OllamaPort}/api/generate" -Method Post `
                -Body "{`"model`":`"$ChatModel`",`"prompt`":`"hi`",`"stream`":false}" `
                -ContentType "application/json" | Out-Null
            Write-Host " loaded!" -ForegroundColor Green
        } catch {
            Write-Host " failed (model may not be pulled yet)" -ForegroundColor Yellow
        }

        # 임베딩 모델 워밍업 (mxbai-embed-large)
        Write-Host "  - Loading $EmbeddingModel..." -NoNewline
        try {
            Invoke-RestMethod -Uri "http://${OllamaHost}:${OllamaPort}/api/embed" -Method Post `
                -Body "{`"model`":`"$EmbeddingModel`",`"input`":`"warmup`"}" `
                -ContentType "application/json" | Out-Null
            Write-Host " loaded!" -ForegroundColor Green
        } catch {
            Write-Host " failed (model may not be pulled yet)" -ForegroundColor Yellow
        }

        Write-Host "`n[5/5] Streaming container logs (Ctrl+C to stop)..." -ForegroundColor Cyan
        Write-Host "  Tip: Run '.\manage.ps1 -Mode Logs -Service ollama' to filter by service." -ForegroundColor DarkGray
        Write-Host "  Press Ctrl+C to stop log stream and return to prompt.`n" -ForegroundColor DarkGray
        try {
            Invoke-WslCommand "docker compose logs -f --tail=20" $false
        } catch { }

        Write-Host "`nAll systems ready!" -ForegroundColor Green
    }
    'Stop' {
        Write-Host "`n[1/3] Unloading models from VRAM..." -ForegroundColor Cyan
        try {
            Invoke-RestMethod -Uri "http://${OllamaHost}:${OllamaPort}/api/generate" -Method Post `
                -Body "{`"model`":`"$ChatModel`",`"keep_alive`":0}" `
                -ContentType "application/json" | Out-Null
            Write-Host "  - $ChatModel unloaded!" -ForegroundColor Green
        } catch { Write-Host "  - $ChatModel unload skipped." -ForegroundColor Yellow }

        Write-Host "`n[2/3] Stopping Docker services..." -ForegroundColor Cyan
        Invoke-WslCommand "docker compose down"
        Write-Host "`nAll systems stopped!" -ForegroundColor Green
    }
    'Logs' {
        if ($Service) {
            Write-Host "`nStreaming logs for: $Service" -ForegroundColor Cyan
            Invoke-WslCommand "docker compose logs -f $Service"
        } else {
            Write-Host "`nStreaming logs for all services..." -ForegroundColor Cyan
            Invoke-WslCommand "docker compose logs -f"
        }
    }
}

Write-Host "`n======================================================="
Read-Host "Done. Press Enter to exit."
