# Android 5.15.7 & iOS 5.4.10 (New Umtomi App Icon)

- Replaced the Umtomi launcher and feed icons with the new 3D gradient U emblem (`1137915`):
  - iOS (`umtomi/ios/Umtomi/Assets.xcassets/AppIcon.appiconset/`): regenerated all 18 opaque PNG icon assets matching `Contents.json`, bumped `project.yml` to `5.4.10` / build `12`.
  - Android (`umtomi/app/src/main/`): replaced legacy `res/drawable/umtomi_icon.xml` vector with `res/drawable-anydpi-v26/umtomi_icon.xml` (`<adaptive-icon>`), `res/drawable-nodpi/umtomi_icon_foreground.png` (432x432 edge-padded), `res/values/colors.xml` (`umtomi_icon_background` `#EEF1FC`), `res/drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/umtomi_icon.png`, and `ic_launcher-web.png` (512x512); bumped `build.gradle.kts` to `5.15.7` / `107`.
  - LiveContainer & Repo (`umtomi-icon.png`, `livecontainer.json`, `Publish-iOSRelease.py`): added 1024x1024 `umtomi-icon.png` at repo root and switched `iconURL` to `https://raw.githubusercontent.com/omyeol/Umtomi/main/umtomi-icon.png`.
- Verification & Artifacts:
  - Android `.\umtomi\Build-Android.ps1 -Test` passed (`assembleDebug`, `lintDebug`, 24 JVM unit tests across 8 suites with 0 failures/errors/skipped). `E:\DEV\CODEX\Umtomi\artifacts\Umtomi-v5.15.7.apk` and `C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.15.7.apk` match (`21,450,495` bytes, SHA-256 `4449A7E241CFF2D8800A4BAC10F16AD57AA194D40FA647DD88D24F1D5333CEE3`). Published `https://github.com/omyeol/Umtomi/releases/tag/v5.15.7` (`latest`, verified unauthenticated).
  - iOS GitHub Actions (`https://github.com/omyeol/Umtomi/actions/runs/45575261755`) succeeded; `E:\DEV\CODEX\Umtomi\artifacts\Umtomi-iOS-5.4.10.ipa` verified (`3,475,312` bytes, `xyz.sabok.umtomi` / `5.4.10` / `12`, SHA-256 `d7284fa796fd0bd838a73559b9627d20caa62063184f8902ce22ed88b30f6438`). Published `https://github.com/omyeol/Umtomi/releases/tag/ios-v5.4.10`, verified `https://raw.githubusercontent.com/omyeol/Umtomi/main/livecontainer.json` (`b6ec2ef`) and `https://raw.githubusercontent.com/omyeol/Umtomi/main/umtomi-icon.png` (`1,108,708` bytes).

# Android 5.15.5 & iOS 5.4.8 (Favorites sort, Settings tab order, Search pull-to-refresh, persistent folder counts, Artist search overlay)

- Implemented across both iOS (`Models.swift`, `AppStore.swift`, `Views.swift`, `TabBarSettingsTests.swift`, `Localizable.strings`, `project.yml` `5.4.8` / `10`) and Android (`WorkspaceNavigation.kt`, `MainActivity.kt`, `PortFavoritesView.kt`, `ClassicBrowseActivity.kt`, `port_strings.xml`, `PortParityTest.kt`, `build.gradle.kts` `5.15.5` / `105`):
  - Favorites sorting (`added` / `upload` / `name` with `descending` / `ascending`).
  - Added `Settings` (`UmtomiTab.settings` / `WorkspaceTab.SETTINGS`) to configurable tab bar order and More overflow.
  - Added pull-to-refresh on Search (`BrowseView` `.refreshable` + `AppStore.refreshSearch` on iOS; `SwipeRefreshLayout` on Android).
  - Fixed cold-start `"0 galleries"` bug in auto-rule favorite folders by persisting resolved auto-match caches (`umtomi.ios.favoriteAutoMatchCache` / `port_favorite_auto_cache`), preserving warm counts on launch, and refreshing child folder rules automatically.
  - Replaced Favorites -> Artists tab-jump with full browse-like overlay search (`ArtistSearchOverlayView` on iOS; `MainActivity.showSearchOverlay` on Android) supporting grid cards, query editing, sort, pagination, and pull-to-refresh.
- Verification & Artifacts:
  - Android `.\umtomi\Build-Android.ps1 -Test` passed (`assembleDebug`, `lintDebug`, 22 JVM unit tests across 7 suites with 0 failures/errors/skipped). `artifacts/Umtomi-v5.15.5.apk` and `C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.15.5.apk` match (21,168,503 bytes, SHA-256 `71DBDBFB89A8D4E00D287BC89BC8C1A86532AC0D6385957412EC2DA96BEE3106`). Published `https://github.com/omyeol/Umtomi/releases/tag/v5.15.5` (`latest`, verified unauthenticated).
  - iOS GitHub Actions (`https://github.com/omyeol/Umtomi/actions/runs/44757816227`) succeeded; `artifacts/Umtomi-iOS-5.4.8.ipa` verified (2,024,757 bytes, `xyz.sabok.umtomi` / `5.4.8` / `10`, SHA-256 `6f1711d669967704850eb1cf23615ee4254af52ab148190996dd93cba9f57a51`). Published `https://github.com/omyeol/Umtomi/releases/tag/ios-v5.4.8` and verified `https://raw.githubusercontent.com/omyeol/Umtomi/main/livecontainer.json` (`042092f`).

# iOS 5.4.7 instant biometric unlock

- Updated `umtomi/ios/Umtomi/AppLock.swift`, `umtomi/ios/Umtomi/AppLockViews.swift`, and `umtomi/ios/project.yml` (`5.4.7`, build `9`):
  - Offloaded `LAContext.canEvaluatePolicy` and `evaluatePolicy` via `BiometricSession` on a `.userInteractive` queue, pre-warming availability during `.inactive` / `onAppear`.
  - Triggered biometrics directly on `.active` via `onAppear` / `onChange(of: phase)` with `Task(priority: .userInteractive)` instead of nested `.task` + `Task`.
  - Applied `authenticating = false`, `message = nil`, `suppressInactiveShield`, and `locked = false` atomically inside one non-animated `Transaction` on `DispatchQueue.main.async` before releasing `activeSession`.
- Built via GitHub Actions (`https://github.com/omyeol/Umtomi/actions/runs/44753430328`), verified `artifacts/Umtomi-iOS-5.4.7.ipa` (1,949,254 bytes, SHA-256 `1190a0536ebc93b81bb47a8846cbfcde9fee8db3c3668ed3ee96f41c49ca5e96`, `xyz.sabok.umtomi` / `5.4.7` / `9`), and published `https://github.com/omyeol/Umtomi/releases/tag/ios-v5.4.7` + `livecontainer.json` (`9e767c3`). Real-device Face ID unlock verification pending.

# Public release transition: Android 5.15.4 & iOS 5.4.6 on omyeol/Umtomi

- Switched `omyeol/Umtomi` repository visibility from private to **public** (`private: false`).
- Updated Android release/updater targets (`UpdateActivity.kt`, `util/update.kt`, `umtomi/Publish-AndroidRelease.py`) and iOS feed publisher (`Publish-iOSRelease.py`) to `omyeol/Umtomi` (`https://github.com/omyeol/Umtomi/releases`), keeping `omyeol/Umtomi-releases/main/livecontainer.json` mirrored for existing LiveContainer subscribers.
- Bumped Android to `5.15.4` (versionCode `104` in `umtomi/app/build.gradle.kts`) and iOS to `5.4.6` (build `8` in `umtomi/ios/project.yml`).
- Cleaned and pushed workspace source (`49b8b64`, excluding `.build-*`, `.kotlin`, `build-codex`, `artifacts`, `DriveSync.local.xcconfig`, and legacy `pupil/`), then fast-forwarded local `main` to `72d879b` (after `Publish-iOSRelease.py` committed `livecontainer.json`).

Verification & Artifacts:
- Android `.\umtomi\Build-Android.ps1 -Test` (`assembleDebug`, 21 JVM unit tests across 7 suites with 0 failures/errors/skipped, `lintDebug`) passed. `artifacts/Umtomi-v5.15.4.apk` and `C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.15.4.apk` match (21,142,189 bytes, SHA-256 `7FEE0D021C0E230CBCAD93D524DD50402E0F16A1A06E9B4E8F41623D768B84B2`). Published `https://github.com/omyeol/Umtomi/releases/tag/v5.15.4` (verified via unauthenticated GitHub API).
- iOS GitHub Actions workflow (`https://github.com/omyeol/Umtomi/actions/runs/44750026692`) succeeded; extracted and verified `artifacts/Umtomi-iOS-5.4.6.ipa` (1,942,283 bytes, ZIP CRC ok, `Info.plist` `xyz.sabok.umtomi` / `5.4.6` / `8`, SHA-256 `72c050cc05c26ec985fd2de3da0fd3554494d7b3b9ebccdc4ca6e1fdeb947bb6`). Published `https://github.com/omyeol/Umtomi/releases/tag/ios-v5.4.6` and verified both `https://raw.githubusercontent.com/omyeol/Umtomi/main/livecontainer.json` and `https://raw.githubusercontent.com/omyeol/Umtomi-releases/main/livecontainer.json`.
- Remaining unverified on physical hardware: real-device Android biometric unlock, live Google Drive sync, `v5.15.4` in-app updater check against `omyeol/Umtomi`, and real-device iOS 5.4.6 OLED dark mode visual check.

# Android 5.15.3 full iOS port, tab editor & common OLED dark mode

Implemented common OLED pure-black (`#000000`) dark mode across both iOS and Android:
- iOS (`UmtomiApp.swift`, `Views.swift`, `AppLockViews.swift`, `DriveSyncSettings.swift`, `Bookshelf.swift`, `ReaderCaptureView.swift`): configured opaque `#000000` `UITabBarAppearance`/`UINavigationBarAppearance` and pure black `Form`/`List`/`ScrollView`/`Reader`/`GalleryCardView`/`AppSwitcherShieldView` backgrounds in dark mode (replacing `.bar`, `.thinMaterial`, `Color.black.opacity(0.95)`, and dark gradient). Note: Windows host has no Xcode/Swift compiler; Swift brace check only, macOS/Actions build pending.
- Android (`res/values-night/*`, `res/values/reader_colors.xml`, `PortStyle.kt`, `CapturePreviewDialog.kt`, `MobileCaptureRegionDialog.kt`, `ReaderCapture.kt`, `BookshelfActivity.kt`, `DriveSyncActivity.kt`, `PortLockSettingsActivity.kt`): set `workspace_background`, `workspace_bar`, `reader_surface`, `reader_canvas`, `browser_overview`, `statusBarColor`, and `navigationBarColor` to `#000000`, with `#0B0B0E` outlined cards/inputs and `OledAlertDialogTheme`.

Replaced the Android bottom tab bar editor (`PortTabBarEditorActivity.kt`, `WorkspaceNavigation.kt`, `ic_drag_handle.xml`):
- Added live bottom tab bar preview, `Show More tab` toggle, `ItemTouchHelper` drag-and-drop reordering (including across Tab Bar <-> In More sections), 1-tap `↑`/`↓`/`Move to More`/`Move to tab bar` inline controls, Reset to default, long-press tab bar shortcut, and styled `BottomSheetDialog` More menu including Settings and Tab Bar settings.

Completed remaining iOS-to-Android port features:
- Favorites (`PortFavoritesView.kt`, `PortFavoriteFolderEditorActivity.kt`, `FavoriteFolders.kt`): ported iOS Works/Artists segmented root, nested folders, auto-rule banner & matching, folder editor with manual multi-select & auto-rule query, and artist search/add/remove directly inside `MainActivity`'s Favorites tab.
- Gallery Detail (`PortGalleryDetail.kt`, `MainActivity.kt`): added Date, Characters, Availability, Artist favorite star toggle, `DetailSearchOverlay` screen stack, and rich Related Galleries rows; fixed `global:` suggestion completion prefix; added Active Downloads vs Saved Offline split and Search result count.
- Recommendations (`WorkspaceRecommendations.kt`): ported iOS `RecommendationProfile`, parallel seed search expansion, `matchesPersistentTagFilters`, and `similarityScore`.
- Reader Resume & Preload (`LastReadPages.kt`, `ReaderActivity.kt`, `DownloadService.kt`, `SettingsActivity.kt`): added `Resume preload` setting (`From Beginning` / `Around Resume Page`), RTL vertical hint, `Resume Reading` prompt (`Yes`/`No`), persistent `LastReadPages` cleared with history, and `DownloadService` anchor-centered `readerLoadOrder` enqueueing & reprioritization.

