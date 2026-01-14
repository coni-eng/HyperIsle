#!/bin/bash
echo "HyperIsle Anchor Mode Enable Script"
echo "=================================="

# Check if HyperIsle is installed
if ! adb shell pm list packages | grep -q "com.coni.hyperisle"; then
    echo "ERROR: HyperIsle not installed"
    exit 1
fi

echo "HyperIsle found, enabling Anchor Mode..."

# Enable Anchor Mode in database
adb shell "run-as com.coni.hyperisle 'sqlite3 /data/data/com.coni.hyperisle/databases/app_settings.db \"INSERT OR REPLACE INTO app_settings (key, value) VALUES (\"ANCHOR_MODE_ENABLED\", \"true\");\"'"

if [ $? -eq 0 ]; then
    echo "SUCCESS: Anchor Mode enabled"
    echo ""
    echo "IMPORTANT: Force stop and restart the app"
    echo "Settings -> Apps -> HyperIsle -> Force Stop -> Open"
else
    echo "ERROR: Failed to enable Anchor Mode"
fi
