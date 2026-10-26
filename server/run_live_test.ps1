$env:PATH = "C:\Program Files\nodejs;" + $env:PATH
Set-Location -Path "$PSScriptRoot"

Write-Host "Starting authoritative server..." -ForegroundColor Cyan
$serverProcess = Start-Process -FilePath "C:\Program Files\nodejs\node.exe" -ArgumentList "dist/server.js" -PassThru -NoNewWindow

Start-Sleep -Seconds 1

Write-Host "Running live multi-client synchronization test..." -ForegroundColor Yellow
& "C:\Program Files\nodejs\node.exe" dist/test_live_sync.js

$exitCode = $LASTEXITCODE

Write-Host "Stopping test server (PID: $($serverProcess.Id))..."
Stop-Process -Id $serverProcess.Id -Force

exit $exitCode
