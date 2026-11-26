# Linky 설계서 (v0.1)

폰 화면을 노트북 앱에서 보고 조작한다. **adb, 무선 디버깅을 쓰지 않으므로 삼성 보안 위험 자동 차단을 켜둔 채로 동작**하는 것이 목표다.

| 구성 | 위치 | 기술 |
|---|---|---|
| 폰 앱 Linky | `android/` | Kotlin, AGP 9.3.3, minSdk 29, targetSdk 36, AndroidX 없음 |
| 노트북 앱 Linky | `desktop/` | Kotlin 2.4.21, Compose Desktop 1.12.2 (Material 3), JDK 21 |
| 공유 통신 규칙 | `shared/` | 두 앱이 같은 소스를 컴파일 (`dev.linky.protocol`) |

공유 코드 (이미 있음, 바꾸면 두 앱 모두 다시 빌드):
- `Linky.kt`: 포트(47180), mDNS 서비스(`_linky._tcp`), 메시지 종류(`Msg`), 키(`Keys`)
- `Frames.kt`: `[길이 u32][종류 u8][내용]` 프레임 읽기/쓰기 (`FrameReader`, `FrameWriter`)
- `Messages.kt`: `Touch`, `Scroll`, `VideoConfig`, `VideoFrame`, `Props`(키=값)
- `Pairing.kt`: 난수, 약속값, 6자리 숫자(SAS), 인증서 지문
- `Identity.kt`: EC P-256 자체 서명 인증서를 만들어 PKCS12 파일에 저장/로드 (BouncyCastle은 인증서 생성에만 쓰고 공급자로 등록하지 않음)
- `Tls.kt`: 상호 TLS. 인증서 검증은 TLS가 아니라 연결 후 **지문 비교**로 한다.

---

## 1. 연결 흐름

```
폰: [화면 공유 시작] → 동의 창 → 포그라운드 서비스 시작
    → TLS 서버(포트 47180) 열기 + mDNS(_linky._tcp) 알리기
노트북: mDNS로 폰 발견 → TLS 연결 (서로 인증서 제시)
양쪽: 상대 인증서 지문 확인 → HELLO 교환
  ├─ 서로 페어링한 사이 → 바로 세션(영상+조작) 시작
  ├─ 아니고, 폰의 "새 노트북 연결" 창이 열려 있음 → 숫자 비교 페어링 → 성공하면 세션 시작
  └─ 아니고, 창이 닫혀 있음 → 폰이 NOTICE로 안내 후 연결 종료
```

### 1.1 TLS
- 폰: `SSLServerSocket`(`Tls.context(identity).serverSocketFactory`), `needClientAuth = true`, `Tls.restrictProtocols`.
- 노트북: 일반 `Socket`으로 5초 제한 연결 → `sslSocketFactory.createSocket(socket, host, port, true)`, `useClientMode = true`, `Tls.restrictProtocols`, `startHandshake()`.
- 양쪽 `tcpNoDelay = true`.
- 핸드셰이크 후 `Tls.peerFingerprint(socket)`로 상대 지문을 얻는다.

### 1.2 HELLO
**노트북이 먼저** 보내고, 폰은 그것을 받은 **뒤에** 그 순간의 판단을 담아 답한다. (v0.1 검토 후 변경: 처음에는 동시에 보냈는데, 그러면 폰이 연결 시점의 값을 오래 쥐고 있다가 나중에 쓰는 문제가 있었다.)
- 노트북 → `Msg.CLIENT_HELLO` (핸드셰이크 직후 바로). Props:
  - `name`: 컴퓨터 이름
  - `version`
  - `trusted`: "1"이면 이 폰 지문이 노트북의 신뢰 목록에 있음
  - `bitrate`, `maxSize`: 처음 영상 설정. 세션 시작 때 인코더를 두 번 만들지 않기 위해
- 폰 → `Msg.HELLO` (CLIENT_HELLO를 받은 뒤). Props:
  - `name`: 기기 이름, 예: "Galaxy A16"
  - `model`, `sdk`
  - `version`: `Linky.PROTOCOL_VERSION`
  - `id`: `Pairing.shortId(폰 지문)`, 화면 비교용
  - `trusted`: "1"이면 지금 이 노트북 지문이 폰 신뢰 목록에 있음
  - `pairing`: "1"이면 지금 페어링 창이 열려 있어 이 연결로 페어링할 수 있음
