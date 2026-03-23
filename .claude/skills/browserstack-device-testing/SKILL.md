---
name: browserstack-device-testing
description: >
  Real device testing and performance profiling via BrowserStack App Live.
  Use when: testing Android APK on real devices, profiling GPU performance,
  reading device logcat on real hardware, collecting timing data from
  on-screen overlays, or comparing emulator vs real device performance.
  Triggers on: "test on real device", "BrowserStack", "profile on device",
  "real device performance", "upload to BrowserStack", "device logs".
---

# BrowserStack Real Device Testing

## Workflow

### 1. Setup credentials

Extract from browser (user must be logged into BrowserStack):

```
navigate → https://www.browserstack.com/accounts/settings
find → "Show access key" eye icon → click
javascript_tool → extract username from Local Folder URL + access key
  → download as bs-creds.env via JS blob
User runs: ! source ~/Downloads/bs-creds.env
Verify: echo "BS_USER=${#BS_USER} BS_KEY=${#BS_KEY}"
```

Or manual: create file with `export BS_USER="..." BS_KEY="..."`

### 2. Build and upload APK

```bash
./gradlew :demoApp:assembleDebug
source <creds-file>
.claude/skills/browserstack-device-testing/scripts/bs-test.sh upload \
    demoApp/build/outputs/apk/debug/demoApp-debug.apk
```

Script prints direct session URL and app ID.

### 3. Launch session

Navigate Chrome tab to the URL printed by the upload script:
```
https://app-live.browserstack.com/#app_url=bs://<ID>&os=android&os_version=15.0&device=Google+Pixel+9
```

Wait 15-20 seconds for device boot + app install.

### 4. Collect data

**On-screen overlay:** Close DevTools panel to enlarge device. Use `zoom` action targeting overlay region. Take screenshot first to locate coordinates.

**Logcat:** Open DevTools. Change log level to "Info". Search by tag (e.g., "BlurPerf"). Select app package in dropdown.

**Tap device:** Click on the device viewport in browser. Screenshot first to find targets.

### 5. Stop session + cleanup

```
find → "Stop Session" button → click     ← ALWAYS use this, never navigate away
source <creds-file>
.claude/skills/browserstack-device-testing/scripts/bs-test.sh delete <APP_ID>
rm -f ~/Downloads/bs-creds.env
```

## Scripts

- `scripts/bs-test.sh` — CLI for upload, list, delete, cleanup-all
- `scripts/setup-creds.sh` — Credential setup helper

## Troubleshooting

See [references/troubleshooting.md](references/troubleshooting.md) for: app not showing in dashboard, logcat filtering, overlay too small, file upload issues, session timeouts.

## Commonly used devices

| Device | OS | Use for |
|--------|----|---------|
| Pixel 9 | 15 | Latest Tensor G4 |
| Pixel 8 Pro | 14 | Tensor G3 comparison |
| Samsung S24 | 14 | Adreno 750 (Snapdragon) |
| Pixel 6 | 12 | Minimum modern baseline |
