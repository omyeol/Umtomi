# Umtomi 인수인계

Codex와 Antigravity에서 같은 폴더를 열어 순서대로 작업한다.

`E:\DEV\CODEX\Umtomi`

새 세션에서는 **“`AGENTS.md`와 `HANDOFF_KO.md`를 읽고 지금 작업 폴더에서 이어서 작업해줘.”**라고 시작하면 된다. 작업을 넘기기 전에 저장되지 않은 편집을 저장하고, 아래 작업 상태와 검증 결과를 갱신한다.

## 현재 상태

- **Android 5.15.7 (빌드 107) & iOS 5.4.10 (빌드 12) 새 앱 아이콘 적용 및 공개 릴리스 완료 (커밋 `1137915`, 피드 `b6ec2ef`).**
  - **공통 아이콘 교체**: 사용자 지정 3D 그라디언트 `U` 엠블럼으로 iOS `AppIcon.appiconset` 18종 PNG, Android 적응형 아이콘(`res/drawable-anydpi-v26/umtomi_icon.xml`, `res/drawable-nodpi/umtomi_icon_foreground.png`, `res/values/colors.xml` `umtomi_icon_background` `#EEF1FC`), Android 밀도별 폴백(`res/drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/umtomi_icon.png`), `umtomi/app/src/main/ic_launcher-web.png`(512×512), 저장소 루트 `umtomi-icon.png`(1024×1024)를 생성·교체하고 기존 벡터 `res/drawable/umtomi_icon.xml`을 제거했다. `Publish-iOSRelease.py`와 `livecontainer.json`의 `iconURL`도 `https://raw.githubusercontent.com/omyeol/Umtomi/main/umtomi-icon.png`로 갱신했다.
  - **Android `5.15.7` (`versionCode = 107`)**: `.\umtomi\Build-Android.ps1 -Test` 통과 (`assembleDebug`, `testDebugUnitTest` 8개 스위트 24건 실패/오류/스킵 0, `lintDebug`). `E:\DEV\CODEX\Umtomi\artifacts\Umtomi-v5.15.7.apk` 및 `C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.15.7.apk` (`21,450,495` bytes, SHA-256 `4449A7E241CFF2D8800A4BAC10F16AD57AA194D40FA647DD88D24F1D5333CEE3`). 릴리스 `https://github.com/omyeol/Umtomi/releases/tag/v5.15.7` (`latest`, 비인증 공개 API 검증 완료).
  - **iOS `5.4.10` (빌드 `12`)**: GitHub Actions([#45575261755](https://github.com/omyeol/Umtomi/actions/runs/45575261755)) `success`, `E:\DEV\CODEX\Umtomi\artifacts\Umtomi-iOS-5.4.10.ipa` (`3,475,312` bytes, SHA-256 `d7284fa796fd0bd838a73559b9627d20caa62063184f8902ce22ed88b30f6438`, `Info.plist`: `xyz.sabok.umtomi` / `5.4.10` / `12`), 릴리스 `https://github.com/omyeol/Umtomi/releases/tag/ios-v5.4.10`, LiveContainer 피드 `https://raw.githubusercontent.com/omyeol/Umtomi/main/livecontainer.json` 및 `umtomi-icon.png` 공개 검증 완료.
- **이전 5.15.6 / 5.4.9 (커밋 `8f76ef9`)**: 공용 동기화 폴더·계정별 분리·동시 동기화 잠금(`sync.lock`/`sync-lease.json`)·iOS 고속 병렬 동기화 반영 완료 (`Umtomi-v5.15.6.apk`, `ios-v5.4.9`).
- **다음 작업.** 실기기에서 새 앱 아이콘 렌더링(Android 런처 적응형/원형 마스크 및 네비게이션 헤더, iOS 홈 화면/LiveContainer 피드 아이콘), iOS 생체인증 잠금 해제·OLED 다크모드·공용 폴더 병렬 동기화, Android 실기기 생체 인증·Google Drive 실계정 동기화·인앱 업데이터(`v5.15.7`)를 최종 확인한다.

## Android 빌드와 확인

저장소 루트에서 실행한다. 빌드 스크립트는 OneDrive 바깥에 중간 파일을 만들고, 프로젝트 `artifacts`와 사용자 APK 폴더에 결과를 복사한 뒤 해시를 비교한다.

```powershell
git status --short --branch
& .\umtomi\Build-Android.ps1 -Test
adb devices -l
```

- MuMu DEV가 실행 중이면 기기 주소 `127.0.0.1:16416`을 확인하고, 설치와 테스트 명령에서 `adb -s 127.0.0.1:16416`으로 기기를 지정한다.
- 기존 설치에 업데이트할 때는 패키지 `xyz.sabok.umtomi`와 이전 APK 서명을 확인한다. 최신 정식 빌드는 `C:\Users\peopl\OneDrive\DEV\APK\Umtomi-v5.15.7.apk`에 있다.
- 책장 저장소나 동기화 동작을 수정하기 전에 [저장소 계약](BOOKSHELF_STORAGE_CONTRACT.md)과 [Omyaru Browser 인수인계](<C:/Users/peopl/OneDrive/DEV/CODEX/BROWSER/docs/AI_HANDOFF.md>)를 확인한다.
- iOS 빌드 경로는 `.github/workflows/ios-ipa.yml`에 있다. 이전 작업, 릴리스 식별자, 해시는 [상세 작업 이력](CODEX_HANDOFF.md)에 기록되어 있다.

## 다음 전환 때 갱신할 항목

| 항목 | 기록할 내용 |
| --- | --- |
| 변경 | 버전, 수정한 경로, 핵심 동작 |
| 검증 | 실행한 검사, 사용 기기, 확인한 동작 |
| 산출물 | APK 또는 IPA 경로, 배포 여부 |
| 다음 작업 | 미완료 항목, 재현 조건, 관련 파일 |