- `version`이 다르면 폰은 NOTICE("앱 버전이 달라 연결할 수 없습니다. 두 앱을 같은 버전으로 맞춰주세요.") 후 종료한다. 노트북도 스스로 판단해 같은 안내를 보여준다.
- 폰은 연결을 받은 뒤 **10초 안에** CLIENT_HELLO를 처리하지 못하면 끊는다. PING을 보내도 연장되지 않는 절대 시한이다.
- **인증 전 제한:**
  - 프레임은 `Linky.MAX_PREAUTH_FRAME`(8KB), Props는 `Props.MAX_BYTES`(4KB)까지만 받는다.
  - 상대가 보낸 이름은 `Props.cleanName`으로 정리한 뒤 화면과 로그에 쓴다.
  - 로그 한 줄은 300자까지만 남긴다.

### 1.3 판단 (양쪽이 같은 규칙으로 판단)
- `phoneTrusts` = 폰 신뢰 목록에 노트북 지문이 있음, `desktopTrusts` = 노트북 신뢰 목록에 폰 지문이 있음.
- **둘 다 참** → 세션 시작 (2장).
- **하나라도 거짓 + 폰 페어링 창 열림** → 페어링 (1.4).
- **하나라도 거짓 + 페어링 창 닫힘** → 폰이 NOTICE("이 노트북은 아직 페어링되지 않았습니다. 폰의 Linky 앱에서 [새 노트북 연결]을 누른 뒤 다시 연결하세요.") 후 종료. 노트북은 이 안내를 그대로 보여준다.

### 1.4 숫자 비교 페어링 (`Pairing.kt` 주석 참고)
1. 노트북 → `PAIR_COMMIT`(= `Pairing.commit(노트북난수)`)
2. 폰 → `PAIR_NONCE`(폰 난수)
3. 노트북 → `PAIR_REVEAL`(노트북 난수). 폰은 `Pairing.verifyCommit`으로 확인하고, 틀리면 NOTICE 후 종료.
4. 양쪽이 `Pairing.sas(폰지문, 노트북지문, 폰난수, 노트북난수)`를 계산해 **큰 글씨로** 보여준다.
   - 폰: 앱 화면이 보이면 대화상자로, 아니면 알림(숫자 + [일치] [다름] 버튼)으로 보여준다.
   - 노트북: 대화상자 "폰에도 같은 숫자가 보이면 [일치]를 누르세요"
5. 노트북 → `PAIR_CONFIRM`(1 = 일치, 0 = 다름)
6. 폰은 **자기 사용자 확인 + PAIR_CONFIRM(1)** 둘 다 받으면 `PAIR_RESULT`(1), 하나라도 거절이거나 120초가 지나면 `PAIR_RESULT`(0) 후 종료.
7. 결과가 1이면 양쪽 모두 상대 지문과 이름을 신뢰 목록에 저장하고 바로 세션 시작. 폰의 페어링 창은 닫는다.

순서가 어긋난 메시지를 받으면 NOTICE 후 종료한다.

**페어링을 지키는 추가 규칙 (v0.1 검토 후 추가)**
- **창 하나에 시도 한 번:**
  - 폰은 PAIR_NONCE를 보내기 직전에 페어링 창을 닫는다.
  - 공격자가 숫자를 미리 보고 끊은 뒤 다시 시도해서, 숫자가 우연히 맞을 때까지 반복하는 것을 막기 위해서다.
  - 중간에 끊기거나 거절되면 폰은 "페어링이 중간에 끊겼습니다. 다시 하려면 [새 노트북 연결]을 누르세요" 알림을 띄운다.
- **자동 연결은 페어링하지 않는다:** 노트북이 스스로 연결한 경우 페어링이 필요하면 멈추고, 사용자가 [연결]을 직접 누르게 한다.
- **인증서가 바뀌면 그 연결로 페어링하지 않는다:**
  - 신뢰하는 폰과 **mDNS fp(지문 앞부분)가 같은데** 처음 보는 인증서를 내밀면, 노트북은 "폰 인증서가 바뀌었습니다" 경고를 띄우고 연결을 닫는다. 경고에는 양쪽 ID를 함께 보여준다.
  - 다시 페어링하려면 사용자가 [페어링 삭제] 후 직접 [연결]해야 한다.
  - **이름이나 주소만 같은 경우**는 막지 않는다. 같은 이름의 다른 폰이나 주소를 이어받은 기기일 수 있기 때문이다. 대신 사용자가 직접 연결해 페어링할 때, 페어링 창에 "주의: 페어링한 ○○(ID …)과 이름이나 주소가 같지만 다른 기기입니다" 경고를 빨간 글씨로 보여준다. (decisions D-016)