Verification:
- Android `assembleDebug`, `testDebugUnitTest` (21 cases across 7 test suites, including 3 new `PortParityTest` cases), and `lintDebug` passed.
- APK `artifacts/Umtomi-v5.15.3.apk` (`C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.15.3.apk`) and delivery copy `C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.15.3.apk`, both SHA-256 `B4EAE1EAE5D40921C1CBA155CC8F2DDED55047FB93CA35F7AD5F7B4752A0266B` (version 5.15.3 / code 103, package `xyz.sabok.umtomi`).
- Verified in-place install (`adb -s 127.0.0.1:16416 install -r`), Search, Tab Bar Editor (with More toggle + live preview), Favorites (Works/Artists switcher + header), Gallery Detail metadata, Settings, and Reader on MuMu DEV (`127.0.0.1:16416`); confirmed pixel RGB `(0, 0, 0)` on background and bottom bars across screenshots. Real-device biometric unlock, Google Drive sync, and iOS macOS build remain pending.

# Android 5.15.2 settings port preview

Replaced the active `SettingsActivity` with the iOS-style Android form. Main navigation opens it directly. Removed the old settings and classic browser routes from active navigation and removed the classic browser entry from the manifest. Sections include language and dark mode, Drive, default query and persistent filters, recommendation count, favorite JSON backup/restore, tab order, reader mode, privacy, data and Android updates. Per-page size and tab placement reuse saved Android values. Persistent filters apply to search and recommendations. Updated the search intersection so subsequent required terms cannot repopulate an empty intersection.

The Drive settings page shows account controls, current sync progress, local/on-demand images, background sync and notifications. Uses the existing Drive and bookshelf implementations and storage-mode preference, with no format or protocol change.

New app password is stored as a salted PBKDF2 verifier in device-private no-backup storage. Existing PIN/pattern remain valid until the authenticated user creates an app password. Biometric unlock is optional with password fallback. App background duration controls relock and immediate mode visually disables the delay row. Legacy setup screens are outside normal navigation.

APK version 5.15.2 / 102, package and certificate match 5.15.1. Gradle assembleDebug, testDebugUnitTest and lintDebug passed. APK `artifacts/Umtomi-v5.15.2-iOS-port-preview.apk` at `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.15.2-iOS-port-preview.apk`, copied to `C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.15.2-iOS-port-preview.apk`. Both match SHA-256 `04b0d87b16d3b62e01f627f6e698e263ae7dc4dc826fa67225fd251787c56941`. Verified update installation and opened Settings, Drive and password setup on MuMu DEV. Reader resume prefetch and real biometric/Drive verification remain pending. GitHub visibility and release destinations were unchanged.

MuMu DEV also verified temporary password creation, disabled delay control in immediate mode, relock after leaving to the launcher, password unlock, and password removal through the app.

# Android 5.15.1 iOS port preview

The previous preview retained the legacy home. User asked to start from the iOS app. `MainActivity` now creates a new six-tab workspace with an adaptive cover grid, a search field with suggestions, sorted paged browsing, a full detail destination with metadata and related galleries, locally saved lists, a bookshelf folder index and grouped settings. Detail actions preserve offline downloads, favorites and existing reader tabs. The older entry point remains accessible under Settings → Support → Classic browser tools. The default launcher keeps its package and component identity.

The bookshelf photo screen uses an iOS-style folder picker row, note field, column stepper and toolbar menu. It calls the existing bookshelf rendering, storage, export and sync code. No changes to the Omyaru shared bookshelf protocol or files. Reader uses the existing zoom, paging, capture and tab data with a dark toolbar, blue selected tab and capture/tools moved above the reading surface.

Built APK version 5.15.1 / 101. Gradle assembleDebug, 18 unit tests and lintDebug passed. Same package `xyz.sabok.umtomi` and APK certificate as 5.14.11 and 5.15.0. Verified in-place installation on MuMu DEV and the separately delivered APK checksum. `artifacts/Umtomi-v5.15.1-iOS-port-preview.apk`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.15.1-iOS-port-preview.apk`. Delivery `C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.15.1-iOS-port-preview.apk`. SHA-256 `d4109f77ff53ca49cf02a3f440c73d12f06e13676e2eb89a3c2dfdedc0924920`.

Remaining platform differences include the favorite-folder and artist management screens, the full reader implementation, recommendation filtering/preferences and app-lock verification. Confirm full Android/iOS parity before publishing any Android port release or changing repository visibility. No GitHub publication or visibility changes in this turn.

MuMu DEV confirmation on 5.15.1 included a two-column browse grid, detail navigation, reader launch and toolbar actions, history and the reader resume button, the bookshelf screen and its folder menu, and grouped settings. Capture output and real-account Drive synchronization were not exercised in this build.

# iOS 5.4.5 release and Android 5.15.0 preview

iOS restores the native tab bar look used in 5.4.3 with a standalone UIKit tab bar. The current tab ordering and optional More configuration are preserved. Immediate locking dims the disabled delay row. Isolated iOS commit `44760e3c5388e984f884dc15d08c87e7eb51b5cf` is on private `omyeol/Umtomi` main. macOS Actions run https://github.com/omyeol/Umtomi/actions/runs/44737074064 passed. The unsigned IPA is 5.4.5 build 7, bundle ID `xyz.sabok.umtomi`. Verified the ZIP, Mach-O, version, OAuth configuration and public download checksum. No iPhone installation or visual check.

- Public release https://github.com/omyeol/Umtomi-releases/releases/tag/ios-v5.4.5
- LiveContainer source https://raw.githubusercontent.com/omyeol/Umtomi-releases/main/livecontainer.json now lists 5.4.5. The URL and older versions are preserved. The Android latest release is still v5.14.11.
- IPA `artifacts/Umtomi-iOS-5.4.5.ipa` at `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-iOS-5.4.5.ipa`, SHA-256 `c9ac4dd959560fd9a3ce5b7c1183b4d89150a260c9798c35cb6455a22864190e`. Local `Publish-iOSRelease.py` can repeat the asset and source update.

Android preview introduces an iOS-inspired home, configurable six-tab navigation, gallery rows, related-work recommendations from history and favorites, favorite artist editing and search-based automatic folder membership. The existing reader, libraries, downloads, captures and local data remain in use. New Google Drive options schedule opt-in Umtomi bookshelf sync with WorkManager and expose optional progress notifications for scheduled and manual sync. Umtomi scheduling uses the existing shared bookshelf adapter and serialization. The Omyaru sync protocol, shelf storage contract and data formats are untouched.

- Android `assembleDebug`, `testDebugUnitTest` (18 cases) and `lintDebug` passed. APK ID `xyz.sabok.umtomi`, version 5.15.0, code 100 and signing certificate match the intended upgrade path from 5.14.11. Verified both copies against SHA-256 `d1f0c1de7f4fc81152a2c02dc0281764664b46a454f66693c7567d8b2a11bee7`.
- APK `artifacts/Umtomi-v5.15.0-preview.apk` at `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.15.0-preview.apk`, also delivered to `C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.15.0-preview.apk`.
- Device tests are pending because no Android device was attached. Check an in-place upgrade, navigation on compact displays, the deferred folder rule lookup, manual and scheduled sync with a test Drive account, actual notification delivery and existing shared shelf access from both Android apps.
- Parity is pending for app password and relock controls, full metadata/tag-based recommendation preferences, reader/settings presentation and real-device feature verification. Source remains private and the preview was kept local. Updater accepts newer APK releases from public `omyeol/Umtomi` once available and falls back to `omyeol/Umtomi-releases` otherwise. Before public cutover, audit the source and history, and plan a bridge release or manual upgrade for installed clients tied to the legacy endpoint. Publish to the main repo when the user's parity condition is met.

# GitHub Actions iOS IPA build

User chose GitHub-hosted macOS builds over Henge/SSH. Authenticated API verified `omyeol/Umtomi` private, default `main`, Actions enabled. Added `.github/workflows/ios-ipa.yml` with manual dispatch and iOS-only main push trigger. macOS 26/Xcode 26.6 builds with XcodeGen, checks the app and stores an unsigned IPA plus SHA-256 as a private Actions artifact for 14 days. Existing local build script supports `--no-icloud`; its old iCloud behavior stays available for local builds. The configured public iOS OAuth client ID is included through `DriveSync.xcconfig`; ignored local override and account secrets were excluded.

Used isolated checkout from remote main so local dirty Android/history files and staging were untouched. Pushed iOS source plus workflow only in `cf40105`, then fixed a Swift memberwise initializer access error and xcodebuild failure propagation in `84994af`. Original Windows checkout is still not reconciled with remote main; future pushes should explicitly use current remote main and stage reviewed iOS paths only.

Successful run https://github.com/omyeol/Umtomi/actions/runs/44735651666, artifact https://github.com/omyeol/Umtomi/actions/runs/44735651666/artifacts/14628566985 . Downloaded IPA relative `artifacts/Umtomi-v5.4.4-unsigned.ipa`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.4.4-unsigned.ipa`. SHA-256 `085d92e8e2bb8889d3c70b9a9a893b792552d30fd27d945dfc24fcab12a81fdb`, 1,907,327 bytes. Verified checksums, ZIP integrity, arm64 executable, bundle ID `xyz.sabok.umtomi`, 5.4.4 build 6, OAuth callback and background identifier. No signed installation, XCTest or real iPhone check in this turn. Run from https://github.com/omyeol/Umtomi/actions/workflows/ios-ipa.yml or push an iOS change to main.

# iOS 5.4.4 tab bar and app password lock

User requested direct Favorites/Bookshelf tabs, optional configurable More, mandatory app password with optional biometrics first, immediate unlock and immediate/delayed relock. RootView uses a custom tab bar so six items stay directly visible by default. Legacy settings retain tab order. More can be enabled and items moved between two reorderable groups. The user can always navigate to Settings from More. AppLock saves a salted PBKDF2 verifier in device-only Keychain, offers optional Face ID/Touch ID first with app-password fallback, and prompts old device-auth users to authenticate before creating an app password. The delay starts when entering background and takes effect upon return. Immediate mode disables minutes; manual lock is separate. Authentication success suppresses the inactive privacy shield during system prompt dismissal and clears the lock without animation.

Version 5.4.4 / build 6. Configured archive `artifacts/Umtomi-iOS-v5.4.4-Omyaru-OAuth.zip`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-iOS-v5.4.4-Omyaru-OAuth.zip`. SHA-256 `ce7d4735a8e8d42999b3468d37367456f6a7e145238be1f2215691a819204231`. Checked 25 Swift syntax trees, visible spec/plist values, Bash script syntax and all 54 archive entries. Added legacy tab settings and More partition XCTest coverage. Xcode/iPhone checks are outstanding, particularly upgrade with legacy authentication, tab navigation on a narrow screen, Face ID dismissal latency, app-password fallback and timed relock. No IPA generated.

# iOS 5.4.3 sync progress notifications

User confirmed the first sync took a long time and asked for live progress notifications for normal and background sync. Added an opt-in `동기화 진행 알림` toggle to DriveSyncSettings and a numeric percentage for the current phase on the screen. DriveSyncNotifications requests local alert permission from the toggle and reuses one silent, passive Notification Center entry. DriveSyncCoordinator samples changed status/phase percent up to every four seconds for manual sync and scheduled BGProcessing sync, then replaces the same notification with the outcome. Short automatic foreground checks under 12 seconds do not post; longer checks can show progress. No protocol or shared storage changes. Background notification delivery still depends on iOS actually launching a task.

Version 5.4.3 / build 5. Configured archive `artifacts/Umtomi-iOS-v5.4.3-Omyaru-OAuth.zip`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-iOS-v5.4.3-Omyaru-OAuth.zip`. SHA-256 `cfacfb0b6aeb5b25c48d984f19989cb95a35d4569fa75d07269d5d0598b3336b`. Verified 22 Swift syntax trees, OAuth and BGTask settings, Bash syntax and all 51 ZIP entries. No Mac/Xcode or iPhone verification on this Windows host. Next test notification permission grant/denial, manual progress replacement/completion, and a real BGProcessing run on iPhone. No IPA generated.

# iOS 5.4.2 Drive sync progress and login deadlines

User saw the same “Google Drive 확인 중” status for ten minutes on iPhone. The status previously covered login, key lookup and the entire first pull of remote shelf changes. No device logs were available to identify the exact stage. DriveSyncCoordinator now reports stages, batch counts and original chunk progress. Records received are saved at eight-record intervals so a failed retry reuses completed batches. DriveAccountDeadline bounds Google SDK login restoration and token refresh to 25 seconds per operation and allows cancellation even if the underlying SDK callback returns late. DriveFiles request timeout 25 seconds and resource limit 90 seconds per request. DriveSyncSettings shows a spinner when there is no numeric estimate.

