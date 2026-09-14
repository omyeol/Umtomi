# Umtomi iOS Port

This folder contains a SwiftUI-based iOS port of the Android app's core reader flow.

Implemented:
- Hitomi search with Android-equivalent sort modes
- Remote tag suggestions
- Gallery list with pagination
- Search result page jump
- Gallery detail with metadata, related galleries, and tag shortcuts
- Reader view for gallery pages
- Reader page jump
- Offline gallery downloads stored inside the iOS app sandbox
- Downloads tab with active progress and delete support
- Favorites, history, recent searches, and basic settings
- Deep link handling for `hitomi.la`, `*.hasha.in`, and `e-hentai.org`

Intentionally not ported in this pass:
- Android foreground/background download services
- Wi-Fi Direct transfer flow
- Pattern/PIN/biometric lock
- Eye-blink camera navigation
- Firebase / Google Play Services integrations

## Project Layout

- `project.yml`: XcodeGen definition for the iOS app
- `Umtomi/`: SwiftUI source files and `Info.plist`

## Build

This repository is being edited from Windows, so Xcode validation could not be run here.

On macOS:

1. Install Xcode 15 or newer.
2. Install XcodeGen: `brew install xcodegen`
3. From this folder, run: `xcodegen generate`
4. Open `Umtomi.xcodeproj` in Xcode.
5. Select an iPhone simulator or device and build.
6. Run `UmtomiTests` in Xcode to verify core parsing logic.

To produce an IPA from Terminal:

1. Install full Xcode and select it with `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`
2. Run `./build-ipa.sh`
3. For a signed IPA export, set `APPLE_TEAM_ID=<your team id>` before running the script.

## Notes

- The iOS port reimplements the Hitomi indexing and image URL generation logic in Swift.
- Image requests use the same `User-Agent` and `Referer` strategy as the Android app to avoid CDN failures.
- Offline downloads are app-local and are not exposed as Android-style background services.
- The port is scoped as a working iOS reader, not a one-to-one rewrite of every Android-only system feature.
