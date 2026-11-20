# Umtomi 인수인계

Codex와 Antigravity에서 같은 폴더를 열어 순서대로 작업한다.

`C:\Users\peopl\OneDrive\DEV\CODEX\Umtomi`

새 세션에서는 **“`AGENTS.md`와 `HANDOFF_KO.md`를 읽고 지금 작업 폴더에서 이어서 작업해줘.”**라고 시작하면 된다. 작업을 넘기기 전에 저장되지 않은 편집을 저장하고, 아래 작업 상태와 검증 결과를 갱신한다.

## 현재 상태

- **저장소 공개 전환 및 최신 코드 푸시 완료 (`omyeol/Umtomi`).** GitHub 저장소 `omyeol/Umtomi`를 `public`으로 전환했고(`private: false`), 최신 소스를 커밋 `d840760` 및 `Publish-iOSRelease.py`가 갱신한 `livecontainer.json` 커밋 `042092f`까지 로컬 `main`(`origin/main`)에 반영했다(로컬 미추적 파일 `release-notes-5.14.11.md` 제외).
- **Android 5.15.5 (빌드 105) 공개 릴리스 완료.**
  - 변경 사항: 즐겨찾기 정렬(추가한 순/업로드순/이름순 + 오름/내림차순, `PortFavoritesView.kt`, `sortFavoriteGalleryIDs`), 하단 탭 바 순서에 `설정` 항목 추가(`WorkspaceTab.SETTINGS`, `WorkspaceNavigation.kt`, `MainActivity.kt`, `ClassicBrowseActivity.kt`), 검색 화면 당겨서 새로고침(`SwipeRefreshLayout`, `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0`), 즐겨찾기 폴더 자동 분류 캐시 영속화 및 하위 폴더 자동 갱신(`0 galleries` 초기 표시 버그 수정), 즐겨찾기 `Artists`에서 작가 탭 시 일반 검색창과 유사한 검색 오버레이(`showSearchOverlay`) 표시 반영 및 `umtomi/app/build.gradle.kts` `5.15.5` (`versionCode = 105`) 상향.
  - 검증: `.\umtomi\Build-Android.ps1 -Test` 통과 (`assembleDebug`, `testDebugUnitTest` 7개 스위트 22건 실패/오류/스킵 0, `lintDebug` 통과). (MuMu 에뮬레이터가 미연결 상태라 실기기/에뮬레이터 UI 검증은 미실행.)
  - 산출물 및 릴리스: `artifacts/Umtomi-v5.15.5.apk` 및 `C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.15.5.apk` (21,168,503 bytes, SHA-256 `71DBDBFB89A8D4E00D287BC89BC8C1A86532AC0D6385957412EC2DA96BEE3106`), 릴리스 `https://github.com/omyeol/Umtomi/releases/tag/v5.15.5` (`latest`, 비인증 공개 API 검증 완료).
- **iOS 5.4.8 (빌드 10) Actions 빌드 및 공개 릴리스 완료.**
  - 변경 사항: `Models.swift`, `AppStore.swift`, `Views.swift`, `TabBarSettingsTests.swift`, `Localizable.strings`(ko/ja)에 즐겨찾기 정렬(`FavoriteSortOrder` + `favoriteSortAscending`), 탭 바 순서 `Settings` 항목(`UmtomiTab.settings`), 검색 `.refreshable`, 즐겨찾기 폴더 자동 분류 캐시 영속화/초기화 보존(`0 galleries` 수정), 작가 검색 오버레이(`ArtistSearchOverlayView`)를 반영하고 `umtomi/ios/project.yml`을 `5.4.8` / `10`으로 올렸다. (직전 `5.4.7`/`9`에는 생체인증 잠금 해제 지연 수정, `5.4.6`/`8`에는 OLED 순수 블랙 적용.)
  - 검증 및 산출물: GitHub Actions 워크플로([#44757816227](https://github.com/omyeol/Umtomi/actions/runs/44757816227)) macOS 무서명 IPA 빌드/체크섬 통과, `artifacts/Umtomi-iOS-5.4.8.ipa` (2,024,757 bytes, SHA-256 `6f1711d669967704850eb1cf23615ee4254af52ab148190996dd93cba9f57a51`, `Info.plist`: `xyz.sabok.umtomi` / `5.4.8` / `10`), 릴리스 `https://github.com/omyeol/Umtomi/releases/tag/ios-v5.4.8`, LiveContainer 피드 `https://raw.githubusercontent.com/omyeol/Umtomi/main/livecontainer.json` 공개 검증 완료.
- **다음 작업.** 실기기에서 iOS `5.4.8`·Android `5.15.5`의 신규 UI(즐겨찾기 정렬/폴더 숫자 유지/작가 오버레이/당겨서 새로고침/`Settings` 탭 순서), iOS 생체인증 속도·OLED 다크모드, Android 실기기 생체 인증·Google Drive 실계정 동기화·인앱 업데이터를 최종 확인한다.

## Android 빌드와 확인

저장소 루트에서 실행한다. 빌드 스크립트는 OneDrive 바깥에 중간 파일을 만들고, 프로젝트 `artifacts`와 사용자 APK 폴더에 결과를 복사한 뒤 해시를 비교한다.

```powershell
git status --short --branch
& .\umtomi\Build-Android.ps1 -Test
adb devices -l
```

- MuMu DEV가 실행 중이면 기기 주소 `127.0.0.1:16416`을 확인하고, 설치와 테스트 명령에서 `adb -s 127.0.0.1:16416`으로 기기를 지정한다.
- 기존 설치에 업데이트할 때는 패키지 `xyz.sabok.umtomi`와 이전 APK 서명을 확인한다. 최신 정식 빌드는 `C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.15.5.apk`에 있다.
- 책장 저장소나 동기화 동작을 수정하기 전에 [저장소 계약](BOOKSHELF_STORAGE_CONTRACT.md)과 [Omyaru Browser 인수인계](../BROWSER/docs/AI_HANDOFF.md)를 확인한다.
- iOS 빌드 경로는 `.github/workflows/ios-ipa.yml`에 있다. 이전 작업, 릴리스 식별자, 해시는 [상세 작업 이력](CODEX_HANDOFF.md)에 기록되어 있다.

## 다음 전환 때 갱신할 항목

| 항목 | 기록할 내용 |
| --- | --- |
| 변경 | 버전, 수정한 경로, 핵심 동작 |
| 검증 | 실행한 검사, 사용 기기, 확인한 동작 |
| 산출물 | APK 또는 IPA 경로, 배포 여부 |
| 다음 작업 | 미완료 항목, 재현 조건, 관련 파일 |