Version 5.4.2 / build 4. Configured source archive `artifacts/Umtomi-iOS-v5.4.2-Omyaru-OAuth.zip`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-iOS-v5.4.2-Omyaru-OAuth.zip`, SHA-256 `b64b52e2fd3a34597429e30c99a7e0483b4190a6251aab5f0454aa4d9afd9155`. Checked 21 Swift syntax trees, OAuth config, all 50 archive entries and Bash syntax. Added delayed SDK callback timeout/cancellation XCTest; Xcode not available on Windows, so tests and on-device behavior remain unverified. Rebuild the configured ZIP on Mac and check the status, count or error on iPhone. No IPA or cloud writes.

# iOS 5.4.1 Omyaru OAuth configuration

User provided the public iOS OAuth client ID for Omyaru project `136563471424`. Configured the workspace-local `umtomi/ios/DriveSync.local.xcconfig` with the client ID and derived callback scheme, using the existing optional include for Debug and Release. Bundle ID remains `xyz.sabok.umtomi`. Packaged a separate configured source ZIP at `artifacts/Umtomi-iOS-v5.4.1-Omyaru-OAuth.zip` (`C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-iOS-v5.4.1-Omyaru-OAuth.zip`). SHA-256 `ce3829f8d5e3f1325e7fe4c0ac815d4bd55aca2feee1570354f72a459ac88029`. Verified 48 ZIP entries, the reversed client ID, build config references and Info.plist substitutions. No account secrets in the ZIP. Next verify Xcode build and actual sign-in/Drive sync on Mac and iPhone. No IPA or cloud writes.

# iOS 5.4.1 common bookshelf and Drive sync source

User requested common bookshelf, Umtomi favorites/history and optional background bookshelf sync, then confirmed no known iOS OAuth client and requested Android's package ID. Android applicationId and iOS Bundle ID are already `xyz.sabok.umtomi`; preserved. Implemented iOS source for Google Sign-In with narrow Drive appdata scope, existing key/envelope namespaces, shelf and userdata replica/outbox, verified chunk upload/download, catalog backup and pre-apply edit check, local/on-demand original handling, and account binding. Omyaru and Android code/protocol unchanged. Existing shared shelf key must be readable before enabling writes. Missing Umtomi userdata key produces a partial completion status. No cloud project or credential changes were performed.

The iOS settings and bookshelf button expose manual sync, progress, local/on-demand storage and background opt-in (off by default). BGProcessing task identifier `xyz.sabok.umtomi.bookshelf-sync` is registered at launch with network connectivity and cancellation. Back-to-back foreground attempts are rate limited and only one sync runs at once. iOS determines task launch time. `RESTART_KO.md` documents creation of an iOS client in the project that can see the existing shelf data, and local public client ID and callback scheme config in `DriveSync.local.xcconfig`.

Source ZIP `artifacts/Umtomi-iOS-v5.4.1-source.zip`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-iOS-v5.4.1-source.zip`, SHA-256 `fa5f163a5d9354f616581ab70cf27bf29a98485b350928bd46db5466160363c1`. Verified 47 archive entries, 19 Swift syntax trees, bundle ID and matching background plist/spec, Bash syntax, both encryption test vectors via independent Python decrypt. Added XCTest for replica and envelope round trips but no Xcode/test target execution, IPA, OAuth login, real Drive round trip or background run on this Windows host. Verify against a test account with known cross-platform records on Mac/iPhone before relying on syncing a production library. Apple and Google SDK integration still needs Xcode build/typecheck.

# iOS 5.4.0 Android feature port

User confirmed the prior iOS source builds on their Mac and requested broad Android feature parity. Ported local bookshelf hierarchy, folder browsing, reorder, subtree move with cycle prevention, descendant gallery/PDF and durable catalog writes in `Bookshelf.swift`. Optional parent IDs support pre-existing catalogs. `AppStore` owns one bookshelf instance for the bookshelf screen and capture destinations. `ReaderCaptureView.swift` adds crop preview, stitching from current page through up to eight pages, PNG Files export, folder choice and shelf creation. `Views.swift` adds a single-page toggle in vertical reader mode and download cancel. `DownloadLibrary.swift` checks cancellation before page writes and final installation, and AppStore quiesces downloads before clearing. `UmtomiApp.swift` adds opt-in device authentication on launch and after backgrounding. XcodeGen and Info.plist now use iOS 5.4.0 build 2 and include Face ID purpose text.

Source transfer ZIP `artifacts/Umtomi-iOS-v5.4.0-source.zip`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-iOS-v5.4.0-source.zip`. SHA-256 `dcaf7a7c173f4fbe7c3a4f9ee071509c70fcb253ccaaa0dd8ff4b13998f77692`. All 38 entries match workspace files. Swift syntax parsed for 11 Swift sources/tests, plist properties agree with project spec, and Bash syntax passed. Added legacy bookshelf decode XCTest, not executed here. Previous Mac build confirmation applies only to the older source snapshot. This Windows host has no Xcode or iOS SDK. No new IPA or signed-device validation.

On Mac, unpack this archive, regenerate with XcodeGen and use `RESTART_KO.md` to test old catalog upgrade, capture preview/export, nested shelf moves, download cancellation and actual device lock. Shared Google Drive bookshelf and favorites/history sync, local/on-demand hydration, cross-app shared originals, Android updater, Wi-Fi Direct, hardware volume controls and guaranteed background download continuation remain outside this code change. Android and Windows already have comparable bookshelf changes; this port only touches iOS and does not change their storage protocol.

# 2026-11-20: iOS restart preparation for a different Mac

User wants to resume iOS development and confirmed they will use a different Mac. Recovered old workflow from this handoff: native SwiftUI app, XcodeGen project (`umtomi/ios/project.yml`), unsigned IPA built on old Mac at `/Users/omyeolmacair/DEV/Umtomi/umtomi/ios` and copied to iCloud Drive `IPA`. The old iPhone signing/installation tool is NOT recorded; do not invent it or imply unsigned IPA installs by tapping it.

Added Korean new-Mac guide `umtomi/ios/RESTART_KO.md`, linked from iOS README. Added `--check` and `--help` to `umtomi/ios/build-ipa.sh`: preflight checks macOS/full Xcode/iPhoneOS SDK/XcodeGen and prints paths without generating project, building, or cleaning IPAs. Normal IPA build/iCloud cleanup behavior retained. Script normalized to LF for Mac use. No app Swift source, identity, version or signing configuration changed. Project remains iOS17 / Swift5.9 / bundle ID xyz.sabok.umtomi; XcodeGen marketing version 5.3.24 differs from fixed Info.plist 1.0 (both build 1): align before next real iOS release. Existing generated .xcodeproj predates Bookshelf.swift; regenerate rather than using it directly.

Prepared a local source-only transfer ZIP (NOT an IPA), relative `artifacts/Umtomi-iOS-restart-20261120.zip`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-iOS-restart-20261120.zip`. Contains 36 allowlisted source/doc/assets under `Umtomi-iOS/`, excluding stale generated .xcodeproj, build outputs, account data and signing files. SHA256 `f60926479197cdcf8cabf8cbf7f49a4270b1f837b7e7f2d91dd5d0400c35e00f`; adjacent .zip.sha256 file. Verified ZIP CRC, every entry byte-for-byte against current sources, plist/JSON parsing, shell LF, bash syntax/help/non-Mac guard with Git Bash. No macOS compile, XCTest, simulator, iPhone installation or IPA produced; no network/device/publish actions.

Next on new Mac: unzip to e.g. `~/DEV/Umtomi-iOS`, install/open full Xcode and install XcodeGen, run `bash build-ipa.sh --check`, regenerate `xcodegen generate --spec project.yml`, open Xcode, build simulator and run tests. Recover iPhone install method and back up old app data before real-device replacement. iOS shelf is still separate local `UmtomiBookshelf`; Google Drive/common shelf and on-demand selection from Android/Windows are not ported. No Android/Windows counterpart changes required for this preparation-only task.

# 2026-10-12: Umtomi & Omyaru Bookshelf Folder Tree UI Enhancement & PC Fixes

Coordinated update across Umtomi and Omyaru per user request to maintain shared bookshelf codebase parity (Umtomi, Omyaru Browser Android, Windows PC desktop and standalone Bookshelf).

### Changes
- In `xyz.sabok.umtomi.ui.BookshelfActivity.kt`:
  - Added folder bar navigation: `[📁 폴더 트리 ▾]` button, breadcrumb trail pills for current active path segments (allowing 1-tap ancestor navigation), and sibling folder quick-switching chips.
  - Added hierarchical folder tree dialog (`showFolderTreeDialog`):
    - Complete tree hierarchy displaying all folders and synthesized ancestor nodes.
    - Level-based indentation (`level * 20dp`).
    - Expand/collapse chevrons (`›` / `⌵`) for subtrees.
    - Folder icon 📁, leaf folder typography, and photo count badges `(N)` for each node.
    - Direct 1-tap folder selection with active folder highlighted.
    - `⋮` action menu for every folder (child folder creation, renaming, moving, deletion).
    - Top "＋ 새 폴더" button for instant root folder creation.
  - Visual destination picker: Moving folders ("다른 폴더로 이동") displays tree hierarchy with 1-tap "최상위로 이동" and "이동" destination buttons, preventing circular self/child moves.
- Matched in Omyaru Browser Android `dev.privacybrowser.app.BookshelfActivity.kt`.
- PC desktop & standalone Bookshelf: Fixed `ObjectDisposedException` on photo right-click by using managed `ContextMenuStrip` property on `VirtualBookshelfGallery`, and eliminated UI flickering on tree select / browser button clicks.

### Verification & GitHub Releases
- Umtomi v5.14.11 (versionCode 99) published to https://github.com/omyeol/Umtomi-releases/releases/tag/v5.14.11 via `Publish-AndroidRelease.py --publish`.
- APK path: relative `artifacts/Umtomi-v5.14.11.apk`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.14.11.apk`, delivery `C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.14.11.apk`. SHA-256: `42c7a917629c1c5f237b55093d1d0c4b7eb9bdadd9cc1a1694d66622fb7a24df`.
- Omyaru Browser Android v0.15.10 (versionCode 68) published to https://github.com/omyeol/Omyaru-Browser/releases/tag/v0.15.10 via `scripts/upload-release-apk.ps1`.
- Omyaru APK path: relative `artifacts/Omyaru-v0.15.10-arm64.apk`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/BROWSER/artifacts/Omyaru-v0.15.10-arm64.apk`, delivery `C:/Users/peopl/OneDrive/DEV/APK/Omyaru-v0.15.10-arm64.apk`. SHA-256: `bb5a5fe7916b7acdaa956680541ec80813557c577f8a0c09cede456f6aae61fa`.
- Windows Desktop and Standalone Bookshelf self-tests and smoke-tests passed (0.4.63 / 0.1.37).

---

# 2026-10-12: Umtomi & Omyaru Bookshelf Parent Folder Column Fix

Coordinated update across Umtomi and Omyaru per user request to maintain shared bookshelf codebase parity (Umtomi, Omyaru Browser Android, Windows PC desktop and standalone Bookshelf).

### Changes
- In `xyz.sabok.umtomi.ui.BookshelfActivity.kt`:
  - `renderGallery`: removed forced 1-column lock on parent folders (`columnCount = store.columnCount()`).
  - `exportCurrentFolderPdf`: removed forced 1-column lock (`columns = store.columnCount(folder)`).
  - `changeColumnCount`: removed early return on parent folders with children.
  - `updateColumnControls`: removed `combined` check so `decreaseColumns` and `increaseColumns` buttons stay enabled and display `store.columnCount()`.
- Matched in Omyaru Android `dev.privacybrowser.app.BookshelfActivity.kt` and Windows PC Desktop/Standalone `Bookshelf.cs`.

