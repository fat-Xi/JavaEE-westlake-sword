param([ValidateSet("web","server")][string]$Part="server")
$ErrorActionPreference="Stop"
Set-Location $PSScriptRoot
if($Part -eq "web") {
  $env:API_TARGET="http://127.0.0.1:8083"
  Set-Location web
  node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort
} else {
  # 密钥仅加载到后端进程，不输出到终端或网页。
  if(Test-Path .env.ai.local) {
    Get-Content .env.ai.local | ForEach-Object {
      if($_ -match '^(AI_[A-Z_]+)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1],$matches[2].Trim().Trim('"').Trim("'"),"Process")
      }
    }
  }
  $env:SERVER_PORT="8083"
  java -jar target/westlake-sword-0.0.1-SNAPSHOT.jar
}