- **ID 비교:** 폰 앱 화면과 노트북의 기기 목록, 페어링 대화상자에 `Pairing.shortId`(지문 앞 16글자)를 보여준다.
- **잠금 상태에서 확인 불가:** 폰 알림의 [일치]는 잠금을 풀어야 누를 수 있다 (API 31+ `setAuthenticationRequired`). 확인 창에는 노트북 이름과 노트북 ID를 함께 보여준다.
- **확인 창 정리:** 이미 끝난 연결의 확인 창과 알림은 지운다.

### 1.5 신뢰 목록
- 폰: `SharedPreferences("trusted")`. 키 = 노트북 지문 hex, 값 = 노트북 이름. 앱 화면에서 삭제할 수 있다.
- 노트북: `%APPDATA%\Linky\trusted.properties` (폰 지문 hex = 폰 이름). 화면에서 "페어링 삭제"할 수 있다.
- 신원 파일: 폰 `filesDir/identity.p12`, 노트북 `%APPDATA%\Linky\identity.p12` (`Identity.loadOrCreate`).
  - 임시 파일에 쓴 뒤 한 번에 바꿔치기하고, 다시 읽어서 확인한다.
  - 읽지 못하면 지우지 않고 `.broken-<시각>`으로 옆에 남긴 뒤 새로 만든다. 이때 `Identity.recoveredFrom`이 채워지고, 앱은 "기존 페어링을 다시 해야 합니다" 경고를 보여준다.
- 노트북 신뢰 목록에는 폰마다 마지막으로 본 이름과 주소도 저장한다 (인증서 바뀜 경고용).
- 폰 앱은 백업과 기기 이전 대상에서 빠진다. 개인 키와 신뢰 목록이 기기 밖으로 나가지 않게 하기 위해서다.
- 페어링을 삭제하면 그 노트북의 모든 연결(진행 중인 것 포함)을 끊는다. 세션이 시작될 때 신뢰 여부를 한 번 더 확인한다.

### 1.6 연결 하나만
- 폰은 **인증된 세션을 한 번에 하나만** 유지한다.
  - 새 노트북이 인증되면 이전 세션에 NOTICE("다른 노트북이 연결되었습니다")를 보내고 닫는다.
  - 인증 전 단계(HELLO, 페어링)는 기존 세션에 영향을 주지 않는다.
- 조작은 **현재 활성 세션에서 온 것만** 받는다.
- **인증 전 연결 수 제한:** 전체 4개, 같은 IP에서 2개까지. 활성 세션은 이 수에 넣지 않는다. 인증 전 연결을 여러 개 열어 진짜 노트북을 막는 공격을 막기 위해서다.

### 1.7 살아 있는지 확인
- 노트북은 2초마다 `PING`(보낸 시각 ms, 8바이트)을 보내고, 폰은 같은 내용으로 `PONG`을 돌려준다. 노트북은 왕복 시간(RTT)을 표시한다.
  - PING은 페어링 중에도 보낸다. 폰은 어느 단계에서든 답한다.
  - 폰은 정확히 8바이트인 PING에만 답하고, 인증 전에는 0.5초에 한 번까지만 답한다.
- 소켓 읽기 제한 시간은 10초. 넘으면 끊긴 것으로 보고 정리한다. 폰은 페어링 중에만 약 130초로 늘린다.
- **쓰기 지키기:**
  - 폰은 연결마다 전용 쓰기 스레드를 둔다(영상은 인코더 스레드). 읽기 스레드나 공용 스레드는 소켓에 직접 쓰지 않는다.
  - 쓰기가 5초 넘게 막히면 감시 스레드가 그 소켓을 닫는다.
  - 소켓 닫기는 전용 스레드에서 한다.
