@echo off
echo HyperIsle Anchor Mode Toggle
echo.
echo Bu script Anchor Mode'u production build'de aktif eder.
echo Uygulamayı yeniden baslatmaniz gerekecek.
echo.

REM Check if adb is available
adb version >nul 2>&1
if %errorlevel% neq 0 (
    echo HATA: ADB bulunamadi. Android SDK yuklu olmali.
    echo Lutfen PATH'e Android SDK platform-tools ekleyin.
    pause
    exit /b 1
)

echo HyperIsle paketi bulunuyor...
for /f "tokens=2" %%i in ('adb shell pm list packages ^| findstr com.coni.hyperisle') do set PACKAGE=%%i
if "%PACKAGE%"=="" (
    echo HATA: HyperIsle yuklu degil veya calisiyor degil.
    pause
    exit /b 1
)

echo Paket bulundu: %PACKAGE%
echo Anchor Mode aktif ediliyor...
adb shell "run-as com.coni.hyperisle 'mkdir -p /data/data/com.coni.hyperisle/databases && echo \"INSERT OR REPLACE INTO app_settings (key, value) VALUES ('ANCHOR_MODE_ENABLED', 'true');\" | sqlite3 /data/data/com.coni.hyperisle/databases/app_settings.db'"

if %errorlevel% equ 0 (
    echo BASARILI: Anchor Mode aktif edildi.
    echo.
    echo LUTFEN: Uygulamayi tamamen kapatip yeniden baslatin.
    echo Ayarlar > Force Stop > Uygulamayi acin.
) else (
    echo HATA: Anchor Mode aktif edilemedi.
    echo Root erisimi veya calisan bir debug build gerekebilir.
)

echo.
pause
