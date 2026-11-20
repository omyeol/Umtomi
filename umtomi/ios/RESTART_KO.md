# 새 Mac에서 Umtomi iOS 다시 시작하기

평소에는 [GitHub Actions의 iOS 빌드](https://github.com/omyeol/Umtomi/actions/workflows/ios-ipa.yml)를 사용하세요. `main`의 iOS 변경은 자동으로 빌드되며, **Run workflow**로도 실행할 수 있습니다. 완료된 실행의 **Artifacts**에서 IPA와 체크섬을 내려받습니다. 기본 빌드에는 Omyaru의 공개 iOS OAuth 설정이 들어 있습니다. IPA는 기존에 사용하던 서명·설치 방법으로 iPhone에 설치합니다.

2026-11-20에 Windows 작업 폴더에서 확인한 안내입니다. 이번에는 준비만 진행했으며 iOS 빌드와 실행은 아직 검증하지 않았습니다.

## 예전에 사용한 방식

SwiftUI 프로젝트를 Mac에서 `build-ipa.sh`로 빌드하여 서명하지 않은 IPA를 만든 뒤 iCloud Drive의 `IPA` 폴더로 복사했습니다. 예전 Mac 경로는 `/Users/omyeolmacair/DEV/Umtomi/umtomi/ios`였지만 새 Mac에서는 어떤 경로에 풀어도 됩니다.

서명하지 않은 IPA는 파일을 누르는 것만으로 일반 설치할 수 없습니다. 예전에 사용한 iPhone 설치·서명 도구 이름은 기록에 없습니다. 기존 설치 방법을 확인하거나 Xcode의 기기 실행을 사용해야 합니다.

## 새 Mac 준비

1. Xcode 전체 앱을 설치하고 한 번 열어 초기 설정과 iOS 플랫폼 설치를 완료합니다.
2. `xcodegen --version`을 확인합니다. 없다면 Homebrew가 준비된 환경에서 `brew install xcodegen`으로 설치합니다.
3. 소스 ZIP을 풉니다. 압축 안의 `Umtomi-iOS` 폴더를 원하는 개발 폴더로 옮깁니다. 아래 예시는 `~/DEV/Umtomi-iOS`입니다.
4. 터미널에서 환경을 확인합니다. `--check`는 빌드하거나 기존 IPA를 정리하지 않습니다.

```bash
cd ~/DEV/Umtomi-iOS
bash build-ipa.sh --check
```

iPhoneOS SDK를 찾지 못하면 Xcode 초기 설정과 플랫폼 설치를 확인하세요. 필요하면 아래 명령으로 사용할 Xcode를 선택합니다. Xcode가 다른 위치에 있으면 해당 경로를 사용하세요.

```bash
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
```

## 프로젝트 생성과 실행

```bash
cd ~/DEV/Umtomi-iOS
xcodegen generate --spec project.yml
open Umtomi.xcodeproj
```

- `Umtomi` 스킴과 설치된 iPhone 시뮬레이터를 선택해 실행합니다.
- 실제 iPhone에서는 Xcode의 Signing & Capabilities에서 사용할 Team을 선택하고 기기를 선택합니다.
- `UmtomiTests`를 실행해 기본 파싱 검사를 합니다.
- 프로젝트 원본은 `project.yml`입니다. 소스가 추가되었으므로 예전 `.xcodeproj`를 그대로 사용하지 말고 반드시 재생성하세요. 특히 `Bookshelf.swift`가 포함되어야 합니다.
- Xcode에서 선택한 Team은 프로젝트 재생성으로 초기화될 수 있습니다. 지속적인 서명 설정은 Mac에서 관리하고 인증서·비밀키를 공유하지 마세요.

## IPA 만들기

예전과 같은 서명하지 않은 IPA:

```bash
cd ~/DEV/Umtomi-iOS
bash build-ipa.sh
```

위 예시 위치를 사용했을 때 출력은 다음과 같습니다.

- 로컬: `~/DEV/Umtomi-iOS/build/Umtomi-unsigned.ipa`
- iCloud 복사본: `~/Library/Mobile Documents/com~apple~CloudDocs/IPA/Umtomi-unsigned-YYMMDD-HHMM.ipa`

새 Mac의 iCloud Drive 연결 상태를 확인하세요. 스크립트는 복사할 때 이전 `Umtomi-*.ipa`만 정리하며 다른 앱의 IPA는 건드리지 않습니다.

해당 Mac의 Xcode에 Apple 계정과 서명 환경이 준비되어 있으면 서명 빌드도 지원합니다.

```bash
APPLE_TEAM_ID=YOUR_TEAM_ID bash build-ipa.sh
```

서명 빌드 결과는 `build/export/`에 생성되고 스크립트가 최종 경로를 출력합니다. 기존 스크립트의 `EXPORT_METHOD` 기본값은 `development`입니다. 지원되는 내보내기 방식과 서명 설정은 설치된 Xcode에서 확인해야 합니다.

## 현재 코드와 최근 Android·PC의 차이

- 검색, 태그 제안, 작품 정보, 다운로드, 기록, 즐겨찾기·폴더·JSON 백업 코드가 있습니다.
- 뷰어에는 여러 탭, 이어 보기, 페이지형·세로 스크롤과 확대 기능이 있습니다.
- 책장에는 사진 가져오기, 폴더, 메모, 열 수 조절과 PDF 내보내기 코드가 있습니다. 책장 탭은 기본 순서에서 ‘더 보기’에 들어갈 수 있습니다.
- iOS 책장은 앱 안의 `UmtomiBookshelf`에 저장하며, Google Drive를 통해 공통 책장에 연결합니다. iOS OAuth 클라이언트가 설정된 빌드에서 사용할 수 있습니다.
- 이번 소스의 iOS 버전은 `5.4.4` / 빌드 `6`입니다. Bundle ID는 Android와 같은 `xyz.sabok.umtomi`입니다.

## Google Drive 연결 준비

OAuth 설정이 포함된 ZIP에서는 `DriveSync.local.xcconfig`에 Omyaru용 공개 클라이언트 ID와 iOS URL scheme이 들어 있습니다. 4단계부터 진행하세요.

1. 실제 Omyaru 공통 책장에 접근하는 Google Cloud 프로젝트의 **Google Auth Platform → 클라이언트**에서 **iOS** 클라이언트를 만듭니다. Bundle ID에 `xyz.sabok.umtomi`를 입력합니다. 저장소에 기록된 기존 Omyaru 프로젝트 번호는 `136563471424`입니다.
2. 같은 프로젝트에서 Drive API가 켜져 있는지 확인합니다. OAuth 앱이 테스트 상태라면, 연결할 Google 계정이 테스트 사용자에 포함되어야 합니다.
3. 이 디렉터리에 `DriveSync.local.xcconfig`를 만들고 콘솔에 표시된 **공개 iOS 클라이언트 ID**와 **iOS URL scheme**을 적습니다. 아래 두 값은 예시입니다.

```xcconfig
UMTOMI_GOOGLE_IOS_CLIENT_ID = 1234567890-example.apps.googleusercontent.com
UMTOMI_GOOGLE_IOS_URL_SCHEME = com.googleusercontent.apps.1234567890-example
```

4. Xcode 프로젝트를 다시 생성하고 빌드합니다. 첫 빌드에서는 Xcode가 Google Sign-In 패키지를 받습니다.
5. 앱에서 **설정 → Google Drive 동기화**를 열고 기존 책장을 쓰는 계정으로 연결합니다. iOS에서 기존 암호화 키를 읽을 수 있는지 먼저 확인합니다. Umtomi 전용 키가 없는 경우 Android Umtomi에서 같은 프로젝트와 계정으로 한 번 동기화해 확인하세요.
6. 사진 저장 방식을 선택합니다. **로컬에 저장**은 원본을 미리 다운로드하고, **필요할 때 클라우드에서 불러오기**는 사진을 표시할 때 내려받습니다. 이미 보관한 원본은 유지합니다.
7. **백그라운드에서 책장 동기화**를 켜면 iOS에 작업을 예약합니다. 백그라운드 앱 새로 고침이 꺼져 있거나 iOS가 아직 실행 시간을 배정하지 않은 경우 예약 동기화가 늦어질 수 있습니다. 실제 iPhone에서 확인하세요.
8. **동기화 진행 알림**을 켜고 iOS 알림 요청을 허용합니다. 진행 중에는 같은 조용한 알림에 단계와 진행률을 갱신하고, 끝나면 완료·중단·실패 상태를 보여줍니다. 백그라운드 동기화가 iOS에 의해 실제로 시작된 경우에도 같은 방식으로 표시합니다.

기존 책장의 계정 및 iOS용 OAuth 클라이언트에서 같은 Drive 데이터가 보이는지는 첫 연결에서 확인해야 합니다. 계정 토큰과 비밀 키를 이 파일에 넣을 필요는 없습니다.

5.4.2의 동기화 화면에서는 로그인 복원·갱신, 암호화 키, 변경 기록, 사진 진행률을 따로 표시합니다. 로그인 복원이나 갱신이 25초 안에 응답하지 않으면 실패로 표시합니다. 첫 동기화에서 변경 기록과 사진이 많은 경우에는 오래 걸릴 수 있습니다. `지금 동기화`를 누르기 전에 마지막으로 표시된 단계와 진행 숫자를 확인하면 문제가 난 지점을 구분할 수 있습니다.

## 5.4.0에서 확인할 기능

1. 기존 책장이 있으면 업그레이드 후 폴더와 사진이 그대로 나타나는지 확인합니다. 하위 책장을 만들고 이동·정렬한 뒤 상위 책장에서 사진을 봅니다.
2. 세로 스크롤 뷰어의 한 컷 보기로 전환했다가 복귀합니다. 뷰어의 자르기 버튼을 눌러 한 컷 또는 연속 컷을 캡처하고, 미리보기에서 범위를 조정해 파일과 책장에 각각 저장합니다.
3. 진행 중인 작품 다운로드를 취소합니다. 다시 다운로드한 뒤 오프라인에서 열어봅니다.
4. **설정 → 하단 탭 바 순서**에서 ‘더 보기 항목 켜기’를 끄면 여섯 항목이 탭 바에 직접 나옵니다. 켜면 항목을 두 목록 사이에서 옮길 수 있습니다. 각 목록 안에서 순서를 바꾸려면 설정의 편집 버튼을 누릅니다.
5. **설정 → 개인정보 보호 → 앱 잠금**에서 앱 전용 비밀번호를 먼저 등록합니다. 5.4.3 이전에 기기 인증 잠금을 켜두었다면, 업데이트 후 처음 열 때 기존 기기 인증을 한 번 거쳐 앱 비밀번호를 등록합니다.
6. 생체 인증을 켜고 잠그면 Face ID 또는 Touch ID가 먼저 뜨고 앱 비밀번호로도 열 수 있습니다. ‘즉시 잠금’을 끄면 앱을 백그라운드로 보낸 뒤 설정한 분이 지났을 때 다시 열면서 잠깁니다. 인증 성공 직후 검은 가림 화면이 남는지 실제 기기에서 확인합니다.

캡처는 현재 컷에서 최대 8컷까지 이어 붙입니다.

## 재개 순서

1. 환경 확인 → 프로젝트 재생성 → 현재 소스 시뮬레이터 빌드.
2. 기본 검사 실행 및 검색·뷰어·즐겨찾기·로컬 책장 확인.
3. 기존 iPhone 설치 방법 확인과 앱 데이터 백업.
4. 새 iOS 버전/빌드 번호 결정과 실제 기기 설치.
5. 확인된 기반 위에 Google Drive 공통 책장 등 필요한 기능 이식.

현재 Windows PC에는 Xcode가 없습니다. 새 동기화 코드의 Xcode 빌드와 실제 기기·Google 계정 확인은 필요합니다.
