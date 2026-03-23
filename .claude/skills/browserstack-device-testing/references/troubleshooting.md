# BrowserStack Troubleshooting

## Common Problems

### Uploaded app not showing in dashboard
- API uploads appear under the uploading user's account, not team-wide
- Click "All Apps" filter (not "Recent Default") to see all uploads
- Navigate directly with app_url: `https://app-live.browserstack.com/#app_url=bs://<id>&os=android&os_version=15.0&device=Google+Pixel+9`

### "Leave site?" dialog when navigating away from session
- Use `force: true` parameter in navigate tool
- Or better: stop session first via Stop Session button before navigating

### Logcat not showing app logs
- Default log level is "Warning" — our `Log.i` (Info) messages are filtered out
- Change the log level dropdown from "Warning" to "Info" or "Verbose"
- Filter by app: select `io.github.ezoushen.blur.demo` in the app dropdown
- Search field filters by text content, not by tag

### Device screen too small to read overlay text
- Close the DevTools/Logcat panel (click X button) to enlarge device viewport
- Collapse the left sidebar (click toggle at top-left)
- Use `zoom` action on the browser tool to magnify specific regions
- The overlay coordinates shift when panels are opened/closed — re-screenshot after layout changes

### File upload dialog not interactable
- Browser automation can't interact with native OS file pickers
- Use the REST API instead: `curl -F "file=@path.apk" /app-live/upload`
- The bs-test.sh script handles this

### Session auto-stops
- Idle timeout is configurable in BrowserStack Settings (default 5-10 min)
- Keep interacting or the session times out

### Credentials file cleaned up accidentally
- Re-extract from browser: navigate to settings, click eye icon, download via JS blob
- Or create manually: `export BS_USER="..." BS_KEY="..."`

## BrowserStack API Reference

### Upload APK
```
POST https://api-cloud.browserstack.com/app-live/upload
-u USER:KEY -F "file=@app.apk"
→ {"app_url": "bs://hash"}
```

### List recent apps
```
GET https://api-cloud.browserstack.com/app-live/recent_apps
-u USER:KEY
→ [{"app_name": "...", "app_url": "bs://...", "uploaded_at": "..."}]
```

### Delete app
```
DELETE https://api-cloud.browserstack.com/app-live/app/delete/<app-id>
-u USER:KEY
→ {"success": true}
```

### Direct session URL
```
https://app-live.browserstack.com/#app_url=bs://<id>&os=android&os_version=<ver>&device=Google+Pixel+9
```