- 폰은 세션이 끝나면 반드시 `GestureStreamer.releaseAll()`로 눌린 손가락을 뗀다.
- 노트북은 페어링된 세션이 **뜻하지 않게** 끊기면 다시 연결한다.
  - 그 폰이 목록에 보이는 동안(또는 IP 직접 입력으로 연결했던 경우) 2, 4, 8…초(최대 30초) 간격으로, **최대 8번**까지 시도한다.
  - 다시 연결은 자동 연결로 취급하므로 페어링하지 않는다.
  - 사용자가 [연결 끊기]를 누르면 멈춘다.
- 노트북은 세션을 시작하기 직전에 신뢰 목록을 한 번 더 확인한다. 그 사이 [페어링 삭제]를 했다면 "이 폰의 페어링을 삭제해서 연결하지 않았습니다." 안내 후 닫는다.
- 노트북 신뢰 목록(`trusted.properties`)에는 `<지문>=<이름>` 외에 `<지문>.host=<마지막 주소>`도 저장한다. 예전 파일도 그대로 읽힌다.

---

## 2. 영상 (폰 → 노트북)

### 2.1 폰: 캡처와 인코딩
- MediaProjection은 0단계 테스트 앱(`android-probe/.../CaptureService.kt`)과 같은 규칙을 따른다.
  - 안드로이드 14+ 동의 창은 `MediaProjectionConfig.createConfigForDefaultDisplay()`로 전체 화면 고정
  - 포그라운드 서비스(`mediaProjection` 타입)를 MediaProjection을 만들기 전에 시작
  - 콜백을 등록한 뒤 화면 생성
  - `createVirtualDisplay`는 **한 번만** 호출하고, 바꿀 때는 `resize` / `setSurface`를 쓴다
- VirtualDisplay는 처음에 `surface = null`로 만든다. 세션이 시작되면 인코더 입력 Surface를 연결하고, 끝나면 다시 `null`로 돌린다 (보는 사람이 없을 때 인코딩하지 않음).
- 인코더: `MediaCodec.createEncoderByType("video/avc")`, 입력은 `createInputSurface()`
  - `KEY_COLOR_FORMAT = COLOR_FormatSurface`
  - `KEY_BIT_RATE` = 기본 8 Mbps, `KEY_FRAME_RATE = 60`
  - `KEY_I_FRAME_INTERVAL = 10`(초): 키프레임은 필요할 때 요청한다
  - `KEY_REPEAT_PREVIOUS_FRAME_AFTER = 100_000`(µs): 화면이 멈춰 있어도 영상이 끊기지 않게
  - `KEY_MAX_B_FRAMES = 0`, `KEY_PRIORITY = 0`(실시간), API 30+ `KEY_LOW_LATENCY = 1`
  - `KEY_MAX_FPS_TO_ENCODER = 60`: A16 화면은 90Hz라 그대로 두면 설정보다 많이 들어간다
- **지연이 쌓이지 않게 (v0.1 검토 후 추가):**
  - 세션 소켓의 보내기 버퍼를 약 256KB로 작게 둔다.
  - 꺼내기 스레드가 "지금 시각 − 화면 캡처 시각"(또는 쓰기가 막힌 시간)을 잰다.
  - 150ms를 넘으면 키프레임이 아닌 장은 버리고 키프레임을 요청한 뒤, 다음 키프레임부터 다시 보낸다.
  - Wi-Fi가 느릴 때 화면이 잠깐 멈췄다 따라붙지만, 몇 초씩 밀리지는 않는다.
- 영상 크기: 실제 화면 크기를 긴 변 `maxSize`(기본 1920) 이하로 줄이고, 가로·세로를 16의 배수로 내린다 (비율 유지). `configure`가 실패하면 1280으로 한 번 더 시도한다.
- 꺼내기 스레드: `dequeueOutputBuffer` 반복
  - `BUFFER_FLAG_CODEC_CONFIG` → `VIDEO_CONFIG`(width, height, SPS/PPS)
  - 그 외 → `VIDEO_FRAME` (`VideoFrame.header(pts, key)` + 데이터를 `FrameWriter.write(type, header, data, off, len)`로 복사 없이 전송). 버퍼 배열은 재사용한다.
- `REQUEST_KEYFRAME` → `setParameters(Bundle().apply { putInt(PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })`
- `VIDEO_SETTINGS`(bitrate, maxSize) → 인코더를 새 설정으로 다시 만든다 (새 VIDEO_CONFIG가 나간다).
- 회전 등으로 화면 크기가 바뀌면 (DisplayListener / `onCapturedContentResize`) → `SCREEN` 전송 + 인코더 재생성 + `resize`.
- 세션 시작 시 `SCREEN`(width, height = 실제 화면 픽셀) 전송.

