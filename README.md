# Linky

같은 Wi-Fi에 있는 안드로이드 폰 화면을 노트북에서 보고 마우스로 조작합니다.
adb와 무선 디버깅을 쓰지 않아서, **삼성 "보안 위험 자동 차단"을 켜둔 채로** 쓸 수 있습니다.

- 영상: 폰의 하드웨어 H.264 인코더 → 노트북에서 FFmpeg로 해독
- 조작: 노트북 마우스 → 폰 접근성 서비스가 실시간 드래그로 실행
- 보안: 상호 TLS 암호화 + 처음 한 번 숫자 비교 페어링(양쪽에 같은 6자리가 보이면 확인)

## 폴더

| 폴더 | 내용 |
|---|---|
| `desktop/` | 노트북 앱 (Kotlin Compose Desktop) |
| `android/` | 폰 앱 (Kotlin, AndroidX 없음) |
| `shared/` | 두 앱이 같이 쓰는 통신 규칙 |
| `android-probe/` | 0단계 테스트 앱 (참고용) |
| `docs/` | 설계서와 기록 |

## 기록

| 문서 | 내용 |
|---|---|
| [docs/linky-design.md](docs/linky-design.md) | 설계서: 통신 순서, 보안, 영상, 조작 |
| [CHANGELOG.md](CHANGELOG.md) | 버전마다 바뀐 점 |
| [docs/decisions.md](docs/decisions.md) | 만들면서 한 고민, 선택지, 결정과 이유 |
| [docs/errors.md](docs/errors.md) | 겪은 오류, 원인, 해결 (새 오류 쓰는 양식 포함) |

## 빌드와 설치

**노트북 앱**

```powershell
cd desktop
.\gradlew.bat run
```

exe 만들기: `.\gradlew.bat createDistributable` → `desktop\build\compose\binaries\main\app\Linky\Linky.exe`

**폰 앱**

```powershell
cd android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
```

**폰에 설치**

Play 프로텍트가 파일 설치를 막기 때문에 adb로 설치합니다.

1. 폰에서 보안 위험 자동 차단을 잠깐 끄고, 무선 디버깅을 켭니다.
2. 노트북에서 아래를 실행합니다.

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\android\install.ps1
   ```

   Windows 기본 설정이 .ps1 실행을 막기 때문에, 이번 실행에만 허용하는 형태로 씁니다 (errors E-015).

3. 설치가 끝나면 폰에서 무선 디버깅을 끄고 자동 차단을 다시 켭니다.

## 처음 쓰는 법

1. 폰 Linky 앱에서 접근성 권한을 켭니다. 막혀 있으면 앱 정보에서 "제한된 설정 허용"을 누른 뒤 켭니다.
2. 폰에서 **[화면 공유 시작]**을 누르고, 처음이면 **[새 노트북 연결]**도 누릅니다.
3. 노트북 Linky에서 폰을 고르면 양쪽에 6자리 숫자가 뜹니다. 같으면 양쪽에서 **[일치]**를 누릅니다.
4. 다음부터는 폰에서 화면 공유만 시작하면 노트북이 자동으로 연결합니다.
