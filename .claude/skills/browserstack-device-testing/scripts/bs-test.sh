#!/bin/bash
# BrowserStack App Live E2E Test Script
# Usage: source the credentials, then run with an action
#
#   source .claude/skills/browserstack-device-testing/scripts/bs-creds.env
#   .claude/skills/browserstack-device-testing/scripts/bs-test.sh upload <apk-path>
#   .claude/skills/browserstack-device-testing/scripts/bs-test.sh list
#   .claude/skills/browserstack-device-testing/scripts/bs-test.sh delete <app-id>
#   .claude/skills/browserstack-device-testing/scripts/bs-test.sh cleanup-all

set -euo pipefail

BS_USER="${BS_USER:?Set BS_USER first: source bs-creds.env}"
BS_KEY="${BS_KEY:?Set BS_KEY first: source bs-creds.env}"
API="https://api-cloud.browserstack.com/app-live"

case "${1:-help}" in
    upload)
        APK="${2:?Usage: $0 upload <path-to-apk>}"
        echo "Uploading $APK..."
        RESP=$(curl -s -u "$BS_USER:$BS_KEY" -X POST "$API/upload" -F "file=@$APK")
        APP_URL=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('app_url',''))" 2>/dev/null)
        if [ -z "$APP_URL" ]; then
            echo "FAILED: $RESP"
            exit 1
        fi
        APP_ID=$(echo "$APP_URL" | sed 's|bs://||')
        echo "OK app_url=$APP_URL"
        echo "   app_id=$APP_ID"
        echo ""
        echo "Open in browser:"
        echo "  https://app-live.browserstack.com/#app_url=$APP_URL&os=android&os_version=15.0&device=Google+Pixel+9"
        ;;
    list)
        echo "Recent uploads:"
        curl -s -u "$BS_USER:$BS_KEY" "$API/recent_apps" | python3 -c "
import sys, json
for a in json.load(sys.stdin)[:10]:
    print(f\"  {a.get('app_name','?'):30s} {a.get('app_url','?'):50s} {a.get('uploaded_at','?')}\")
"
        ;;
    delete)
        APP_ID="${2:?Usage: $0 delete <app-id>}"
        echo "Deleting $APP_ID..."
        RESP=$(curl -s -u "$BS_USER:$BS_KEY" -X DELETE "$API/app/delete/$APP_ID")
        echo "$RESP"
        ;;
    cleanup-all)
        echo "Deleting ALL your uploaded apps..."
        curl -s -u "$BS_USER:$BS_KEY" "$API/recent_apps" | python3 -c "
import sys, json
for a in json.load(sys.stdin):
    aid = a.get('app_url','').replace('bs://','')
    if aid:
        print(f'  Deleting {a.get(\"app_name\",\"?\")} ({aid})...')
"
        curl -s -u "$BS_USER:$BS_KEY" "$API/recent_apps" | python3 -c "
import sys, json, subprocess
for a in json.load(sys.stdin):
    aid = a.get('app_url','').replace('bs://','')
    if aid:
        r = subprocess.run(['curl','-s','-u','$BS_USER:$BS_KEY','-X','DELETE',f'$API/app/delete/{aid}'], capture_output=True, text=True)
        print(f'  {aid}: {r.stdout.strip()}')
"
        echo "Done"
        ;;
    *)
        echo "BrowserStack App Live Test Tool"
        echo ""
        echo "Usage: $0 <command> [args]"
        echo ""
        echo "Commands:"
        echo "  upload <apk>     Upload APK and get session URL"
        echo "  list             List recent uploaded apps"
        echo "  delete <app-id>  Delete a specific uploaded app"
        echo "  cleanup-all      Delete ALL uploaded apps"
        echo ""
        echo "Prerequisites:"
        echo "  source .claude/skills/browserstack-device-testing/scripts/bs-creds.env"
        ;;
esac