### 2.2 노트북: 해독과 표시
- FFmpeg(`org.bytedeco.ffmpeg`)의 avcodec을 직접 호출한다 (JavaCV의 FrameGrabber는 버퍼링 지연이 있어 쓰지 않음).
  - `avcodec_find_decoder(AV_CODEC_ID_H264)`, `flags |= AV_CODEC_FLAG_LOW_DELAY`, `flags2 |= AV_CODEC_FLAG2_FAST`, `thread_count = 1`(프레임 스레딩은 지연을 늘림)
  - `VIDEO_CONFIG`를 받으면 해독기를 새로 만들고, SPS/PPS를 **다음 프레임 데이터 앞에 붙여서** 보낸다 (Annex-B 그대로)
  - `avcodec_send_packet` → `avcodec_receive_frame` 반복
  - 해독 오류가 나면 `REQUEST_KEYFRAME`을 보내고, 다음 키프레임까지 프레임을 버린다
- 색 변환: `sws_getCachedContext`로 해독 결과 → BGRA. 버퍼는 재사용한다.
- 표시: Compose 창 안에 비율을 유지해 맞춰 그린다(남는 곳은 검은 여백). 프레임마다 객체를 새로 만들어 메모리가 새지 않게 한다 (Skia 비트맵 재사용 / 이전 이미지 close).
- 해독은 받는 스레드에서 바로 한다 (H.264 소프트웨어 해독은 장당 수 ms). 화면 갱신은 최신 프레임만 그린다.
- 표시할 것: 연결 상태, 화면 fps(해독), 받는 속도(kbps), RTT(ms), 영상 해상도

---

## 3. 조작 (노트북 → 폰)

좌표는 항상 **영상 화면 기준 0~1 비율**이다.

영상 크기를 16의 배수로 맞추면 비율이 폰 화면과 최대 약 1% 달라진다. 이때 폰 화면은 영상 안에 가운데 맞춤으로 들어가고 가장자리에 얇은 여백이 생긴다. 그래서 폰은 받은 좌표를 이 여백을 빼고 화면 비율로 바꾼 뒤 실제 화면 크기를 곱한다.
- `scale = min(영상폭/화면폭, 영상높이/화면높이)`
- 여백 `ox = (영상폭 − 화면폭×scale)/2`, `oy = (영상높이 − 화면높이×scale)/2`
- 화면 비율 `sx = (x×영상폭 − ox)/(화면폭×scale)`, `sy`도 같은 방식, 0~1로 자른다

NaN이나 무한대 좌표는 버린다.

| 노트북 입력 | 메시지 | 폰 처리 |
|---|---|---|
| 왼쪽 버튼 누름/이동/뗌 | `TOUCH` DOWN/MOVE/UP (+ 시각 ms) | `GestureStreamer.down/move/up` |
| 마우스 휠 (60ms씩 모아서) | `SCROLL`(칸 수, + = 아래로) | `GestureStreamer.scroll` |
| 오른쪽 클릭 | `KEY` BACK | `performGlobalAction` |
| 가운데 클릭 | `KEY` HOME | |
| 버튼: 뒤로, 홈, 최근 앱, 알림창 | `KEY` | |
| 글자 입력칸 + Enter | `TEXT` | `ACTION_SET_TEXT` (커서 있는 입력란 끝에 붙임) |

- 노트북: 이동은 Compose의 historical 포인트까지 모두 보낸다. 영상 영역 밖에서 놓거나 창 포커스를 잃으면 UP을 보낸다. 드래그 중의 휠은 보내지 않는다. 두 번째 버튼이나 손가락은 무시한다.
- 폰: 0단계 앱의 `GestureStreamer.kt`와 접근성 서비스(`ProbeAccessibilityService.kt`)를 그대로 옮겨 쓴다 (이름만 `LinkyAccessibilityService`). 실시간 드래그, 관성 제거, 교착 방지가 이미 들어 있고 검증되었다.
- 접근성 서비스가 꺼져 있으면 폰은 DOWN, KEY, TEXT를 받을 때 NOTICE("폰에서 Linky 접근성 권한이 꺼져 있습니다")를 보낸다. MOVE마다 보내지는 않는다.

---

## 4. 폰 찾기 (mDNS)