### Verification & GitHub Releases
- Umtomi v5.14.10 (versionCode 98) published to https://github.com/omyeol/Umtomi-releases/releases/tag/v5.14.10 via `Publish-AndroidRelease.py --publish`.
- APK path: relative `artifacts/Umtomi-v5.14.10.apk`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.14.10.apk`, delivery `C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.14.10.apk`. SHA-256: `35246c7484bc0476e5b10f03aa6a74c8e3741666f2c721e03ffed2aeef6704f0`.
- Omyaru Browser Android v0.15.9 (versionCode 67) published to https://github.com/omyeol/Omyaru-Browser/releases/tag/v0.15.9 via `scripts/upload-release-apk.ps1`.
- Omyaru APK path: relative `artifacts/Omyaru-v0.15.9-arm64.apk`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/BROWSER/artifacts/Omyaru-v0.15.9-arm64.apk`, delivery `C:/Users/peopl/OneDrive/DEV/APK/Omyaru-v0.15.9-arm64.apk`. SHA-256: `76f0c1db65110644629e9d0fbfb6d85f913b2d488f678c54bbdef635f14eb533`.
- Windows Desktop and Standalone Bookshelf self-tests and smoke-tests passed (0.4.62 / 0.1.36).

---

# 2026-10-11: Windows bookshelf virtual ancestor folders

BROWSER PC bookshelf v0.4.49/standalone v0.1.23 now renders `Umtomi/paradise` under a virtual `Umtomi` parent even though `Umtomi` is not itself a stored catalog folder. Actual PC catalog has that path among 6 folders/49 photos. The display-only fix and synthetic nested-tree smoke test passed. No Umtomi source, APK, storage data or device state changed. See `../BROWSER/docs/AI_HANDOFF.md`.

---

# 2026-10-11: Windows bookshelf folder tree UI

BROWSER desktop 0.4.48 and standalone bookshelf 0.1.22 now display hierarchical folders in a wider left sidebar, with collapse/expand, selection and direct image counts. Both builds and published EXE self/smoke tests passed; see `../BROWSER/docs/AI_HANDOFF.md`. Catalog, Drive sync and storage layout did not change. No Umtomi source, APK or device data changed; Android UI was not tested in this PC-only UI task.

---

# 2026-10-11: PC bookshelf Drive receive/recovery fix

The latest BROWSER Windows desktop 0.4.47 and standalone bookshelf 0.1.21 restore 6 folders/49 verified originals from the common Google Drive bookshelf, protect unexpectedly empty local catalogs from propagating deletions, and keep repeated sync from re-uploading unchanged photos. The new standalone release is running with sync enabled and no missing originals. The older 322-entry catalog with 311 missing originals is preserved and was not merged. See `../BROWSER/docs/AI_HANDOFF.md` for details. No Umtomi source, APK or Android device data changed in this turn; Android UI was not tested. If the user has mobile items beyond the Drive projection of 6 folders/49 photos, diagnose the Android upload separately.

---

# 2026-10-10: PC Browser bookshelf AppData recovery

User reported PC Browser bookshelf sync blocked by a stale selected storage path. BROWSER desktop 0.4.43 and standalone 0.1.15 now offer AppData default recovery and existing-catalog reconnect, preserving prior location settings and pausing sync. On this PC the unavailable Browser storage setting was backed up and reset to `%LOCALAPPDATA%/PrivacyBrowser/bookshelf`; auto-sync was paused. No original images were recovered or remote sync performed. No Umtomi or Omyaru Android sources/APKs changed. See ../BROWSER/docs/AI_HANDOFF.md.

---

# 2026-10-10: PC standalone bookshelf missing photos

User clarified black shelf photos occur in Windows standalone. BROWSER LocalAppData standalone catalog references 322 images, of which 311 originals are absent; 11 present originals decode normally. Browser Windows standalone 0.1.14 and desktop 0.4.42 replace black tiles with missing-original warnings and pause cloud sync while originals are missing. No Umtomi or Omyaru Android source changes, APKs, or device data changes were needed. The absent photos have not been restored; see ../BROWSER/docs/AI_HANDOFF.md.

---

# 2026-10-10: Omyaru Windows standalone client configuration fix

User reported OmyaruBookshelf PC Google client missing. BROWSER Windows standalone 0.1.13 and desktop 0.4.41 now offer JSON import; standalone reuses same Windows account Browser client configuration, without copying tokens. Old selected storage is unavailable, so both Windows builds now fail closed on catalog writes and cloud sync until medium returns. Built/tested locally; actual OAuth not exercised. No Umtomi Android code or APK change. Details: ../BROWSER/docs/AI_HANDOFF.md.

---

# 2026-10-10: Umtomi 5.14.1 (89) bookshelf selector width

User reported Umtomi bookshelf selector much smaller than Omyaru Browser. Compared both Android BookshelfActivity.renderFolders: Umtomi used WRAP_CONTENT, Omyaru used display width minus 40dp. Umtomi now uses same width and single-line marquee for long paths. Omyaru Android/Windows selector already met desired size; no Browser production changes or binaries needed. Shared storage/protocol unchanged.

Verification: assembleDebug, 13 JVM tests, lintDebug passed. MuMu DEV index1 / 127.0.0.1:16416 updated via install -r; existing BookshelfHierarchyUiTest passed (one test, folder selector/navigation and rename). No user catalog clear; test removes its synthetic folder and restores active folder. Existing signing certificate and package retained. No other emulator or host network changes.

Published https://github.com/omyeol/Umtomi-releases/releases/tag/v5.14.1; anonymous public asset digest verified. APK workspace relative artifacts/Umtomi-v5.14.1.apk, absolute C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.14.1.apk; delivery C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.14.1.apk. 20,858,824 bytes, SHA256 78559296f7315ce9a8e6d10d5c4463fa0fb225e8772c95103ab394a017a95387. Source remains private/local and unpushed. Release note artifacts/RELEASE-v5.14.1.md. No outstanding implementation/build work.

---

# 2026-10-10: Umtomi 5.14.0 (88) unified Google account

User requested single bookshelf/cloud login and fix for shared-shelf connection stuck at Google Drive checking. Previous SharedShelfActivity automatically started blocking shelf transfer after authentication with no status change. It now redirects to DriveSyncActivity; only one account/login/disconnect UI. New CloudAccount migrates a sole/same legacy account, requires explicit selection for conflicting old accounts, mirrors legacy prefs for compatibility, and disconnects both. Bookshelf foreground auto-sync reads canonical account. Connection authenticates only; explicit Sync now runs Umtomi favorites/history (no legacy shelf, stable consolidated state namespace) then shared shelf. Shared key prerequisite remains unchanged: missing Omyaru keys produces actionable partial-success message; no automatic key generation/browser data access added.

Authorization has 30-second watchdog, generation guard against late task replies, cancel/Back, consent-result state restoration; timeout/cancel/failure retains previous account. Shared transfer reports stages/image counts; pending main-thread task is cancelled on worker interruption. Shared/Umtomi sync coordination now uses interruptible ReentrantLock so cancellation can escape lock waits. Existing 10-minute sync limit remains. Protocol, originals, OAuth scopes/signing/app ID unchanged. Browser Android/Windows account flows/protocol need no matching change; no Browser production files or binaries changed.

Validation: assembleDebug, lintDebug (no errors), 13 JVM tests pass. MuMu DEV 127.0.0.1:16416 installed -r; CloudAccountTest migration/conflict/disconnect and lock-wait interruption (2), DriveSyncTest repeated authorized completion + timeout UI recovery (1) passed. Synthetic preferences restored. Tests invoke completion/timeout handlers, not real Google authorization; real Google OAuth/Drive transport and slow network timeout not verified. No user data clear or other emulator/host network changes.

Published https://github.com/omyeol/Umtomi-releases/releases/tag/v5.14.0; anonymous public asset digest verified. Source remains private/unpushed. artifacts/Umtomi-v5.14.0.apk = C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.14.0.apk; delivery C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.14.0.apk. 21,166,896 bytes, SHA256 f479911eb5c6f8894431b449eac170a976b126bbdc5a8e8b3bfd0e743426cdab. Same prior signing certificate; signature-v5.14.0.txt and RELEASE-v5.14.0.md in artifacts. A first signature command piped to Select-Object -First aborted output and returned nonzero; rerun with full file redirection passed, artifact unchanged.

---

# 2026-10-10: Umtomi 5.13.1 (87) account reconnect fix

User reported Google account connect/change stuck at legacy shelf transfer. Code showed every authorization automatically ran full blocking sync and prevented Back. Separated connection-only authorization from explicit Sync now; preserved action across consent recreation. Added per-image progress to Umtomi legacy transfer, cancel button/Back cancellation using runInterruptible, 10-minute operation timeout, finally UI reset, and cancellation of queued onMain FutureTask. No remote protocol/storage changes. Omyaru Android/Windows use their own account flows; no corresponding shared-engine change needed and no Browser binaries built.

Verification: build + lint passed, 13 JVM tests passed. MuMu DEV 127.0.0.1:16416 data-preserving installs; new DriveSyncTest.reconnectCompletesWithoutStartingShelfTransfer passed (repeated completion with deliberately invalid synthetic token, no transfer triggered, actions enabled). Test restores prior account preference. Real Google authorization/network hang not reproduced; no real Drive transport or timeout/cancel network test. APK package xyz.sabok.umtomi and existing signing certificate retained.

Artifacts: artifacts/Umtomi-v5.13.1.apk (absolute C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.13.1.apk), delivery C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.13.1.apk. 20,860,297 bytes; SHA256 5c1b0b42a350e731432740f166e31d9b1ae22ec7f16132af96b5faf3bd08e74b. Release notes artifacts/RELEASE-v5.13.1.md. Published https://github.com/omyeol/Umtomi-releases/releases/tag/v5.13.1; anonymous public asset digest verified. No source push; no device data clear or changes to other emulators/network.

---

# 2026-10-10: shared local bookshelf / hierarchy — 5.13.0 (86)

User's persistent instruction: develop bookshelf features together in Umtomi and Omyaru Android, Windows desktop, and standalone bookshelf. New Umtomi/AGENTS.md and Browser/AGENTS.md record this. Read BOOKSHELF_STORAGE_CONTRACT.md and ../BROWSER/docs/SHARED_BOOKSHELF_STORAGE.md.

Implemented:
- Common Android optional storage at `<volume>/Omyaru_Bookshelf`; Android 11+ per-app MANAGE_EXTERNAL_STORAGE grant and explicit connect through Bookshelf gear. No arbitrary path selector. Internal default, removable volume choices. Both apps share a location record; missing selected media fails closed.
- Identical five Kotlin components in both packages: BookshelfPath, SharedBookshelfCatalog, BookshelfFileTransaction, SharedBookshelfStorage, BookshelfStorageActivity. Catalog merges use stable IDs, field-level 3-way updates and tombstones; cross-process file/location locks; verified copies, atomic catalog/pending records; volume migration journal and verified cleanup.
- Existing app catalogs migrate under Umtomi/ or Omyaru Browser/ while preserving IDs. Already-synced identical IDs share one original. Private source copies removed only after verification. Distinct image IDs are intentionally NOT content-deduplicated.
- BookshelfStore integrates opt-in shared catalog while preserving legacy private mode before connection / on older Android. Do NOT silently fall back when enrolled storage becomes unavailable. Captures/imports use existing store integration. Shared-local enrollment disables legacy Umtomi shelf sync; favorites/history remain separate.
- Folder navigator, child creation, virtual-parent rename, subtree move; IDs/notes/columns/items preserved. Nonempty parent deletion requires resolving children first. Shared shelf reload while foreground, plus on resume.
- Shared Drive engine and Browser engine reuse an existing verified manifest when only local original path changed, avoiding upload after folder/storage moves. No OAuth config or account data changes; real-account Drive still unverified.
- Windows desktop/standalone gain matching path/tree/rename/move UI, real image subdirectories, copy verification before catalog commit and cleanup. Desktop 0.4.40 / standalone 0.1.12 published locally as self-contained executables. Windows retains its existing local storage-location setting.

Verification:
- Umtomi JVM tests: 13 passed; lintDebug: no errors. Browser JVM tests: 85 passed; release/lintVital passed. New path/merge/file-transaction tests include stale deletion, conflicting edits, retry/dedup, missing originals and pending-journal recovery.
- MuMu DEV ONLY, index1 / 127.0.0.1:16416, API32. Installed both final APKs with -r. SharedLocalStorageTest passed (two contexts, volume switch, stale edit, unavailable/reinserted test medium, switch back). BookshelfHierarchyUiTest passed. Existing BookshelfTest six cases and ReaderUiTest passed.
- CrossAppShelfTest executed Umtomi create -> actual Browser modify -> Umtomi verify against one synthetic directory on real emulated shared storage. Final APK round trip passed, one PNG persisted. Test artifacts cleaned; both packages' temporary MANAGE_EXTERNAL_STORAGE appops restored to default. User's real catalogs/accounts were NOT enrolled/migrated by the tests. MuMu DEV left running; no uninstall/clear-data/VPN/network changes.
- Final Windows standalone --self-test and --smoke-test exit0; desktop --self-test exit0. Pure Android sources match after package/whitespace normalization.
- Actual removable SD hardware and real Google-account round trip remain unverified; release notes disclose this.

