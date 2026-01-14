# HyperIsle Anchor Mode Enable Script
Write-Host "HyperIsle Anchor Mode Enable Script" -ForegroundColor Green
Write-Host "==================================" -ForegroundColor Green

# Check if HyperIsle is installed
$packageCheck = adb shell pm list packages | Select-String "com.coni.hyperisle"
if (-not $packageCheck) {
    Write-Host "ERROR: HyperIsle not installed" -ForegroundColor Red
    exit 1
}

Write-Host "HyperIsle found, enabling Anchor Mode..." -ForegroundColor Yellow

# Enable Anchor Mode in database
$enableCmd = "run-as com.coni.hyperisle 'sqlite3 /data/data/com.coni.hyperisle/databases/app_settings.db `"INSERT OR REPLACE INTO app_settings (key, value) VALUES ('ANCHOR_MODE_ENABLED', 'true');`"'"
$result = adb shell $enableCmd

if ($LASTEXITCODE -eq 0) {
    Write-Host "SUCCESS: Anchor Mode enabled" -ForegroundColor Green
    Write-Host ""
    Write-Host "IMPORTANT: Force stop and restart the app" -ForegroundColor Yellow
    Write-Host "Settings -> Apps -> HyperIsle -> Force Stop -> Open"
} else {
    Write-Host "ERROR: Failed to enable Anchor Mode" -ForegroundColor Red
    Write-Host "Result: $result"
}
