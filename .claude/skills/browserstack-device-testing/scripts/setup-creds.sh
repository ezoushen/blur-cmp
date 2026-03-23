#!/bin/bash
# Setup BrowserStack credentials from the browser
#
# This script extracts credentials from the BrowserStack settings page
# via the Chrome browser automation tools. The agent should:
# 1. Navigate to https://www.browserstack.com/accounts/settings
# 2. Use javascript_tool to extract username from Local Folder URL
#    and access key from the revealed key field
# 3. Download as bs-creds.env via blob download
# 4. User runs: source <downloaded-file>
#
# Or manually: create bs-creds.env with:
#   export BS_USER="your_username"
#   export BS_KEY="your_access_key"
#
# Verify with:
#   echo "BS_USER=$BS_USER BS_KEY length=${#BS_KEY}"

CREDS_FILE="${1:-.claude/skills/browserstack-device-testing/scripts/bs-creds.env}"

if [ -f "$CREDS_FILE" ]; then
    echo "Credentials file exists: $CREDS_FILE"
    echo "To use: source $CREDS_FILE"
else
    echo "No credentials file found."
    echo ""
    echo "Option 1 (browser): Agent extracts from BrowserStack settings page"
    echo "Option 2 (manual): Create $CREDS_FILE with:"
    echo '  export BS_USER="your_username"'
    echo '  export BS_KEY="your_access_key"'
fi