Artifacts:
- Umtomi/artifacts/Umtomi-v5.13.0.apk and C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.13.0.apk; 21,048,719 bytes; SHA256 05c7410137ca3ef27a489b70904533be3fc3ceda56d29b3c5e3ba7c885d62372.
- Umtomi release published: https://github.com/omyeol/Umtomi-releases/releases/tag/v5.13.0. Source repository remains private.
- Browser apps/android/.build-release-0150/app/outputs/apk/release/app-arm64-v8a-release.apk is a verified staging copy of the external Temp build. Delivery C:/Users/peopl/OneDrive/DEV/APK/Omyaru-v0.15.0-arm64.apk; 199,398,197 bytes; SHA256 600d578a240a5669366a314074d7eac69f74bdf0122ae04b866fed1e34cae6e4. Browser release published and anonymously verified: https://github.com/omyeol/Omyaru-Browser/releases/tag/v0.15.0. Both public asset digests match the tested APKs and delivery folder copies.
- Both APK cert SHA256 9d7238f656325b90e39553e45ba5fbeb71090220a4c0d121d42adc6d6b270c80, matched previous versions. Package IDs unchanged. APK archive filename scan found only existing public ISRG root CA certificate in Umtomi; no signing/private key files.
- Windows: ../BROWSER/artifacts/releases/Omyaru-v0.4.40-win-x64/Omyaru.exe and ../BROWSER/artifacts/releases/OmyaruBookshelf-v0.1.12-win-x64/OmyaruBookshelf.exe.
- Synthetic cross-app logs: artifacts/shared-cross-app-final-test.txt. Release notes artifacts/RELEASE-v5.13.0.md.

Build output stays outside OneDrive (`-PumtomiBuildDir=C:/Users/peopl/AppData/Local/Temp/umtomi-android-build`; Browser `-PomyaruBuildDirectory=C:/Users/peopl/AppData/Local/Temp/omyaru-shared-folders`). A OneDrive intermediate lock affected an earlier Browser test build; external Temp output passed. Browser instrumentation uses optional `-PshelfTestBuildType=release` and tests only synthetic fixtures.

Release completion: both published assets verified by anonymous GitHub API (draft=false, SHA256 matches), delivery folder hashes match. No outstanding local implementation/build/release work. User setup is each app Bookshelf -> gear -> shared connect -> grant -> connect again; then same-account shared Drive setup for other devices. Do not grant all-files access on the user's real phone via automation.

---

# Codex Handoff: Umtomi iOS

Last updated: 2026-10-04

## 5.12.0 shared Omyaru bookshelf — 2026-10-10 (setup pending)

- User chose OAuth project consolidation (option 1), including Windows. Implemented Umtomi adapter to existing Omyaru shelf protocol; no Browser production code edits or new Browser binaries required. Omyaru Android, WinForms desktop and standalone bookshelf share protocol; Gecko shell has no shelf UI.
- Added sync/shared isolated copy of current Omyaru Android DriveAppDataClient, DriveAccountKeys, BookshelfSyncReplica, BookshelfImageChunks, DriveBookshelfSync; BrowserSyncCrypto matches actual privacy-browser-sync envelope/AAD. SharedShelfSync accesses key/shelf namespaces only, requires preexisting Omyaru account keys before any upload (does not create keys or touch tabs/userdata), synchronizes under legacy sync gate and detects local edits before apply. Imported UUID normalization backs up catalog and preserves originals.
- SharedShelfActivity reached via DriveSyncActivity button; explicit same-account connect and initial merge. Enables foreground 15-second BookshelfActivity loop after successful sync. Loop silently requests access, never launches consent automatically; pauses with screen, skips import, detects account/state changes. Failed transfers remain retryable. Not background WorkManager sync.
- Shared joined flag permanently prevents legacy Umtomi shelf pipeline from racing common shelf, even after disconnect. Umtomi-only favorites/history state uses fresh post-consolidation directory while preserving old state.
- REQUIRED USER ACTION before real use: download/backup old Umtomi remote data while old OAuth works; register Android xyz.sabok.umtomi with existing SHA1 in Omyaru Cloud project 136563471424; enable Omyaru shelf sync same account; connect Umtomi common shelf. SHARED_BOOKSHELF_SETUP.md has sequence. No Cloud client/real remote data changes performed; actual authenticated cross-device sync NOT verified. Do not claim setup complete.
- Build, lint (zero errors), 3 JVM tests pass. MuMu DEV index1 restarted via escalated manager launch, APK installed -r; 4 instrumentation tests pass: shared keys/deletion, Windows envelope/metadata decode→Android reply, legacy imported ID normalization preserving bytes, full ReaderUi regression. artifacts/test-results-5.12.0.txt.
- Windows fixture harness links actual Omyaru SyncCodec.cs and BookshelfSyncReplica.cs; stub SyncSnapshot only for unused browser-tab APIs. Windows→Android and Android→Windows encryption/metadata application passed. Harness artifacts/shared-shelf-fixtures (synthetic data only, no credentials). No actual Drive transport round trip or Windows UI test this turn.
- Delivered/published 5.12.0 (85), 21,101,749 bytes, same package/signature. artifacts/Umtomi-v5.12.0.apk and C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.12.0.apk. SHA256 779bf37f5d203c5f81d67fe787adbdbe0c183faf43d2620068a9f17ca4c81849. https://github.com/omyeol/Umtomi-releases/releases/tag/v5.12.0 ; anonymous latest digest checked, source still private/local/unpushed.

## Cross-app bookshelf sync investigation — 2026-10-10 (IN PROGRESS)

- User requests automatic bookshelf sync with latest Omyaru Browser, including Windows; confirms OAuth projects DIFFERENT. No shared sync implementation or new release yet. Existing APK remains 5.11.3.
- Read BROWSER/AGENTS.md, docs/AI_HANDOFF.md and bookshelf-sync-progress.md. Android metadata replica, chunk codec, transfer engine match Umtomi after package/prefix normalization. Omyaru crypto is privacy-browser-sync (not omyaru-sync), key namespace omyaru-account-key. Windows WinForms/standalone share coordinator and format; Gecko shell lacks shelf UI.
- Google appDataFolder cannot share across apps/projects. drive.file does not grant broad recursive access to other applications' new files. Asked user to choose: consolidate OAuth into Omyaru project (retain narrow appdata scope) or keep projects and opt into broader Drive access/common normal folder. Await answer; DO NOT widen OAuth scopes or claim shared sync works yet. Need legacy cloud migration/backup before moving registration if consolidation chosen.
- Independent fix prepared in BookshelfStore: file imports used hyphenated UUIDs incompatible with compact-ID replica schema. New imports compact; prepareSync normalizes existing image IDs, validates uniqueness, backs up catalog, preserves image paths/bytes. Added isolated DriveSyncTest.legacyImportedImageIdsMigrateWithoutMovingOriginals. compileDebugKotlin and compileDebugAndroidTestKotlin passed (19s). No device execution or new APK.
- Next: finish compile check, record result; obtain architectural choice and implement end-to-end Umtomi + Android Browser + Windows shared shelf protocol, migration and automatic lifecycle scheduling. Preserve separate Umtomi favorites/history. Do not touch other emulator instances, credentials or remote real data during tests.

## 5.11.3 single-page bottom button — 2026-10-04

- Replaced reader long-press single-page entry with bottom dock 한 컷 / 연속 MaterialButton. Keeps chrome visible upon switching; single tap still toggles chrome. Removed old preference UI and preference-based onResume exit; old stored preference is ignored. Long-press callback no longer assigned. Back still returns to continuous mode.
- Fast-scroller direction now follows actual continuous/single-page layout instead of fullscreen chrome state.
- Build, lint and 3 unit tests passed. MuMu DEV was stopped; launched index 1 (escalated command required), connected 127.0.0.1:16416, installed with -r. Updated ReaderUiTest passed: mode button both directions, chrome visibility, no long-press mode switch, capture/shelf creation and resume-reader regression. artifacts/test-results-5.11.3.txt: OK (1 test).
- Delivered 5.11.3 (84), 20,779,154 bytes, same package/signing certificate. artifacts/Umtomi-v5.11.3.apk and C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.11.3.apk. SHA256 63d7daf7e7a6a5f0b7ea32a756e4b9839b94b26ed6e00669ee6d0061a1d30fac.
- Published https://github.com/omyeol/Umtomi-releases/releases/tag/v5.11.3 ; anonymous latest digest verified. Source private/local/unpushed. BROWSER/iOS unchanged.

## 5.11.2 return to reader — 2026-10-03

- MainActivity bottom-start mint MaterialButton 뷰어로 돌아가기 displays open tab count. Refreshes ReaderTabStore onResume, hides when no selected tab. Click reloads current selected ID and launches existing ReaderActivity routing, preserving tabs/pages.
- Extended existing ReaderUiTest: leave reader, launch Main, physically click return button, assert ReaderActivity resumes and selected fixture remains; close test tabs, recreate Main and assert button GONE. Final MuMu DEV API32 test passes (1 integrated test), report artifacts/test-results-5.11.2.txt. Fixture prefs/tabs restored. Build, 3 JVM tests and lint passed.
- Delivered/published 5.11.2 (83), xyz.sabok.umtomi, 20,778,788 bytes. APK saved to artifacts and C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.11.2.apk. SHA256 af3e65c2f24232259b4adaa6bc8322b1a590a8eeedd639abe7c519898dbaf1a2; same signing certificate verified. MuMu DEV updated with -r.
- Release https://github.com/omyeol/Umtomi-releases/releases/tag/v5.11.2 ; anonymous latest digest checked. Source remains private and local/unpushed. BROWSER/iOS unchanged.

## 5.11.1 create shelf from capture — 2026-10-03

- Capture destination selector now has 새 책장 만들기. Name dialog rejects blank input, clears error on edit, calls existing BookshelfStore.addFolder (duplicate names select existing folder), immediately selects the result in preview. Cancel retains preview. Tracked dialog dismissed on Reader destruction.
- Extended existing ReaderUiTest to create the shelf through UI, reject blank name, assert destination label and verify stored PNG. Passed on MuMu DEV index 1 / 127.0.0.1:16416 (API 32). Initial keyboard-animation test timing failure resolved by waiting for keyboard close; error label also cleared on edit. Final test artifacts/test-results-5.11.1.txt: OK (1 test). 3 JVM tests, lint, build pass.
- 5.11.1 (82), 21,045,397 bytes, SHA256 ec9eec8df995140a8ff6a20d93f21ec660b2a6d2b27b6ebf420d05a8213c59e9. Saved artifacts/Umtomi-v5.11.1.apk and C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.11.1.apk. Signature matches previous version; MuMu updated with -r.
- Published https://github.com/omyeol/Umtomi-releases/releases/tag/v5.11.1 ; anonymous latest tag and digest verified, source remains private/unpushed. No BROWSER/iOS changes.

## 5.11.0 capture preview and updater state — 2026-10-03

- User supplied running MuMu DEV. Verified MuMuManager index 1 name DEV, adb 127.0.0.1:16416, Android API 32. Installed with -r; no uninstall, data wipe or other instance changes.
- UpdateActivity headline and DownloadManager notification use target version, persisted with download ID. Uses live PackageManager installed version for release selection and rechecks automatic prompt freshness. Clears completed/superseded downloads on resume, completion and install; legacy metadata migrated by reading APK version code. Automatic prompts are deduplicated per activity. Signature/package/version validation retained.
- Capture selector disables native scrollbars and overscroll; dark secondary buttons and mint handles/confirm improve contrast. Modal dialog intercepts underlying reader fast-scroller input.
- Added CapturePreviewDialog: crop image, dimensions, bottom file/save-to-shelf actions, destination shelf selector. Canceling file picker or shelf picker retains preview. Saving disables actions, failures permit retry; bitmap detached before disposal and background save owns it until completion.
- Final build, 3 JVM tests and lint (zero errors) pass. MuMu DEV instrumented tests 5/5 pass: stale legacy APK and target metadata cleared across activity recreation; reader back-to-continuous/long-press setting, zoom, scroll capture, preview, file picker cancellation, shelf selection and image storage; crop pixel and stitching tests. Report artifacts/test-results-5.11.0.txt. Synthetic screenshots visually inspected: artifacts/reader-preview/capture-result-5.11.png, capture-region-5.11.png. No real Google synchronization or future-version installer round trip tested this turn.
- Delivered 5.11.0 (81), xyz.sabok.umtomi, 21,040,912 bytes. artifacts/Umtomi-v5.11.0.apk and C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.11.0.apk, identical SHA256 8e13c0aec2db3482c37909347b79e4ac9bf57fdb7de11e364b36ef248fe2d80c. v1/v2 signature and previous certificate verified.
- Published https://github.com/omyeol/Umtomi-releases/releases/tag/v5.11.0 ; source repository remains private. Source edits remain local/uncommitted. BROWSER/iOS unchanged.

