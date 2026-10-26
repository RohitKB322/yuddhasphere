# YuddhaSphere Authoritative Multiplayer WebSocket Server Runner
$env:PATH = "C:\Program Files\nodejs;" + $env:PATH
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   YuddhaSphere v0.4 / v0.5 Authoritative Server Engine   " -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Starting WebSocket server on ws://0.0.0.0:8080 ..." -ForegroundColor Green

Set-Location -Path "$PSScriptRoot\server"
& "C:\Program Files\nodejs\node.exe" dist/server.js
