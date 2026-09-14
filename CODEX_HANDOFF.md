# Codex Handoff: Umtomi iOS

Last updated: 2026-06-01

## Project Snapshot

This workspace contains an iOS Hitomi reader app that started as Pupil/Jotomi and is now named **Umtomi**.

Main app path:

- `pupil/ios/Umtomi`
- XcodeGen spec: `pupil/ios/project.yml`
- Build script: `pupil/ios/build-ipa.sh`

Current app identity:

- Product name: `Umtomi`
- Bundle ID: `xyz.sabok.umtomi`
- Deployment target: iOS 17.0
- Swift version: 5.9
- Xcode project is generated from `project.yml`; do not manually edit generated `.xcodeproj` unless absolutely necessary.

## Build And IPA

Typical unsigned IPA build:

```bash
cd /Users/omyeolmacair/DEV/Pupil-5.3.24/pupil/ios
./build-ipa.sh
```

Output:

- Local IPA: `pupil/ios/build/Umtomi-unsigned.ipa`
- iCloud copy: `~/Library/Mobile Documents/com~apple~CloudDocs/IPA/Umtomi-unsigned-YYMMDD-HHMM.ipa`

Important iCloud behavior:

- `build-ipa.sh` copies the new IPA to iCloud Drive `IPA`.
- It must only delete previous `Umtomi-*.ipa` files.
- Do not broaden this back to `*.ipa`; the user explicitly asked that other IPA files stay untouched.

Signed IPA, if needed:

```bash
APPLE_TEAM_ID=YOUR_TEAM_ID ./build-ipa.sh
```