## 5.10.0 single-page exit, preference, scrolling capture, version clarity

- Fixed ReaderActivity back priority: single-page mode exits to vertical continuous layout, adapter non-single-page mode, fullscreen off, chrome visible in one action. Added default-on reader_long_press_single_page SwitchPreference; long press is ignored when off, and onResume leaves single-page mode if disabled while reader paused.
- Restored Omyaru-style '아래로 확장 · 스크롤 캡처' in MobileCaptureRegionDialog for continuous mode. ReaderCapture scrolls 75% viewport, measures actual consumed dy, waits up to 8 seconds for exposed images to decode, then CaptureStitch appends only new bottom rows. Rejects before scrolling if projected image exceeds 12 million pixels. Failed extension restores last position; dialog close cancels work and restores original position. Single-page capture remains region-only. No watermark feature.
- User confirmed TWO Umtomi icons. Explained 5.7.0 DEBUG is the still-installed old .debug package, not a stale version in the clean app. Kept old app/data untouched. UpdateActivity displays actual installed package version with explicit current/target labels, revalidates downloaded APK immediately before installer launch. New package remains xyz.sabok.umtomi.
- Delivered and published 5.10.0 (80), 20,769,260 bytes, `artifacts/Umtomi-v5.10.0.apk` and `C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.10.0.apk`. SHA-256 `006c59a53fa5d90dc41008da2b59b5dac141b54ee5ee829cd504e64d7f1d382e`. Same v1/v2 signing certificate; package/version verified. Release https://github.com/omyeol/Umtomi-releases/releases/tag/v5.10.0 ; anonymous latest API digest verified. Source still private and local changes uncommitted/unpushed.
- assembleDebug, assembleDebugAndroidTest, 3 JVM tests, lint (zero errors) passed. Extended ReaderUiTest for direct back-to-continuous, disabled long press and scroll-capture button; CaptureRegionTest validates overlap rows. These device tests remain UNEXECUTED: read-only emulator boot attempt also exits 1 before adb registration. Diagnostic `artifacts/emulator-5.10.0.log`; no connected device. User and release notes explicitly informed. Do not cite 5.8.0's 20 passing tests as verification of this release.
- Next validation on working device: direct back from immersive single-page and chrome-visible single-page; setting off/on; scrolling capture across image boundaries, end-of-gallery, decode failure, repeated extension, cancel/confirm restoring position; file and shelf PNG output; update screen from clean app. BROWSER read only; iOS unchanged.

## 5.9.0 reader region capture

- User reported Google registration complete and app connection had no errors. Updated GOOGLE_DRIVE_SETUP wording to user-reported success; no independent live Google account test performed here.
- User requested Omyaru capture subset: directly enter region selection from reader, then choose download or bookshelf after confirming. Added bottom dock capture icon, ReaderCapture controller and adapted MobileCaptureRegionDialog/View from BROWSER (read only). Corner handles, dim mask and magnifier preserved. Current visible viewport drawn into bitmap including pan/zoom, excluding title/tabs/dock; no watermark processing or scrolling extension.
- File saving uses Android CreateDocument PNG picker (can choose Downloads); bookshelf destination dialog reloads BookshelfStore and adds crop with gallery source URL. Cancellation/disposal recycles bitmaps; full-image crop copies instead of aliasing source recycled by selector dismissal. Back dismisses selector. Same package xyz.sabok.umtomi and signing certificate.
- Version 5.9.0 (79), 22,922,486 bytes. `artifacts/Umtomi-v5.9.0.apk` and `C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.9.0.apk`. SHA-256 `ed11430230fded68a372c3f48f5959d2690cb06e7ccea025a7e275a7ab2538d0`. v1/v2 signature verified. Published https://github.com/omyeol/Umtomi-releases/releases/tag/v5.9.0 ; anonymous latest asset digest verified. Source private, no source push.
- Verification: assembleDebug, 3 JVM tests, lint (zero errors), assembleDebugAndroidTest succeeded. Added CaptureRegionTest for crop-coordinate/full-source ownership and extended ReaderUiTest for capture → confirm → selected shelf file. **These device tests were NOT RUN this turn:** Android emulator 37.1.11 exits code 1 during boot before adb registration, including cold/software/acceleration-off attempts. No wipe/uninstall or host virtualization changes. User and release notes explicitly informed. No valid capture screenshot produced. No connected Android device remains.
- Next concrete validation: on a working Android device run CaptureRegionTest and ReaderUiTest, verify corner dragging/magnifier, cancel, file picker save/reopen PNG, bookshelf selected destination and current pan/zoom crop. Do not imply this turn had successful instrumentation; previous 5.8.0 tests were separate.
- iOS unchanged, BROWSER repository unmodified. Source edits remain local.

## 5.8.0 continuous reader, Drive, package change — 2026-10-03

- User requested seamless panels, shared continuous zoom, retained long-press single-page mode, no main volume pagination, optional reader volume navigation, Omyaru bookshelf/Drive integration separate from browser, history/favorite sync. Later explicitly requested removal of package .debug suffix.
- Delivered/published **xyz.sabok.umtomi**, version 5.8.0 (78). File `artifacts/Umtomi-v5.8.0.apk`, copy `C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.8.0.apk`, 20,984,845 bytes. SHA-256 `8534CE251E2E81E2DB7DAA50D59079C22FE7F0A9C95F2ACA9EB628897147A56A`. Same signing key, v1/v2 verified. Release: https://github.com/omyeol/Umtomi-releases/releases/tag/v5.8.0 ; anonymous latest API asset digest verified. Original source repository remains private; source not committed/pushed.
- IMPORTANT: New package installs alongside old xyz.sabok.umtomi.debug. No uninstall/data clearing. No automatic internal-data migration. User informed and release notes state direct APK installation required; old updater deliberately ignores new plain APK filename. Favorites JSON can be transferred. New package updater selects plain APKs using APPLICATION_ID; Build-Android and publisher support plain filename. Build configuration is still debug with same development signing certificate, despite clean package/version/file names.
- ContinuousReaderView expands RecyclerView layout width for all panels (1–4x), keeps native vertical scrolling and horizontal panning; ReaderAdapter fits subsampled originals to width even when source is smaller than screen, disables individual continuous zoom, removes margins/boundary background. Single-page adapter mode is separate from UI fullscreen/chrome state. Volume preference reader_volume_navigation defaults false; main override removed; reader clamps page movement and consumes key-up only when enabled.
- Existing BookshelfStore preserves original imports and UI. Added stable folder IDs, snapshot/apply adapters, cloud entry button and reload on return. Ported Omyaru DriveAppDataClient, DriveAccountKeys, BookshelfSyncReplica, DriveBookshelfSync and BookshelfImageChunks under sync package; all remote namespaces/key formats/crypto AAD changed to Umtomi. Browser repository was read only. Native architecture preserved.
- DriveSyncActivity uses Google Play services AuthorizationClient + account chooser, only drive.appdata scope, no token stored. Manual '지금 동기화', no periodic/background sync. Android 8+ feature gate; rest of APK retains API 21 minimum. play-services-auth 21.3.0 preserves minimum SDK compatibility. Account metadata is local, actual keys in account appData. This is account-backed encryption, not zero knowledge.
- UserDataReplica uses logical-clock per-entry records/tombstones and persisted pending batches. Sync covers favorites/folders/tags/artists and viewed gallery IDs (histories); search history, reader tabs and downloaded gallery cache are not synchronized. Shelf image originals chunked and hash-verified. Detects local changes during transfer before applying; main-thread application avoids UI races. Local pre-sync backups retained. SavedSet.replaceWith uses atomic file writes. Favorite orphan parents detached and deleted membership filtered.
- User explicitly answered OAuth has NOT been registered. Provided initial .debug values, then corrected package to **xyz.sabok.umtomi** per newest request. SHA-1 `08:D6:BB:25:59:AD:10:DE:B7:16:7E:51:72:71:BF:4C:D8:46:5F:C8`. `GOOGLE_DRIVE_SETUP.md` has steps and limitations. **Real Google OAuth/Drive round trip remains UNVERIFIED until user registers the Android client and logs in.** No credentials requested in chat, no real user data uploaded.
- Validation: assembleDebug + assembleDebugAndroidTest, 3 JVM tests, lint zero errors, **20 emulator tests passed** in `artifacts/test-results-5.8.0.txt`. Reader test waits for actual cached image decoding, injects multi-touch, verifies expanded list width and rendered-image scale, gestures/navigation. Drive tests cover namespace isolation, authenticated encryption tamper rejection, chunk roundtrip/hash rejection, merge/deletions/replay/reload, favorite folder restoration, stable shelf rename IDs, connection screen/recreation without login. Actual zoom and Drive UI viewed via synthetic screenshots under artifacts/reader-preview. No live Drive transport test was performed.
- Emulator-5554 left running; both old .debug and new clean package installed without clearing data. BROWSER devices were not touched. iOS unchanged.

## 5.7.0 Android reader design — 2026-10-02

- Native reader structure retained. Added dark ReaderTheme, MaterialToolbar, rounded Material Chip tabs, mint accent, bottom previous/next controls and page slider, and a BottomSheetDialog for existing reader actions. Hidden legacy FAB actions delegate existing behavior. System-bar insets and dark icon appearance use AndroidX compatibility APIs.
- Single tap toggles toolbar/tabs/dock; long press keeps the one-page behavior. Fixed first tap after entering fullscreen to restore chrome immediately. Independent tab page state retained.
- Version 5.7.0 (77), delivered to artifacts and `C:\Users\peopl\OneDrive\DEV\APK`. SHA-256: `4ECE1038FD12B2DDFC71EBC79FB4AA6DA63CF8231A58A00F3CE36188A4B689F2`. APK 22,394,926 bytes, same v1/v2 signing certificate.
- assembleDebug, lintDebug (zero errors), 3 JVM tests and 14 Android instrumentation tests passed. New ReaderUiTest verifies page controls, chrome toggling and fullscreen restoration with synthetic local images. Report: `artifacts/test-results-5.7.0.txt`. Portrait, landscape and tools menu visually checked; synthetic previews at `artifacts/reader-preview/final.png`, `landscape.png`, `tools-final.png`. Fixtures are emulator cache/test data, not production assets.
- Published APK-only release: https://github.com/omyeol/Umtomi-releases/releases/tag/v5.7.0 . Anonymous latest API and uploaded asset digest verified. Source repository remains private. Release notes: `artifacts/RELEASE-v5.7.0.md`. Source changes remain local; iOS unchanged this turn.

## 5.6.1 reader tab follow-up — 2026-09-28

- A nonexistent gallery tab previously set ReaderActivity's polling flag false and permanently stopped download state updates for later tabs. Now it shows a one-time warning per ID and continues polling; retry clears the warning state.
- Version 5.6.1 (76) built to `artifacts/Umtomi-v5.6.1-debug.apk` and copied to user APK folder, matching hashes and v1/v2 signature. Lint, 3 JVM tests and 13 API-32 emulator tests pass. The two-tab and gesture UI was manually verified on 5.6.0; 5.6.1 changes only the missing-gallery listener, with successful emulator install and tests.

## 5.6.0 Android reader tabs — 2026-09-28

- User requested iOS-like multiple reader tabs on Android and new gestures. Added `ReaderTabStore.kt` SharedPreferences persistence (tab gallery IDs, selected ID, independent page), horizontal reader tab strip with select/close/new ID, `singleTask` ReaderActivity routing via onNewIntent, and long-press on GalleryDialog FAB to open background tab. Existing open entry points launch ReaderActivity and are redirected to the same workspace.
- ReaderAdapter image/root taps now toggle title/tab/FAB visibility; long press performs the old tap behavior (enter full-screen one-page mode, then advance one page per long press). Full-screen hides tabs; back returns chrome. Tabs are retained after leaving the reader and across app restart.
- Fixed fresh-install SettingsFragment download-folder summary crash on emulator API 32 when preference is blank. This issue surfaced while running instrumentation and was unrelated to tabs but affected the required test gate.
- Version 5.6.0 (75), `artifacts/Umtomi-v5.6.0-debug.apk` copied to `C:\Users\peopl\OneDrive\DEV\APK`. Build script verifies copied SHA-256. Lint and 3 JVM tests pass. 13 emulator tests pass (Bookshelf, Favorites/Theme, ReaderTabs). Manually verified two-tab switching/closing, one-tap hide/show, long-press one-page mode on API 32. Release notes at `artifacts/RELEASE-v5.6.0.md`.

