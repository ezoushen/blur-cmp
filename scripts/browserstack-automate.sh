#!/bin/bash
# BrowserStack FULLY AUTOMATED Performance Test
# Usage: ./scripts/browserstack-automate.sh <username> <access_key>
#
# Uses App Automate (not App Live) to:
# 1. Build APK
# 2. Upload to BrowserStack
# 3. Run an Espresso/Appium test that launches the app, waits, reads logcat
# 4. Retrieve BlurPerf timing from device logs
# 5. Clean up
#
# For simpler testing without Espresso, use browserstack-test.sh (manual session)

set -euo pipefail

BS_USER="${1:?Usage: $0 <browserstack_username> <access_key>}"
BS_KEY="${2:?Usage: $0 <browserstack_username> <access_key>}"
BS_API="https://api-cloud.browserstack.com/app-automate"
APK_PATH="demoApp/build/outputs/apk/debug/demoApp-debug.apk"

echo "=== Step 1: Build APK ==="
./gradlew :demoApp:assembleDebug --quiet
echo "APK: $APK_PATH"

echo ""
echo "=== Step 2: Upload APK ==="
UPLOAD_RESPONSE=$(curl -s -u "$BS_USER:$BS_KEY" \
    -X POST "$BS_API/upload" \
    -F "file=@$APK_PATH")
APP_URL=$(echo "$UPLOAD_RESPONSE" | jq -r '.app_url // empty')

if [ -z "$APP_URL" ]; then
    echo "Upload failed: $UPLOAD_RESPONSE"
    exit 1
fi
echo "App URL: $APP_URL"

echo ""
echo "=== Step 3: Start Appium session ==="
# Create a session that just launches the app and waits
SESSION_RESPONSE=$(curl -s -u "$BS_USER:$BS_KEY" \
    -X POST "https://hub-cloud.browserstack.com/wd/hub/session" \
    -H "Content-Type: application/json" \
    -d "{
        \"desiredCapabilities\": {
            \"app\": \"$APP_URL\",
            \"device\": \"Google Pixel 9\",
            \"os_version\": \"15.0\",
            \"project\": \"blur-cmp-perf\",
            \"build\": \"perf-test-$(date +%Y%m%d-%H%M%S)\",
            \"name\": \"BlurPerf Variable Tab\",
            \"browserstack.debug\": true,
            \"browserstack.appium_version\": \"2.0.0\",
            \"browserstack.deviceLogs\": true,
            \"autoGrantPermissions\": true
        }
    }")

SESSION_ID=$(echo "$SESSION_RESPONSE" | jq -r '.sessionId // .value.sessionId // empty')

if [ -z "$SESSION_ID" ]; then
    echo "Session creation failed: $SESSION_RESPONSE"
    # Clean up app
    curl -s -u "$BS_USER:$BS_KEY" -X DELETE "$BS_API/app/delete/$(echo "$APP_URL" | sed 's/bs:\/\///')" > /dev/null
    exit 1
fi
echo "Session ID: $SESSION_ID"

echo ""
echo "=== Step 4: Wait for app to run (30 seconds) ==="
sleep 30

echo ""
echo "=== Step 5: Retrieve device logs ==="
LOGS=$(curl -s -u "$BS_USER:$BS_KEY" \
    "https://api-cloud.browserstack.com/app-automate/sessions/$SESSION_ID/devicelogs")

echo "BlurPerf log entries:"
echo "$LOGS" | jq -r '.[] | select(.message | contains("BlurPerf")) | .message' 2>/dev/null || \
echo "$LOGS" | grep -o "BlurPerf[^\"]*" | head -20

echo ""
echo "=== Step 6: Stop session ==="
curl -s -u "$BS_USER:$BS_KEY" \
    -X DELETE "https://hub-cloud.browserstack.com/wd/hub/session/$SESSION_ID" > /dev/null
echo "Session stopped"

echo ""
echo "=== Step 7: Clean up app ==="
APP_ID=$(echo "$APP_URL" | sed 's/bs:\/\///')
curl -s -u "$BS_USER:$BS_KEY" \
    -X DELETE "$BS_API/app/delete/$APP_ID" > /dev/null
echo "App deleted"

echo ""
echo "=== Done ==="