- 폰: `NsdManager.registerService`. 서비스 이름 "Linky-<기기 이름>", 타입 `_linky._tcp`, 포트 47180. TXT 항목:
  - `fp` = `Pairing.shortId(폰 지문)`
  - `name` = 기기 이름
  - `v` = 프로토콜 버전

  공유를 멈추면 등록을 해제한다.
- 노트북: JmDNS를 **사이트 로컬 IPv4 주소(192.168.x, 10.x, 172.16~31.x)마다 하나씩** 만든다 (VPN 등 어댑터가 여러 개라 기본값으로는 Wi-Fi를 못 잡을 수 있음).
  - `_linky._tcp.local.`을 듣고, 해석되면 (이름, IPv4, 포트, fp)로 목록에 넣는다. 사라지면 뺀다.
- 노트북 목록에는 `fp`가 신뢰 목록의 지문 앞부분과 같으면 "페어링됨"으로 표시한다. **페어링된 폰이 하나만 보이면 자동으로 연결한다.**
- mDNS가 막힌 Wi-Fi를 위해 "IP 직접 입력"(포트는 47180 고정)을 둔다. 폰 앱 화면에도 자기 IP를 표시한다.

---

## 5. 화면 (UI) 요구사항 — 모두 한국어

### 폰 앱
- **상태:** 접근성 ✅/❌, 화면 공유 중 여부, 연결된 노트북 이름, 보내는 fps, 폰 IP
- **1. 조작 권한:** [접근성 설정 열기], [앱 정보 열기 (제한된 설정 허용)]
- **2. 화면 공유:** [화면 공유 시작] / [중지]
- **3. 노트북 연결:** [새 노트북 연결] → 2분 동안 페어링 창 열림 (남은 시간 표시), 페어링된 노트북 목록 (각각 [삭제])
- **페어링 확인:** 6자리 숫자를 큰 글씨로, [일치] [다름]
- **로그:** 최근 40줄
- 알림:
  - 포그라운드 서비스 "Linky 화면 공유 중" ([중지] 버튼)
  - 페어링 요청 (숫자 + [일치] [다름])

### 노트북 앱
- **왼쪽(또는 위) 패널:** 찾은 폰 목록 (페어링됨/새 기기, [연결]), [IP 직접 입력], 페어링 삭제
- **가운데:** 폰 화면 (마우스 조작). 연결 전에는 안내 문구:
  > 폰의 Linky 앱에서 [화면 공유 시작]을 누르세요. 처음이면 [새 노트북 연결]도 누르세요.
- **위 도구줄:** 뒤로, 홈, 최근 앱, 알림창, 화질(낮음 4Mbps/1280, 보통 8Mbps/1920, 높음 16Mbps/원본), [연결 끊기]
- **아래:** 글자 입력칸(Enter로 전송), 상태(fps, kbps, RTT, 해상도)
- **페어링 대화상자:** 6자리 숫자를 큰 글씨로, [일치] [다름]
- **안내(NOTICE)와 오류:** 화면 위쪽 알림 띠
- **로그:** 접을 수 있는 패널

---

## 6. 스레드 규칙

- 폰:
  - 접속 받기 스레드 1개
  - 연결마다 읽기 스레드 1개
  - 인코더 꺼내기 스레드 1개
  - VirtualDisplay 조작은 capture HandlerThread에서
  - 제스처는 GestureStreamer의 HandlerThread에서 (이미 그렇게 되어 있음)
  - UI 갱신은 메인 스레드
- 노트북:
  - 연결마다 읽기+해독 스레드 1개
  - PING 타이머
  - UI 상태는 Compose 메인(Swing) 스레드에서만 바꾼다
- 공유 `FrameWriter`는 동시 쓰기에 안전하다. `FrameReader`는 한 스레드에서만 쓴다.

## 7. v0.1에서 하지 않는 것
소리, 클립보드, 파일 전송, 키보드 앱(IME), 하드웨어 해독(d3d11va), 여러 폰 동시 연결.

## 8. 빌드
- 폰: `android`에서 `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`로 맞추고 `.\gradlew.bat assembleDebug`
- 노트북: `desktop`에서 `.\gradlew.bat run` / `.\gradlew.bat createDistributable`
- 폰 설치: Play 프로텍트 때문에 adb로 설치한다 (`android/install.ps1`, errors E-005).