## 5.5.1 public APK release — 2026-09-16

- User selected option 1: a public APK-only repository, leaving source private. Created `https://github.com/omyeol/Umtomi-releases`; published `https://github.com/omyeol/Umtomi-releases/releases/tag/v5.5.1` with APK and SHA256SUMS.txt. Added installation/update README. Verified original `omyeol/Umtomi` still private. No source push or source upload occurred.
- Version 5.5.1 (74), package `xyz.sabok.umtomi.debug`, same signing certificate as prior APKs. Updated API endpoint, asset URL allowlist, release browser URL and unit-test fixtures. Files delivered to both artifacts and user APK directory.
- SHA-256: `0bfce0de49b18790112e2a03512b0d95fcd7d480dd6264a87537f2f29c1c1c40` (20,478,043 bytes). APK signature verified; installed over previous emulator app successfully. Unit tests and lint passed. Anonymous GitHub API and complete APK download/hash verified at final v5.5.1 URL. Actual app update screen correctly says no newer APK on 5.5.1. A future-version upgrade through the system installer was not simulated.
- IMPORTANT: 5.5.0 and earlier need this APK installed manually once; their update URL pointed at the private repository. Future updates can use the new channel.
- `umtomi/Publish-AndroidRelease.py` uses Git credential manager in memory, fixed public APK repo, draft upload with checksum then optional `--publish`. Refuses overwriting published releases. GitHub initially substituted an untagged tag when PATCH omitted tag_name; repaired published tag to v5.5.1 and fixed script to always include tag_name. Do not publish unless requested; APK local delivery remains automatic on each build.
- The previous pending distribution question is RESOLVED by user option 1. Older notes below record the prior state.

## 5.5.0 Android update — 2026-09-16

- Delivered `artifacts/Umtomi-v5.5.0-debug.apk` and `C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.5.0-debug.apk`, versionCode 73, package `xyz.sabok.umtomi.debug`. SHA-256: `C99902A2F30E93B6FF62558898A3885851E9883D48039B48538B13CB5D36411F`.
- Replaced overlooked raster drawer banner with themed Umtomi icon/name/current repository header. Deleted `side_nav_bar.png` and unused old vector logos. Removed homepage/mail/Discord menu entries and translations. Help points to current repository issues.
- Added FavoritesActivity and FavoriteFolders.kt: nested manual folders, assigning works to multiple folders, rename/delete without deleting global favorites, JSON export/merge import via Android document picker. iOS UUID casing and folder/artist fields supported. Auto-rule strings are preserved, but Android does not perform iOS automatic classification.
- Theme fix: persist preference before setDefaultNightMode, keep restored SettingsFragment/subpages, load translation languages in lifecycle scope and mutate UI on main thread, cancel destroyed MainActivity jobs, remove UI-thread runBlocking. BaseActivity now overrides normal onCreate and only requests lock if configured. Main search/mode/page state survives recreation.
- User additionally requested in-app updates and GitHub Releases. Added UpdateActivity: progress, resume monitoring, retry/cancel/install; strict newer numeric version selection; filters APK build type; validates package/versionCode/signing certificate; Android install permission flow. Settings app-version preference opens it. GitHub network calls are bounded and automatic prompts follow lifecycle.
- IMPORTANT PENDING: authenticated read found `omyeol/Umtomi` is PRIVATE, with no releases. An async question is pending: create public APK-only `Umtomi-releases`, make current repo public, or keep private and download through GitHub login. Do not change visibility or publish publicly without the user's answer. Current updater endpoint remains original private repository, so anonymous release downloads are not yet operational. No token is included in APK. No push or release publication occurred. Once answered, configure the updater endpoint and URL allowlist accordingly, rebuild/redeliver, then upload release and test download/install. `artifacts/RELEASE-v5.5.0.md` contains draft notes.
- Existing Git credential manager has authenticated repository admin/push access. `gh` is absent. Python subprocess `git credential fill` + urllib GitHub API worked with credentials held only in memory; never print credentials. Use `GIT_TERMINAL_PROMPT=0`, `GCM_INTERACTIVE=never` for read probes.
- Verification: assembleDebug, lintDebug (0 errors), 3 JVM tests, 12 Android 14 instrumentation tests passed (BookshelfTest + FavoritesAndThemeTest, including six alternating theme changes and update-screen recreation). APK signature v1/v2 verified, installed on emulator. Actual drawer text and JSON SAF export manually verified. Release download/install end-to-end awaits distribution choice.
- Instrumentation via direct `adb shell am instrument -w -r -e class xyz.sabok.umtomi.FavoritesAndThemeTest,xyz.sabok.umtomi.BookshelfTest xyz.sabok.umtomi.debug.test/androidx.test.runner.AndroidJUnitRunner`; Gradle UTP listener previously failed infrastructure startup. Report: `artifacts/test-results-5.5.0.txt`.
- Java: `C:/Program Files/Microsoft/jdk-21.0.10.7-hotspot`. Gradle needs sandbox escalation for cache writes. Headless AVD `U_Pixel_2_` also requires escalation for `.android/avd` writes; use hidden window. Emulator 5554 was running at end of verification.

## 5.4.1 Android update

- Android version is now 5.4.1 (72).
- User-specified copyright display: `Omyeol`, source `https://github.com/omyeol/Umtomi`. The previous `THIRD_PARTY_NOTICES.md` summary was removed and replaced by `COPYRIGHT.md`. Android Settings displays the new attribution and opens that repository.
- APK filenames are derived from Gradle output metadata. Every build is copied and hash-verified in `C:\Users\peopl\OneDrive\DEV\APK`.
- Android Studio's old bundled Java path is no longer complete on this PC. Use the installed Microsoft JDK 21; the build script now falls back to Java on PATH.

## APK delivery preference

- Every newly built APK must be copied to `C:\Users\peopl\OneDrive\DEV\APK` and verified by SHA-256 against the build output. The user explicitly requested this location on 2026-09-16.
- `umtomi/Build-Android.ps1` performs this delivery automatically. Apply the same rule when building APKs with Gradle directly. Keep the local build artifact as well.

## 2026-09 Windows continuation

- Repository cloned to `C:\Users\peopl\OneDrive\DEV\CODEX\Umtomi`.
- Renamed the former project folder to `umtomi`; preserved the Android/SwiftUI platform architecture.
- Android namespace/application ID is `xyz.sabok.umtomi` (`.debug` suffix for the delivered development build), version 5.4.0 (71). App class, packages, UI names, icon, and update channel use Umtomi. Copyright notices are retained. Old iOS migration keys were removed on 2026-09-16.
- Removed reliance on the upstream Firebase account. Exceptions are logged locally. Tag dictionaries are bundled with their own license notices, avoiding the upstream app's server references at runtime.
- Android bookshelf: `umtomi/app/src/main/java/xyz/sabok/umtomi/ui/BookshelfActivity.kt` and `BookshelfStore.kt`, adapted from BROWSER. Entry is the bookshelf icon in the main search toolbar.
- Features: folders, per-folder 2–6 masonry columns, notes, multiple original image file imports, clipboard URI imports, full screen, confirmed image/folder deletion, portrait/landscape PDF export. Image archives retain original bytes and EXIF orientation is respected. Catalog writes are atomic; corrupt catalogs are preserved.
- iOS bookshelf: `umtomi/ios/Umtomi/Bookshelf.swift`, added to `UmtomiTab`. Default six-tab layout puts the shelf under More; tab ordering remains customizable. Native SwiftUI/ImageIO/UIKit implementation with the same core local shelf workflow.
- No desktop catalog import, storage relocation UI or cross-device bookshelf sync is included.
- Build on Windows using `umtomi/Build-Android.ps1 -Test`. Gradle output is outside OneDrive to avoid file-lock errors. Current verified output: `artifacts/Umtomi-v5.4.0-debug.apk`.
- Validation: APK assembled; JVM unit test passed; six Android 14 emulator tests passed (folders/notes/columns/reopen/delete, original import preservation, validation, corrupt catalog preservation, activity recreation, multipage PDF orientation/rendering); Lint has zero errors (existing warnings remain). APK v1/v2 signing verified; APK installed and main-toolbar bookshelf entry manually exercised in Korean.
- iOS was not compiled or run on Windows. Run `umtomi/ios/build-ipa.sh` on macOS; it regenerates the Xcode project and includes the new source file. The checked-in generated Xcode project was deliberately not manually edited.
- Changes are local; no GitHub push or release publication was performed.

The older notes below describe the existing reader features and their fragile areas.

## Project Snapshot

This workspace contains the **Umtomi** Android and iOS reader apps.

Main app path:

- `umtomi/ios/Umtomi`
- XcodeGen spec: `umtomi/ios/project.yml`
- Build script: `umtomi/ios/build-ipa.sh`

Current app identity:

- Product name: `Umtomi`
- Bundle ID: `xyz.sabok.umtomi`
- Deployment target: iOS 17.0
- Swift version: 5.9
- Xcode project is generated from `project.yml`; do not manually edit generated `.xcodeproj` unless absolutely necessary.

## Build And IPA

Typical unsigned IPA build:

```bash
cd /Users/omyeolmacair/DEV/Umtomi/umtomi/ios
./build-ipa.sh
```

Output:

- Local IPA: `umtomi/ios/build/Umtomi-unsigned.ipa`
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
-module-cache-path umtomi/ios/build/SwiftModuleCache \
-sdk /Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS26.4.sdk \
-target arm64-apple-ios17.0 \
umtomi/ios/Umtomi/Models.swift \
umtomi/ios/Umtomi/AppStore.swift \
umtomi/ios/Umtomi/DownloadLibrary.swift \
umtomi/ios/Umtomi/FavoritesBackupDocument.swift \
umtomi/ios/Umtomi/HitomiService.swift \
umtomi/ios/Umtomi/UmtomiApp.swift \
umtomi/ios/Umtomi/Views.swift
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
- Only Umtomi storage keys and directories are used. Old-name automatic migration was removed at the user’s explicit request on 2026-09-16. Existing Umtomi data is unchanged; unmigrated older data is no longer imported automatically.

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
cd /Users/omyeolmacair/DEV/Umtomi/umtomi/ios
xcodegen generate --spec project.yml
```

Build IPA:

```bash
cd /Users/omyeolmacair/DEV/Umtomi/umtomi/ios
./build-ipa.sh
```

Check IPA executable:

```bash
unzip -l /Users/omyeolmacair/DEV/Umtomi/umtomi/ios/build/Umtomi-unsigned.ipa | rg "Payload/Umtomi.app/Umtomi$"
```

Find newest iCloud Umtomi IPA:

```bash
zsh -lc 'print -rl -- "$HOME/Library/Mobile Documents/com~apple~CloudDocs/IPA"/Umtomi-unsigned-*.ipa | tail -1'
```

Search useful spots:

```bash
rg -n "readerTabs|retainedImageCache|Open in Background Tab|FavoriteFolder|FavoriteArtist|resumePrefetch" umtomi/ios/Umtomi
```

## 2026-10-11: One-column bookshelves and image numbering

Changed Umtomi Android 5.14.2/code90 in `umtomi/app`: minimum shelf columns 1 across local/shared/sync models; new and existing original images use numbered `imageN.ext` names based on saved item order. The private and shared migrations copy, compare SHA-256, atomically update catalog paths, then remove only verified old originals. Incomplete originals fail closed. Shared-catalog merge no longer sorts images by ID. Unit tests, `Build-Android.ps1 -Test` including lint, and six on-device bookshelf instrumentation tests passed. Data-preserving install on MuMu DEV `127.0.0.1:16416`; synthetic cross-app Umtomi → Omyaru → Umtomi shared shelf test passed all three stages after enabling all-files permission on that emulator. No other emulator touched. Actual user's phone bookshelf migration/Drive sync not yet observed.

APK relative `artifacts/Umtomi-v5.14.2.apk`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.14.2.apk`, delivered copy `C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.14.2.apk`. Published public APK-only release `https://github.com/omyeol/Umtomi-releases/releases/tag/v5.14.2`; anonymous API size/SHA-256 matched local (`17d3dcfe16f93e5de5298d25927a23db09c4da0770ebc11ec7a62cdcc6c5e4c5`). Private source repo unchanged.

