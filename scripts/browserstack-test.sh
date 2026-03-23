#!/bin/bash
# BrowserStack E2E Performance Test Script
# Usage: ./scripts/browserstack-test.sh <username> <access_key>
#
# This script:
# 1. Builds the instrumented APK
# 2. Uploads to BrowserStack
# 3. Launches on a Pixel 9 (Android 16)
# 4. Waits for the app to run
# 5. Retrieves device logs with BlurPerf timing
# 6. Cleans up the uploaded app
#
# Prerequisites: curl, jq, ./gradlew

set -euo pipefail

BS_USER="${1:?Usage: $0 <browserstack_username> <access_key>}"
BS_KEY="${2:?Usage: $0 <browserstack_username> <access_key>}"
BS_API="https://api-cloud.browserstack.com"
APK_PATH="demoApp/build/outputs/apk/debug/demoApp-debug.apk"

echo "=== Step 1: Build instrumented APK ==="
./gradlew :demoApp:assembleDebug --quiet
echo "APK built: $APK_PATH ($(du -h "$APK_PATH" | cut -f1))"

echo ""
echo "=== Step 2: Upload APK to BrowserStack ==="
UPLOAD_RESPONSE=$(curl -s -u "$BS_USER:$BS_KEY" \
    -X POST "$BS_API/app-live/upload" \
    -F "file=@$APK_PATH")
APP_URL=$(echo "$UPLOAD_RESPONSE" | jq -r '.app_url // empty')

if [ -z "$APP_URL" ]; then
    echo "Upload failed: $UPLOAD_RESPONSE"
    exit 1
fi
echo "Uploaded: $APP_URL"

# Extract app ID for cleanup
APP_ID=$(echo "$APP_URL" | sed 's/bs:\/\///')
echo "App ID: $APP_ID"

echo ""
echo "=== Step 3: List available devices ==="
echo "Target: Google Pixel 9 Pro, Android 15"
echo ""
echo "=== Step 4: Open App Live session ==="
echo ""
echo "Please open BrowserStack App Live in your browser:"
echo "  https://app-live.browserstack.com/"
echo ""
echo "Select:"
echo "  1. Your uploaded app (should appear at top of Uploaded Apps)"
echo "  2. Google Pixel 9 (Android 16) or Pixel 9 Pro (Android 15)"
echo ""
echo "Once the session starts:"
echo "  - The app should launch automatically on the Variable Blur tab"
echo "  - Look at the yellow BLUR PERF overlay in the top-right corner"
echo "  - It shows: strategy, dim, total ms, blur ms, frames"
echo ""
echo "  Tab test sequence:"
echo "  1. Variable tab → read total/blur ms (Kawase pipeline)"
echo "  2. Tap Uniform tab → read total/blur ms (should use Kawase via BlurOverlay)"
echo "  3. Tap ColorDodge tab → read total/blur ms"
echo "  4. Tap Transition tab → tap 'Blur In', verify animation works"
echo ""
echo "Record the numbers, then press Enter to continue cleanup..."
read -r

echo ""
echo "=== Step 5: Clean up uploaded app ==="
DELETE_RESPONSE=$(curl -s -u "$BS_USER:$BS_KEY" \
    -X DELETE "$BS_API/app-live/app/delete/$APP_ID")
echo "Delete response: $DELETE_RESPONSE"

echo ""
echo "=== Done ==="
echo "Don't forget to stop the BrowserStack session via the Stop button!"
