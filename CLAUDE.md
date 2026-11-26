# Linky 작업 규칙

안드로이드 폰(갤럭시 A16) 화면을 노트북에서 보고 조작하는 프로젝트입니다. adb와 무선 디버깅을 쓰지 않아서, 삼성 "보안 위험 자동 차단"을 켜둔 채로 동작하는 것이 목표입니다. 사용자와는 한국어로 대화합니다.

- `desktop/`: 노트북 앱 Linky. Kotlin Compose Desktop, FFmpeg(H.264 해독), JmDNS
- `android/`: 폰 앱 Linky. MediaProjection과 H.264 인코딩, 접근성 서비스(조작), TLS 서버
- `shared/`: 두 앱이 같이 컴파일하는 통신 규칙 (`dev.linky.protocol`). 바꾸면 두 앱을 모두 다시 빌드한다.
- `android-probe/`: 0단계 테스트 앱. 검증된 코드(드래그 엔진 등)의 출처로 남겨둔 것
- 설계서: [docs/linky-design.md](docs/linky-design.md). 통신 순서, 보안, 영상, 조작 규칙이 들어 있다. 동작을 바꾸면 설계서도 함께 고친다.

## 기록 규칙 (꼭 지킬 것)

작업이 끝날 때마다 아래 세 문서를 함께 갱신한다. 코드만 바꾸고 기록을 빠뜨리지 않는다.

1. **[CHANGELOG.md](CHANGELOG.md)**: 사용자에게 보이는 동작이 바뀌면 해당 구성(폰 앱 / 노트북 앱)의 새 버전 항목을 맨 위에 추가한다.
   - 버전을 올릴 때는 숫자도 함께 맞춘다.
     - 폰 앱: `android/app/build.gradle.kts`의 `versionCode`, `versionName`
     - 노트북 앱: `desktop/build.gradle.kts`의 `packageVersion`
   - 통신 규칙(`shared`)이 바뀌면 `Linky.PROTOCOL_VERSION`을 올리고 두 앱을 같이 배포한다.
2. **[docs/decisions.md](docs/decisions.md)**: 사용자가 고민을 이야기하거나 방향을 정하면 `D-번호` 항목을 아래에 추가한다. 고민, 선택지, 결정, 이유, 남은 걱정을 쓴다. 끝의 "다음에 정할 것"도 갱신한다.
3. **[docs/errors.md](docs/errors.md)**: 오류가 나면 바로 `E-번호` 항목과 "한눈에 보기" 표의 줄을 추가한다.
   - 증상에는 나온 문구를 그대로 쓴다.
   - 원인과 해결은 알게 되면 채우고, 상태를 갱신한다.
   - 사용자가 겪은 오류뿐 아니라 빌드 오류, 설치 오류, 검토에서 찾은 버그도 적는다.

## 빌드

- 노트북 앱: `desktop`에서 `.\gradlew.bat run` (개발 실행), `.\gradlew.bat createDistributable` (exe)
- 폰 앱: `android`에서 `JAVA_HOME`을 `C:\Program Files\Android\Android Studio\jbr`로 맞추고 `.\gradlew.bat assembleDebug`
  - `cmd /c`로 실행하면 작업 폴더가 달라질 수 있으니 PowerShell에서 직접 실행한다 (errors E-007).
- 폰 앱 설치: Play 프로텍트 때문에 파일로는 설치되지 않는다 (errors E-005). 자동 차단을 잠깐 끄고 무선 디버깅을 켠 뒤 `android/install.ps1`(adb)로 설치하고, 끝나면 다시 켠다.