The separate iOS bookshelf in `umtomi/ios/Umtomi/Bookshelf.swift` now permits one column, names new imports `imageN.ext`, and migrates existing complete UUID-named catalogs by verified copy plus atomic catalog write. iOS build/IPA validation is pending because this Windows host has no Xcode or Swift compiler; do not claim an iOS binary exists yet. See `../BROWSER/docs/AI_HANDOFF.md` for PC and Omyaru changes. The incomplete older standalone PC catalog was intentionally not migrated.


## 2026-10-11: Cloud-only bookshelf groundwork (not released)

User approved on-demand Drive originals plus a bounded thumbnail cache for Android and PC first; iOS is deferred. Android Umtomi source now includes `umtomi/app/src/main/java/xyz/sabok/umtomi/ui/CloudShelfMarker.kt` and marker-aware shared sync/rendering groundwork. `sync/shared/DriveBookshelfSync.kt` currently defaults `cloudOnly=false`: the public shared shelf still keeps full local originals. `sync/shared/SharedShelfSync.kt` does not retire any shared originals. Legacy private sync is unchanged. This is deliberate pending a real-account cross-OAuth visibility test because Umtomi and Omyaru have separate OAuth projects and Google `appDataFolder` may be app-scoped. Do not enable cloud-only mode or delete shared originals on this evidence alone.

Both Android compileDebugKotlin builds passed. The targeted MuMu DEV-only test used Browser's connected account to write a temporary reference to an existing Drive key ID in a synthetic public folder; Umtomi reported `not_connected`. The synthetic folder was cleaned. User has been asked to connect Umtomi to the same Google account on MuMu DEV; response pending. No production APK built or installed in this checkpoint. Do not touch the other emulator, uninstall apps, or clear data. See `../BROWSER/docs/AI_HANDOFF.md` latest checkpoint for PC v0.4.51 / standalone v0.1.25 builds and full 49-image read-only Drive check. Next: rerun cross-OAuth test once Umtomi is connected, then design/verify shared cloud references, offline option, cross-app image load, and release APKs. iOS later.
MuMu DEV cross-OAuth check was retried after PC publishing: Umtomi still reported not_connected. This does not prove cross-app Drive access; the exact synthetic validation folder was removed.

## 2026-10-11: Cropped bookshelf image follow-up

User reported cloud images not visible and images cropped; exact app was asked, response pending. Umtomi Android `BookshelfActivity.kt` gallery image scaleType was changed from CENTER_CROP to FIT_CENTER, preserving the whole image even when saved aspect ratio differs. `compileDebugKotlin` passed. No Umtomi release APK was built or installed: shared cloud-only access remains unverified because MuMu DEV Umtomi account was not connected at last check, and shared originals remain protected. See BROWSER latest handoff for Windows v0.4.52 / standalone v0.1.26 builds and unresolved missing-cloud diagnosis.

## 2026-10-12: Shared bookshelf transfer percentage

User requested mobile bookshelf sync upload/download percentage in both apps. `umtomi/app/src/main/java/xyz/sabok/umtomi/sync/shared/DriveBookshelfSync.kt` now emits completed/total percentages after each upload/download original; `ui/BookshelfActivity.kt` shows the current phase, percentage, count and horizontal progress bar while the shared sync runs, then hides it. Version bumped to 5.14.3/code91 in `umtomi/app/build.gradle.kts`. The paired Omyaru Android 0.15.3 implementation is recorded in `../BROWSER/docs/AI_HANDOFF.md`. Existing shared-cloud-only restriction and separate OAuth-project uncertainty remain unchanged.

`compileDebugKotlin`, `testDebugUnitTest`, and APK build passed. Existing output directory had Windows/OneDrive access conflicts during a parallel `-Test` run; compile was independently verified with the isolated Gradle output helper and unit tests rerun successfully. Relative APK `artifacts/Umtomi-v5.14.3.apk`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.14.3.apk`, delivered `C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.14.3.apk`; SHA-256 `8b28a806befcc8153b518cb624e235ee4904ed69e2a9d7692149433a89403548`, signing certificate matches v5.14.2. Published public APK-only release `https://github.com/omyeol/Umtomi-releases/releases/tag/v5.14.3`; anonymous API size/digest match. MuMu DEV only (`127.0.0.1:16416`) was updated with `adb install -r` and reports code91/name5.14.3; no uninstall/data clear. Other emulator untouched. Umtomi's non-exported BookshelfActivity could not be directly started by shell, so actual signed-in bookshelf progress appearance remains to be verified in the app. No iOS work this turn, no private source push.

## 2026-10-12: Mobile bookshelf status and download progress follow-up

User reports `Omyaru Browser/이세계 밀프헌터` absent in BOTH mobile apps. Browser Windows authenticated read-only Drive projection has the folder and all 316 images (365 total images, 196 records), so PC upload is complete. The phone's actual account/sync state is pending user reply; Umtomi uses a separate OAuth project and must not be assumed to see Browser appData directly. Shared on-device bookshelf requires its storage configuration and access; do not delete originals or force empty sync to diagnose.

Umtomi Android 5.14.4/code92 retains the shared shelf upload/download percentage, briefly shows completion or waiting state in `BookshelfActivity.kt`, and `CloudShelfMarker.kt` now marks API-26-only operations correctly while guarding decode on older Android. `Build-Android.ps1 -Test` passed assembleDebug, JVM tests, and lintDebug after fixing 18 API-level lint errors in CloudShelfMarker. APK relative `artifacts/Umtomi-v5.14.4.apk`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.14.4.apk`, copy `C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.14.4.apk`; SHA-256 `428552e31a0339f30c83bc6457353e3b8b7ca85c95dfb8f2987a16b04646c991`. Signing cert matches v5.14.3. Public APK-only release https://github.com/omyeol/Umtomi-releases/releases/tag/v5.14.4, source repo remains private. MuMu DEV `127.0.0.1:16416` updated data-preservingly, reports code92; other emulator untouched. Real phone bookshelf receipt not verified. Omyaru Android v0.15.4 was paired and released; see `../BROWSER/docs/AI_HANDOFF.md`. Next: get each app's sync/account status from the user and diagnose Browser download first, then Umtomi shared-local visibility/cross-project limitation.
Anonymous GitHub API verification: v5.14.4 asset published and size/SHA-256 digest match local APK; paired Omyaru v0.15.4 also matched. No source push or PR.

## 2026-10-12: finish ongoing shared-shelf sync after app switch

User requested the current mobile bookshelf transfer continue when switching to another app. Umtomi Android previously set `shelfActive=false`, cancelled `sharedSyncJob` in `onPause`, and its validity callback rejected all background work. `ui/BookshelfActivity.kt` now stops scheduling new cycles on pause but does not cancel the in-progress job; validity checks account, enabled state, import/destruction, and resumes normal periodic checks on return. Activity destruction/process kill can still interrupt; no Android foreground service was added. Omyaru Android already continued its current `shelfSyncWorker` when merely paused, so no background change needed there. Both apps now use local bookshelf originals: Umtomi shared sync already had `cloudOnly=false`, Omyaru Android v0.15.5 switches its sync to that mode; Windows v0.4.58/standalone v0.1.32 download markers to local PNGs. See `../BROWSER/docs/AI_HANDOFF.md`.

Umtomi v5.14.5/code93 `Build-Android.ps1 -Test` passed APK, unit tests and lintDebug. APK relative `artifacts/Umtomi-v5.14.5.apk`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.14.5.apk`, delivered `C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.14.5.apk`; SHA256 `35c76a92e22ff46ba35d2e47b06220add7ddb19733e0ac00102f0ef273772f2c`, same prior signing cert. Public APK release https://github.com/omyeol/Umtomi-releases/releases/tag/v5.14.5 published and anonymous size/digest verified. Data-preserving install on MuMu DEV `127.0.0.1:16416` reports code93/name5.14.5; other emulator untouched. Real-account/phone background transfer was not exercised, and actual phone folder visibility still requires sync/account status. No source push or PR.

## 2026-10-12: Android gallery visibility question
Read-only review: both Android SharedBookshelfStorage implementations create .nomedia at the fixed Omyaru_Bookshelf shared root on activation and volume migration. This preserves local cross-app sharing while suppressing normal media scanning. Android/data/<package> is isolated from other apps on Android 11+ and would break direct sharing. No code changes/build/device actions. If actual Gallery entries persist, diagnose existing media index/device behavior without moving or deleting originals.


## 2026-10-12: Show shared shelf folders before download completes
Paired with Browser Android 0.15.6, Umtomi shared DriveBookshelfSync now publishes projected folders/image markers through metadataReady before downloading full originals. SharedShelfSync applies the intermediate state on the main thread with fresh catalog/account guards, then applies verified local original paths at completion; existing originals and image identities remain intact. The legacy non-shared UmtomiDriveSync path was left unchanged because it uses a separate sync implementation. Version 5.14.6/code94. Compile/debug unit tests and Build-Android.ps1 -Test (assembleDebug, unit, lintDebug) passed. Relative APK artifacts/Umtomi-v5.14.6.apk, absolute C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.14.6.apk, delivered C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.14.6.apk; SHA256 175faff6445fdae0fb84ed0cdad1315cc624ac7d500ee6b64b7bdb334fb43199, matching prior signing cert. Public APK-only release https://github.com/omyeol/Umtomi-releases/releases/tag/v5.14.6 published; anonymous asset size/hash match. MuMu DEV 127.0.0.1:16416 installed data-preservingly, code94/name5.14.6; emulator-5556 untouched. Real account slow-download flow remains unverified; separate OAuth projects still constrain cross-app remote visibility. No source push or PR.

## 2026-10-12: Parent bookshelf gallery and child folder order

Umtomi Android bookshelf now opens parent shelves and renders descendant images consecutively, with a one-column parent gallery to keep each child shelf together. Folder actions move a shelf up/down among siblings, and newly created shelves append below existing siblings. `BookshelfPath`, `BookshelfStore`, shared catalog merge and shared Drive sync replica persist folder `order` metadata with fallback for old records. No original images, filenames or per-shelf image order were changed. Paired Omyaru Android, Windows browser and standalone bookshelf implementations are recorded in `../BROWSER/docs/AI_HANDOFF.md`.

Version 5.14.7/code95. `Build-Android.ps1 -Test` passed assembleDebug, JVM tests, lintDebug and release APK; new folder order tests passed. Relative artifact `artifacts/Umtomi-v5.14.7.apk`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.14.7.apk`, delivered `C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.14.7.apk`. SHA-256 `3cf3349f039c2f923194de47174cb7f15de02228348d055541f743d7e738d688`; signing certificate matches 5.14.6. Published public APK-only release https://github.com/omyeol/Umtomi-releases/releases/tag/v5.14.7 ; anonymous release size and digest match. MuMu DEV `127.0.0.1:16416` data-preserving install reports code95/name5.14.7; other emulator untouched. Historical folder order is existing catalog order, and can be adjusted manually. Actual cross-account Drive visual order remains to be verified; update both apps to understand the new folder order field. No private source push or iOS changes.
## 2026-10-12: Per-app local/on-demand bookshelf image setting

User clarified that local image storage must continue Google Drive synchronization. `ui/BookshelfStorageActivity.kt` now selects local (default) or on-demand per Umtomi installation. `sync/shared/SharedShelfSync.kt` supplies selected mode to `DriveBookshelfSync.kt`; local mode retrieves missing originals, on-demand creates cloud references for new incoming originals, and neither mode deletes existing local originals. Existing verified originals are retained and reused; no re-upload required when changing the mode. Paired Omyaru Android, Windows desktop and standalone implementation is in `../BROWSER/docs/AI_HANDOFF.md`.

Final version 5.14.9/code97: initial 5.14.8 release was published before an Android choice-dialog layout correction. `Publish-AndroidRelease.py` correctly refuses replacement of an already published asset, so correction was issued as new 5.14.9 instead of mutating the prior public release. Isolated Gradle `assembleDebug`, unit tests and `lintDebug` passed. Relative artifact `artifacts/Umtomi-v5.14.9.apk`, absolute `C:/Users/peopl/OneDrive/DEV/CODEX/Umtomi/artifacts/Umtomi-v5.14.9.apk`, delivered `C:/Users/peopl/OneDrive/DEV/APK/Umtomi-v5.14.9.apk`. Final SHA-256 `b941367803112e64c781effd157c60085f68159dac9004be45d7e1186fa21521`; signing cert matches 5.14.7. Published public APK-only release https://github.com/omyeol/Umtomi-releases/releases/tag/v5.14.9 ; public size/hash verified. MuMu DEV `127.0.0.1:16416` data-preserving install reports 5.14.9/code97; other emulator untouched. Real-account visual mode switch remains unverified; no iOS or private source push.
