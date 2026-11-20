# Umtomi Google Drive 등록

**5.12.0 Omyaru 공통 책장 연결은 [별도 이전 안내](SHARED_BOOKSHELF_SETUP.md)를 먼저 확인하세요.** 아래 내용은 기존 Umtomi 전용 동기화 안내입니다.

현재 APK 패키지 이름은 **xyz.sabok.umtomi**입니다. 앞서 안내한 `.debug` 이름 대신 이 값을 등록하세요.

1. Google Cloud Console에서 사용할 프로젝트를 선택합니다. 기존 Omyaru 프로젝트도 사용할 수 있습니다.
2. Google Drive API를 활성화합니다.
3. Google Auth Platform → 클라이언트 → 클라이언트 만들기에서 Android를 선택합니다.
4. 이름 Umtomi, 패키지 `xyz.sabok.umtomi`, SHA-1 아래 값을 입력합니다.

`08:D6:BB:25:59:AD:10:DE:B7:16:7E:51:72:71:BF:4C:D8:46:5F:C8`

5. 앱의 데이터 액세스 범위에 `https://www.googleapis.com/auth/drive.appdata`를 추가합니다. 동의 화면이 테스트 상태라면 사용할 Google 계정을 테스트 사용자로 등록합니다.
6. Google Play 서비스가 있는 Android 8 이상 기기에 APK를 설치하고 설정 → Google Drive 동기화 또는 책장의 구름 버튼을 엽니다.
7. Google 계정 연결을 누르고 접근을 승인합니다. 같은 계정으로 다른 Umtomi 기기에서 연결한 다음 '지금 동기화'를 누르면 변경 사항을 합칩니다.

클라이언트 비밀키나 Google 비밀번호를 APK/채팅에 넣을 필요가 없습니다. Play 서비스가 패키지와 인증서로 Android 클라이언트를 확인합니다. 현재 인증서는 기존 배포 APK와 동일하며, 패키지 이름만 바뀌었습니다.

## 동기화 범위

- 책장 원본 이미지, 폴더, 메모, 열 수
- 작품 열람 기록, 즐겨찾기와 폴더, 즐겨찾기 태그
- '지금 동기화'로 실행하는 수동 양방향 동기화입니다. 백그라운드 주기 실행은 포함하지 않습니다.
- Omyaru Browser와 별도 `umtomi-*` 파일·암호화 키를 사용합니다. 브라우저 자료를 가져오거나 수정하지 않습니다.
- 앱 전용 Drive 저장소를 사용하므로 일반 내 드라이브 파일 목록에는 나타나지 않습니다.
- 이미지 원본은 조각별 전송 및 SHA-256 검증 후 적용합니다. 데이터와 계정 키는 Google 계정으로 복원됩니다.
- 연결 해제는 이 기기의 연결만 끊으며 로컬/Drive 데이터는 삭제하지 않습니다.

## 패키지 변경

5.7.0 이하의 `xyz.sabok.umtomi.debug`와는 별도 앱으로 설치됩니다. 기존 앱/데이터를 삭제하지 마세요. Android가 앱 내부 데이터를 자동으로 이동해 주지는 않습니다. 즐겨찾기는 기존 앱의 JSON 내보내기 후 새 앱에서 가져올 수 있습니다. 기존 책장과 열람 기록의 자동 패키지 마이그레이션은 포함되지 않았습니다.

5.8.0 APK는 직접 설치해야 합니다. 이후 새 패키지 앱에서는 설정 → 앱 버전의 업데이트를 사용할 수 있습니다.

## 검증 범위

사용자가 OAuth 등록 완료 및 앱 연결 시 오류가 없음을 확인했습니다. 이 작업 환경에서 실제 계정으로 서버 왕복 검증을 별도로 실행하지는 않았습니다. 기기에서 연결 화면, 데이터 병합·삭제, 폴더 복원, 암호화·손상 검출, 이미지 분할 복원을 검증했습니다.

공식 안내: https://developer.android.com/identity/authorization
Drive 앱 데이터: https://developers.google.com/workspace/drive/api/guides/appdata
