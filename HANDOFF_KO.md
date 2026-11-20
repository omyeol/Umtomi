# Umtomi 인수인계

Codex와 Antigravity에서 같은 폴더를 열어 순서대로 작업한다.

`C:\Users\peopl\OneDrive\DEV\CODEX\Umtomi`

새 세션에서는 **“`AGENTS.md`와 `HANDOFF_KO.md`를 읽고 지금 작업 폴더에서 이어서 작업해줘.”**라고 시작하면 된다. 작업을 넘기기 전에 저장되지 않은 편집을 저장하고, 아래 작업 상태와 검증 결과를 갱신한다.

## 현재 상태

- **저장소 공개 전환 및 최신 코드 푸시 완료 (`omyeol/Umtomi`).** GitHub 저장소 `omyeol/Umtomi`를 `public`으로 전환했고(`private: false`), 최신 소스를 커밋 `49b8b64`로 푸시했다. `Publish-iOSRelease.py`가 갱신한 `livecontainer.json` 커밋 `72d879b`까지 로컬 `main`(`origin/main`)에 반영되어 있으며, 로컬 작업 트리에서 추적 파일 변경은 없다(로컬 미추적 파일 `release-notes-5.14.11.md` 제외).
- **Android 5.15.4 (빌드 104) 공개 릴리스 완료.**
  - 변경 사항: OLED 다크모드(`#000000`), 드래그/미리보기 하단 탭 바 편집기(`PortTabBarEditorActivity`), iOS 기준 즐겨찾기/작품상세/추천/뷰어 이어보기 프리로드 포팅(`5.15.3`)에 더해, `UpdateActivity.kt`·`util/update.kt`·`umtomi/Publish-AndroidRelease.py`·`Publish-iOSRelease.py`의 배포/업데이트 대상을 `omyeol/Umtomi`로 전환하고 `umtomi/app/build.gradle.kts`를 `5.15.4` (`versionCode = 104`)로 올렸다.
  - 검증: `.\umtomi\Build-Android.ps1 -Test` 통과 (`assembleDebug`, `testDebugUnitTest` 7개 스위트 21건 실패/스킵 0, `lintDebug` 통과). UI/OLED 픽셀(`RGB(0,0,0)`) 실기/에뮬레이터 검증은 `5.15.3` 빌드에서 MuMu DEV(`127.0.0.1:16416`)로 완료(`5.15.4` 시점에는 MuMu 미연결 상태라 업데이터 URL 변경분만 반영).
  - 산출물 및 릴리스: `artifacts/Umtomi-v5.15.4.apk` 및 `C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.15.4.apk` (21,142,189 bytes, SHA-256 `7FEE0D021C0E230CBCAD93D524DD50402E0F16A1A06E9B4E8F41623D768B84B2`), 릴리스 `https://github.com/omyeol/Umtomi/releases/tag/v5.15.4` (`latest`, 비인증 공개 API 검증 완료).
- **iOS 5.4.7 (빌드 9) 생체인증 잠금 해제 지연 수정 및 공개 릴리스 완료.**
  - 변경 사항: `AppLock.swift`, `AppLockViews.swift`에서 `BiometricSession`으로 `LAContext.canEvaluatePolicy`를 `.userInteractive` 백그라운드 큐에서 사전 준비/실행해 메인 스레드 동기 XPC 지연을 제거했고, `.active` 복귀 시 중첩 `.task`/`Task` 없이 즉시 호출하며, 인증 성공 직후 단일 무애니메이션 `withTransaction`으로 `authenticating`/`suppressInactiveShield`/`locked`를 한 번에 갱신한 뒤 세션을 해제하도록 수정했다(`umtomi/ios/project.yml` `5.4.7` / `9`). (직전 `5.4.6`/`8`에는 OLED 순수 블랙 다크모드 적용.)
  - 검증 및 산출물: GitHub Actions 워크플로([#44753430328](https://github.com/omyeol/Umtomi/actions/runs/44753430328)) macOS 무서명 IPA 빌드/체크섬 통과, `artifacts/Umtomi-iOS-5.4.7.ipa` (1,949,254 bytes, SHA-256 `1190a0536ebc93b81bb47a8846cbfcde9fee8db3c3668ed3ee96f41c49ca5e96`, `Info.plist`: `xyz.sabok.umtomi` / `5.4.7` / `9`), 릴리스 `https://github.com/omyeol/Umtomi/releases/tag/ios-v5.4.7`, LiveContainer 피드 `https://raw.githubusercontent.com/omyeol/Umtomi/main/livecontainer.json` 공개 검증 완료.
- **다음 작업.** 실기기에서 iOS `5.4.7` 생체인증 잠금 해제 속도 및 OLED 다크모드 표시를 확인하고, Android 실기기 생체 인증·Google Drive 실계정 동기화·`v5.15.4` 인앱 업데이터(`omyeol/Umtomi/releases`) 조회를 최종 확인한다.

## Android 빌드와 확인

저장소 루트에서 실행한다. 빌드 스크립트는 OneDrive 바깥에 중간 파일을 만들고, 프로젝트 `artifacts`와 사용자 APK 폴더에 결과를 복사한 뒤 해시를 비교한다.

```powershell
git status --short --branch
& .\umtomi\Build-Android.ps1 -Test
adb devices -l
```

- MuMu DEV가 실행 중이면 기기 주소 `127.0.0.1:16416`을 확인하고, 설치와 테스트 명령에서 `adb -s 127.0.0.1:16416`으로 기기를 지정한다.
- 기존 설치에 업데이트할 때는 패키지 `xyz.sabok.umtomi`와 이전 APK 서명을 확인한다. 최신 정식 빌드는 `C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.15.4.apk`에 있다.
- 책장 저장소나 동기화 동작을 수정하기 전에 [저장소 계약](BOOKSHELF_STORAGE_CONTRACT.md)과 [Omyaru Browser 인수인계](../BROWSER/docs/AI_HANDOFF.md)를 확인한다.
- iOS 빌드 경로는 `.github/workflows/ios-ipa.yml`에 있다. 이전 작업, 릴리스 식별자, 해시는 [상세 작업 이력](CODEX_HANDOFF.md)에 기록되어 있다.

## 다음 전환 때 갱신할 항목

| 항목 | 기록할 내용 |
| --- | --- |
| 변경 | 버전, 수정한 경로, 핵심 동작 |
| 검증 | 실행한 검사, 사용 기기, 확인한 동작 |
| 산출물 | APK 또는 IPA 경로, 배포 여부 |
| 다음 작업 | 미완료 항목, 재현 조건, 관련 파일 |
