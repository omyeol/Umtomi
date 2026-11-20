# Umtomi 인수인계

Codex와 Antigravity에서 같은 폴더를 열어 순서대로 작업한다.

`C:\Users\peopl\OneDrive\DEV\CODEX\Umtomi`

새 세션에서는 **“`AGENTS.md`와 `HANDOFF_KO.md`를 읽고 지금 작업 폴더에서 이어서 작업해줘.”**라고 시작하면 된다. 작업을 넘기기 전에 저장되지 않은 편집을 저장하고, 아래 작업 상태와 검증 결과를 갱신한다.

## 현재 상태

- **작업 폴더가 최신 Android 소스를 갖고 있다.** 로컬 `main`의 HEAD와 로컬에 기록된 `origin/main`은 초기 커밋 `0176962`에 있다. 새 `umtomi/` 소스와 기존 경로의 삭제가 아직 커밋되지 않았다. 다른 도구에서도 이 폴더를 그대로 열고, 커밋과 원격 상태를 정리할 때는 파일을 검토한 후 별도로 진행한다.
- **Android 5.15.3 시험판, 빌드 103.** `umtomi/app/`에서 작업 중이다. iOS·Android 공통 OLED 다크모드(`#000000` 완전 블랙 배경/하단 바/시스템 바/뷰어)를 적용했고, Android 하단 탭 바 편집기(`PortTabBarEditorActivity`, 드래그·미리 보기·1탭 이동·초기화·더 보기 바텀시트)를 새로 구현했다. 또한 iOS 기준의 즐겨찾기(작품/작가 전환, 중첩 폴더·자동 분류·폴더 편집기), 작품 상세(날짜·캐릭터·저장 상태·작가 즐겨찾기 별표·태그 오버레이·관련 작품), 추천 알고리즘(프로필 기반 확장 검색 및 필터), 뷰어 이어보기 확인/미리 불러오기(`LastReadPages`, `DownloadService` 앵커 우선순위), 다운로드 진행/저장 구분까지 포팅했다. Gradle 빌드·21개 단위 테스트·lint를 통과했고 MuMu DEV(`127.0.0.1:16416`)에서 설치 및 `RGB(0,0,0)` OLED 다크모드·탭 편집·즐겨찾기·작품 상세·설정·뷰어 화면을 확인했다.
- **다음 Android 작업.** 실제 기기에서 생체 인증과 Google Drive 실계정 동기화를 검증한다. iOS(`umtomi/ios/Umtomi/`)의 OLED 다크모드 수정(`UmtomiApp.swift`, `Views.swift`, `AppLockViews.swift`, `DriveSyncSettings.swift`, `Bookshelf.swift`, `ReaderCaptureView.swift`)은 Windows 호스트에서 중괄호 구문 검사만 수행했으므로 macOS Actions 빌드 또는 Xcode로 빌드·실기기 확인한다.
- **iOS 5.4.5, 빌드 7.** GitHub Actions에서 빌드한 IPA가 기존 [iOS 릴리스](https://github.com/omyeol/Umtomi-releases/releases/tag/ios-v5.4.5)와 [LiveContainer 소스](https://raw.githubusercontent.com/omyeol/Umtomi-releases/main/livecontainer.json)에 등록되어 있다. (로컬 소스에 OLED 다크모드 수정 반영, 새 IPA 빌드는 macOS/Actions에서 필요)
- **배포 전환 조건.** iOS와 Android의 기능이 맞고 검증된 뒤 `omyeol/Umtomi`를 공개하고 정식 릴리스를 옮긴다. 그 전에는 기존 릴리스 및 LiveContainer URL을 이용한다.

## Android 빌드와 확인

저장소 루트에서 실행한다. 빌드 스크립트는 OneDrive 바깥에 중간 파일을 만들고, 프로젝트 `artifacts`와 사용자 APK 폴더에 결과를 복사한 뒤 해시를 비교한다.

```powershell
git status --short --branch
& .\umtomi\Build-Android.ps1 -Test
adb devices -l
```

- MuMu DEV가 실행 중이면 기기 주소 `127.0.0.1:16416`을 확인하고, 설치와 테스트 명령에서 `adb -s 127.0.0.1:16416`으로 기기를 지정한다.
- 기존 설치에 업데이트할 때는 패키지 `xyz.sabok.umtomi`와 이전 APK 서명을 확인한다. 최신 시험판은 `C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.15.3.apk`에 있다.
- 책장 저장소나 동기화 동작을 수정하기 전에 [저장소 계약](BOOKSHELF_STORAGE_CONTRACT.md)과 [Omyaru Browser 인수인계](../BROWSER/docs/AI_HANDOFF.md)를 확인한다.
- iOS 빌드 경로는 `.github/workflows/ios-ipa.yml`에 있다. 이전 작업, 릴리스 식별자, 해시는 [상세 작업 이력](CODEX_HANDOFF.md)에 기록되어 있다.

## 다음 전환 때 갱신할 항목

| 항목 | 기록할 내용 |
| --- | --- |
| 변경 | 버전, 수정한 경로, 핵심 동작 |
| 검증 | 실행한 검사, 사용 기기, 확인한 동작 |
| 산출물 | APK 또는 IPA 경로, 배포 여부 |
| 다음 작업 | 미완료 항목, 재현 조건, 관련 파일 |
