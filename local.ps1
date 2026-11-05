param(
    [ValidateSet('start', 'stop', 'status', 'restart-backend')]
    [string]$Action = 'start'
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$configPath = Join-Path $projectRoot '.env.local'
if (-not (Test-Path -LiteralPath $configPath)) {
    throw 'Missing .env.local. See LOCAL-RUN.md for this computer setup.'
}
foreach ($line in Get-Content -LiteralPath $configPath -Encoding UTF8) {
    if ($line -match '^([A-Z_]+)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}

$runtimeRoot = $env:WESTLAKE_RUNTIME_DIR
$logRoot = Join-Path $runtimeRoot 'logs'
$mysqlData = Join-Path $runtimeRoot 'mysql-data'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$node = Join-Path $env:WESTLAKE_NODE_DIR 'node.exe'
$env:MAVEN_USER_HOME = Join-Path $runtimeRoot 'maven'
$mavenRepository = Join-Path $runtimeRoot 'maven-repository'
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:3307/westlake_sword?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8080'
$env:API_TARGET = 'http://127.0.0.1:8080'
$env:APP_UPLOAD = Join-Path $projectRoot 'uploads'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null

function Test-Port([int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $pending = $client.ConnectAsync('127.0.0.1', $Port)
        return ($pending.Wait(400) -and $client.Connected)
    } catch { return $false }
    finally { $client.Dispose() }
}

function Get-OwnedProcess([string]$Name) {
    $recordPath = Join-Path $runtimeRoot ($Name + '.process.json')
    if (-not (Test-Path -LiteralPath $recordPath)) { return $null }
    $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
    $running = Get-Process -Id $record.id -ErrorAction SilentlyContinue
    if ($running -and
        $running.StartTime.ToUniversalTime().Ticks.ToString() -eq $record.started -and
        $running.Path -eq $record.executable) { return $running }
    return $null
}

function Save-Process([string]$Name, $Process, [string]$Executable) {
    @{
        id = $Process.Id
        started = $Process.StartTime.ToUniversalTime().Ticks.ToString()
        executable = [System.IO.Path]::GetFullPath($Executable)
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimeRoot ($Name + '.process.json')) -Encoding UTF8
}

function Stop-OwnedProcess([string]$Name) {
    $running = Get-OwnedProcess $Name
    if ($running) {
        Stop-Process -Id $running.Id
        $running.WaitForExit(10000) | Out-Null
        Write-Host "Stopped $Name."
    }
}

function Start-OwnedProcess([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$Directory, [int]$Port) {
    if (Test-Port $Port) {
        if (Get-OwnedProcess $Name) {
            Write-Host "$Name is already running on $Port."
            return
        }
        throw "Port $Port is occupied by another process. Check it before starting $Name."
    }
    $running = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $Directory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot ($Name + '.log')) -RedirectStandardError (Join-Path $logRoot ($Name + '.error.log'))
    Save-Process $Name $running $Executable
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while ([DateTime]::UtcNow -lt $deadline) {
        $running.Refresh()
        if ($running.HasExited) { throw "$Name exited. Check $logRoot\$Name.error.log and $Name.log." }
        if (Test-Port $Port) {
            Write-Host "Started $Name on $Port."
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name did not become ready within 45 seconds. Check $logRoot."
}

if ($Action -eq 'status') {
    foreach ($service in @(@('mysql', 3307), @('backend', 8080), @('frontend', 5173))) {
        $ready = Test-Port $service[1]
        $owned = [bool](Get-OwnedProcess $service[0])
        Write-Host "$($service[0]): port=$($service[1]) reachable=$ready managed=$owned"
    }
    exit 0
}

if ($Action -eq 'stop') {
    Stop-OwnedProcess 'frontend'
    Stop-OwnedProcess 'backend'
    if (Get-OwnedProcess 'mysql') {
        & (Join-Path $env:WESTLAKE_MYSQL_BIN 'mysqladmin.exe') --no-defaults --protocol=TCP -h 127.0.0.1 -P 3307 -u root shutdown
        if ($LASTEXITCODE -ne 0) { throw 'MySQL shutdown failed.' }
        Write-Host 'Stopped mysql. Database files are retained.'
    }
    exit 0
}

if ($Action -eq 'restart-backend') { Stop-OwnedProcess 'backend' }
if (-not (Test-Path -LiteralPath (Join-Path $mysqlData 'auto.cnf'))) {
    throw 'The local database is not initialized. See LOCAL-RUN.md.'
}
Start-OwnedProcess 'mysql' (Join-Path $env:WESTLAKE_MYSQL_BIN 'mysqld.exe') @('--no-defaults', '--console', '--datadir=.', '--port=3307', '--bind-address=127.0.0.1', '--skip-log-bin', '--mysqlx=0') $mysqlData 3307

if (-not (Get-OwnedProcess 'backend')) {
    if (Test-Port 8080) { throw 'Port 8080 is occupied by another process.' }
    Push-Location $projectRoot
    try {
        Write-Host 'Building backend...'
        & (Join-Path $projectRoot 'mvnw.cmd') "-Dmaven.repo.local=$mavenRepository" --batch-mode --no-transfer-progress -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    } finally { Pop-Location }
}
$jar = Join-Path $projectRoot 'target\westlake-sword-0.0.1-SNAPSHOT.jar'
Start-OwnedProcess 'backend' $java @('-jar', ('"' + $jar + '"')) $projectRoot 8080
if ($Action -ne 'restart-backend') {
    $vite = Join-Path $projectRoot 'web\node_modules\vite\bin\vite.js'
    if (-not (Test-Path -LiteralPath $vite)) { throw 'Frontend dependencies are missing. Run npm ci in web.' }
    Start-OwnedProcess 'frontend' $node @(('"' + $vite + '"'), '--host', '127.0.0.1', '--port', '5173', '--strictPort') (Join-Path $projectRoot 'web') 5173
}
Write-Host 'Open http://127.0.0.1:5173'
Write-Host "Logs: $logRoot"
