# HyperIsle Anchor Mode Enable Script (Fixed)
Write-Host "HyperIsle Anchor Mode Enable Script" -ForegroundColor Green
Write-Host "==================================" -ForegroundColor Green

# Check if HyperIsle is installed
$packageCheck = adb shell pm list packages | Select-String "com.coni.hyperisle"
if (-not $packageCheck) {
    Write-Host "ERROR: HyperIsle not installed" -ForegroundColor Red
    exit 1
}

Write-Host "HyperIsle found, enabling Anchor Mode..." -ForegroundColor Yellow

# Method 1: Try direct sqlite command
Write-Host "Method 1: Direct SQLite command..." -ForegroundColor Cyan
$result1 = adb shell "run-as com.coni.hyperisle 'sqlite3 /data/data/com.coni.hyperisle/databases/hyperisle_db \"INSERT OR REPLACE INTO app_settings (key, value) VALUES ('ANCHOR_MODE_ENABLED', 'true');\"'"

if ($LASTEXITCODE -eq 0) {
    Write-Host "SUCCESS: Anchor Mode enabled via Method 1" -ForegroundColor Green
    Write-Host ""
    Write-Host "IMPORTANT: Force stop and restart the app" -ForegroundColor Yellow
    Write-Host "Settings -> Apps -> HyperIsle -> Force Stop -> Open"
    exit 0
}

# Method 2: Try with echo pipe
Write-Host "Method 1 failed, trying Method 2: Echo pipe..." -ForegroundColor Cyan
$result2 = adb shell "run-as com.coni.hyperisle 'echo \"INSERT OR REPLACE INTO app_settings (key, value) VALUES ('ANCHOR_MODE_ENABLED', 'true');\" | sqlite3 /data/data/com.coni.hyperisle/databases/hyperisle_db'"

if ($LASTEXITCODE -eq 0) {
    Write-Host "SUCCESS: Anchor Mode enabled via Method 2" -ForegroundColor Green
    Write-Host ""
    Write-Host "IMPORTANT: Force stop and restart the app" -ForegroundColor Yellow
    Write-Host "Settings -> Apps -> HyperIsle -> Force Stop -> Open"
} else {
    Write-Host "ERROR: Both methods failed" -ForegroundColor Red
    Write-Host "Method 1 result: $result1"
    Write-Host "Method 2 result: $result2"
    Write-Host ""
    Write-Host "Try installing debug build instead:" -ForegroundColor Yellow
    Write-Host "./gradlew installDebug"
}
