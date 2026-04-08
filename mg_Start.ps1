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

$Services = @{
    PostgreSQL = @{ Port = 5432; ContainerName = "mindgraph-db" }
    RabbitMQ   = @{ Port = 15672; ContainerName = "mindgraph-rabbitmq" }
    Neo4j      = @{ Port = 7474; ContainerName = "mindgraph-neo4j" }
    Ollama     = @{ Port = 11434; ContainerName = "mindgraph-ollama" }
}
$ChatModel = "qwen2.5:14b"; $EmbeddingModel = "mxbai-embed-large"

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
        Write-Host "`n[1/3] Starting Docker services..." -ForegroundColor Cyan
        Invoke-WslCommand "docker compose up -d"

        Write-Host "`n[2/3] Checking ports..." -ForegroundColor Cyan
        foreach ($s in $Services.GetEnumerator()) {
            Write-Host "  - $($s.Key) waiting..." -NoNewline
            while (-not (Test-NetConnection -ComputerName "localhost" -Port $s.Value.Port -InformationLevel Quiet)) {
                Start-Sleep -Seconds 2; Write-Host "." -NoNewline
            }
            Write-Host " OK!" -ForegroundColor Green
        }

        Write-Host "`n[3/4] Opening dashboards..." -ForegroundColor Cyan
        Start-Process "http://localhost:15672" # RabbitMQ
        Start-Process "http://localhost:7474"  # Neo4j

        Write-Host "`n[4/4] Warming up GPU (loading qwen2.5:14b into VRAM)..." -ForegroundColor Cyan
        try {
            Invoke-RestMethod -Uri "http://localhost:11434/api/generate" -Method Post `
                -Body '{"model":"qwen2.5:14b","prompt":"hi","stream":false}' `
                -ContentType "application/json" | Out-Null
        } catch {}
        Write-Host "  - qwen2.5:14b loaded!" -ForegroundColor Green

        Write-Host "`nAll systems ready!" -ForegroundColor Green
    }
    'Stop' {
        Write-Host "`n[1/2] Unloading model from VRAM..." -ForegroundColor Cyan
        try {
            Invoke-RestMethod -Uri "http://localhost:11434/api/generate" -Method Post `
                -Body '{"model":"qwen2.5:14b","keep_alive":0}' `
                -ContentType "application/json" | Out-Null
        } catch {}
        Write-Host "  - qwen2.5:14b unloaded!" -ForegroundColor Green

        Write-Host "`n[2/2] Stopping Docker services..." -ForegroundColor Cyan
        Invoke-WslCommand "docker compose down"
        Write-Host "`nAll systems stopped!" -ForegroundColor Green
    }
    'Logs' { Invoke-WslCommand "docker compose logs -f $Service" }
}

Write-Host "`n======================================================="
Read-Host "Done. Press Enter to exit."
