# Contributing to blur-cmp

Thanks for your interest in contributing! This is a small library, so every contribution counts.

## How to Contribute

### Report Bugs

Open a [bug report](https://github.com/ezoushen/blur-cmp/issues/new?template=bug_report.yml) with:
- blur-cmp version, platform (Android/iOS), device model, OS version
- Minimal reproduction code or steps
- Expected vs actual behavior

### Suggest Features

Open a [feature request](https://github.com/ezoushen/blur-cmp/issues/new?template=feature_request.yml) describing the problem, your desired solution, and any alternatives you've considered.

### Submit Code

1. Fork the repository
2. Create a feature branch: `git checkout -b feat/my-change`
3. Make your changes
4. Run the demo app to verify on your target platform(s)
5. Commit with a descriptive message (see style below)
6. Push and open a pull request

## Commit Style

Use conventional commit prefixes:

```
feat:     new feature
fix:      bug fix
docs:     documentation changes
chore:    version bumps, build config, CI
refactor: code restructuring without behavior change
test:     test additions or changes
```

Examples:
```
feat(ios): add variable blur gradient support
fix(android): crash on API 23 when using BlurOverlay
docs: update performance numbers for Pixel 9
```

## Development Setup

- **Kotlin**: 2.3.20+
- **JDK**: 17
- **Android Studio**: Latest stable (for Android emulator)
- **Xcode**: 15+ (for iOS simulator)

### Android

```bash
./gradlew :demoApp:assembleDebug
```

### iOS

Open `iosApp/iosApp.xcodeproj` in Xcode, or use xcodebuild:

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 15'
```

## Pull Request Guidelines

- Keep PRs focused on a single concern
- Test on real devices when possible (especially for GPU/rendering changes)
- Update docs if your change affects the public API
- Add a line to the Unreleased section in CHANGELOG.md

## Code Style

- Follow existing patterns in the codebase
- Use Kotlin conventions (no wildcard imports, consistent formatting)
- iOS Swift code should match standard Swift style

## Questions?

Open a [discussion](https://github.com/ezoushen/blur-cmp/discussions) or tag `@ezoushen` in your issue.
