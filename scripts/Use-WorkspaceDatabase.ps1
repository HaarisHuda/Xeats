$backendRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $backendRoot
$workspaceEnvironmentPath = Join-Path $workspaceRoot 'infra\.env'

if (-not (Test-Path -LiteralPath $workspaceEnvironmentPath)) {
    throw 'The shared development database credentials are missing. Run ..\scripts\Start-BackendDatabase.ps1 first.'
}

$environmentText = Get-Content -LiteralPath $workspaceEnvironmentPath -Raw
$passwordMatch = [regex]::Match($environmentText, '(?m)^MYSQL_PASSWORD=(.*)$')
if (-not $passwordMatch.Success -or [string]::IsNullOrWhiteSpace($passwordMatch.Groups[1].Value)) {
    throw 'MYSQL_PASSWORD is missing from the shared development database environment file.'
}

$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://127.0.0.1:3307/xeats?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:SPRING_DATASOURCE_USERNAME = 'xeats_app'
$env:SPRING_DATASOURCE_PASSWORD = $passwordMatch.Groups[1].Value.Trim()
Write-Host 'Loaded Xeats shared development database settings into this PowerShell session.'