Useful validation:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/swiftc \
-typecheck \
-module-cache-path pupil/ios/build/SwiftModuleCache \
-sdk /Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS26.4.sdk \
-target arm64-apple-ios17.0 \
pupil/ios/Umtomi/Models.swift \
pupil/ios/Umtomi/AppStore.swift \
pupil/ios/Umtomi/DownloadLibrary.swift \
pupil/ios/Umtomi/FavoritesBackupDocument.swift \
pupil/ios/Umtomi/HitomiService.swift \
pupil/ios/Umtomi/UmtomiApp.swift \
pupil/ios/Umtomi/Views.swift
```

## Main Architecture

Core files:

- `Models.swift`: app models, settings, tabs, favorite folders/artists, reader tab sessions, gallery decoding.
- `AppStore.swift`: central state, search, favorites, settings persistence, reader tabs, resume pages, downloads.
- `HitomiService.swift`: Hitomi network/index/meta/image loading and in-memory image cache.
- `Views.swift`: nearly all SwiftUI UI, including Browse, Favorites, Gallery Detail, Reader, Settings.
- `DownloadLibrary.swift`: offline downloads and local manifest.
- `FavoritesBackupDocument.swift`: favorites/folders/artists backup and restore.

Data sources:

- App uses Hitomi directly, not e-hentai as a database.
- Search/index URLs include `tagindex.hitomi.la` and `galleriesindex`.
- Gallery metadata comes from `ltn.gold-usergeneratedcontent.net/galleries/<id>.js`.
- Some old e-hentai URL parsing exists only to extract gallery IDs from links.

Persistence:

- Settings/favorites/history/recent searches/last read pages use `UserDefaults`.
- Downloads live in app support under `UmtomiDownloads`.
- Old Pupil/Jotomi storage keys/folders may remain as migration compatibility. Do not delete migration code casually.

## Recently Added User-Facing Features

Reader:

- Paged and Vertical Scroll modes.
- Vertical Scroll has no page gaps.
- Vertical Scroll pinch zoom works like a document/PDF: the whole vertical document zooms, not one image at a time.
- Paged mode keeps per-page pinch zoom.
- Tap once in Reader toggles top chrome: back/download/title/page count.
- Right-to-left applies only to paged reader and is disabled for Vertical Scroll.
- Last-read page is remembered. On reopen, user chooses whether to resume or start from page 1.
- Resume preloading setting:
  - `From Beginning`
  - `Around Resume Page`: example order `30, 29, 31, 28, 32`.
- Reader tabs work like browser tabs.
- `Open in Background Tab` creates a reader tab without opening the reader.
- If tabs exist but reader is not open, a floating `Reader` button appears so user can enter the reader workspace.
- Loaded reader images should remain in memory until the app exits or the relevant reader tab is closed.
- Closing a reader tab clears only that tab's image cache.

Search and filters:

- Always included tags and always excluded tags exist in Settings.
- The tag filters are applied to Search and Algorithm.
- Search result count was adjusted to match actual displayed results.
- Search detail overlay exists for artist/group/tag taps from Gallery Detail.
- Numeric ID direct open has been fixed for mixed Hitomi metadata types.

Algorithm:

- Algorithm tab shows recommendations based on history/favorites.
- Recommendation count is configurable in Settings.
- Tag filters also apply to Algorithm.

Favorites:

- Favorites root separates `Works` and `Artists`.
- Favorite folders support manual gallery IDs and folder-local auto rules.
- Auto rules support terms like `artist:name`, `group:name`, `female:ahegao`.
- Auto rules must only affect that folder, not parent or child folders.
- Favorite artist support exists from gallery detail artist rows.
- Backup/restore includes favorite works, folders, and favorite artists.

Settings:

- App UI language supports English/Korean/Japanese via localizations.
- Dark mode can be enabled/disabled.
- Hide app in recent apps can be toggled.
- Bottom tab order can be customized.
- Clear downloads/history/recent searches show confirmation dialogs.
- `Port Status` was removed.

## Important Recent Fixes And Fragile Areas

Reader tabs:

- `ReaderWorkspaceView` keys `ReaderView` by selected tab ID so per-tab page state does not bleed across tabs.
- `GalleryRoute.id` uses a fixed `reader-workspace` ID for reader presentation so changing/closing tabs does not dismiss the sheet.
- `closeReaderTab` must not re-present reader workspace when switching fallback tabs.
- If user reports all tabs jumping to the same page, check `ReaderWorkspaceView`, selected tab binding, and `ReaderTabSession.selectedPage`.

Reader image loading:

- `HitomiService` has:
  - shared URL image loading tasks
  - `NSCache` image/data cache
  - a strong retained image cache so loaded reader images survive tab switching
- `prefetchImageData` currently preloads decoded images through `image(for:)`.
- Vertical reader has priority loading for the currently selected/resume page to avoid `29/31 loaded but 30 blank`.
- If scrolling stutters, first inspect:
  - image decoding path in `HitomiService.image(for:)`
  - `prefetchImageData(for:)`
  - vertical reader `beginLoadingImages`
  - any main-thread work in `ZoomableVerticalReaderScrollView.Coordinator`

Hitomi metadata decoding:

- Some old Hitomi galleries return numeric `id`, numeric `languages[].galleryid`, or numeric tag flags.
- `GalleryInfo`, `GalleryLanguage`, and `GalleryTag` have tolerant decoders for mixed string/int/bool values.
- Known fixed IDs from debugging: `3213424`, `1995000`, `867970`.

Favorites folder auto rules:

- Earlier crashes came from eager metadata loading when Favorites rendered.
- Auto classification should be lazy and folder-local.
- Be careful not to trigger all favorite metadata fetches during app startup or Favorites root render.

iCloud IPA cleanup:

- The user explicitly corrected this on 2026-06-01:
  - Only remove previous `Umtomi-*.ipa`.
  - Do not delete other `.ipa` files in iCloud Drive `IPA`.

## User Preferences And Workflow

- User tests via unsigned IPA copied to iCloud Drive.
- After meaningful app changes, build IPA unless they only ask for explanation.
- Keep responses concise and in Korean.
- When mentioning build output, include both local and iCloud IPA paths.
- The user values quick iteration over long explanations.

## Quick Commands

Generate project:

```bash
cd /Users/omyeolmacair/DEV/Pupil-5.3.24/pupil/ios
xcodegen generate --spec project.yml
```

Build IPA:

```bash
cd /Users/omyeolmacair/DEV/Pupil-5.3.24/pupil/ios
./build-ipa.sh
```

Check IPA executable:

```bash
unzip -l /Users/omyeolmacair/DEV/Pupil-5.3.24/pupil/ios/build/Umtomi-unsigned.ipa | rg "Payload/Umtomi.app/Umtomi$"
```

Find newest iCloud Umtomi IPA:

```bash
zsh -lc 'print -rl -- "$HOME/Library/Mobile Documents/com~apple~CloudDocs/IPA"/Umtomi-unsigned-*.ipa | tail -1'
```

Search useful spots:

```bash
rg -n "readerTabs|retainedImageCache|Open in Background Tab|FavoriteFolder|FavoriteArtist|resumePrefetch" pupil/ios/Umtomi
```

