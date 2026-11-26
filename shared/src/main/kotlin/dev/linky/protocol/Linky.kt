package dev.linky.protocol

/**
 * 폰 앱과 노트북 앱이 같이 쓰는 통신 규칙.
 * 이 폴더(shared)는 두 앱이 똑같이 컴파일한다. 바꾸면 두 앱을 모두 다시 빌드해야 한다.
 * 자세한 설명: docs/linky-design.md
 */
object Linky {
    /** 통신 규칙 버전. 서로 다르면 연결을 거절한다. */
    const val PROTOCOL_VERSION = 1

    /** 폰 앱이 기다리는 TCP 포트 */
    const val PORT = 47180

    /** 같은 Wi-Fi에서 폰을 찾을 때 쓰는 mDNS 서비스 이름 */
    const val SERVICE_TYPE = "_linky._tcp"

    /** mDNS TXT에 넣는 폰 인증서 지문 앞부분 (페어링 여부를 연결 전에 알기 위해) */
    const val TXT_FINGERPRINT = "fp"
    const val TXT_NAME = "name"
    const val TXT_VERSION = "v"

    /** 프레임 하나의 최대 크기 (영상 한 장이 이보다 클 일은 없다) */
    const val MAX_FRAME = 16 * 1024 * 1024

    /** 인증(페어링 확인) 전 프레임의 최대 크기. HELLO·페어링·PING만 오가므로 작게 둔다. */
    const val MAX_PREAUTH_FRAME = 8 * 1024
}

/** 프레임 종류. 각 프레임은 [길이 u32][종류 u8][내용]. 숫자는 모두 빅엔디언. */
object Msg {
    // ---- 폰 → 노트북 ----
    /**
     * CLIENT_HELLO를 받은 "뒤에" 보낸다 (그 순간의 판단을 담기 위해).
     * Props: name, model, sdk, version, id(폰 지문 앞 16글자, 화면 표시용),
     *        trusted(이 노트북을 이미 페어링했는지 "1"/"0"), pairing(이 연결로 페어링할 수 있는지 "1"/"0")
     */
    const val HELLO = 1
    /** 32바이트 폰 난수 */
    const val PAIR_NONCE = 3
    /** 1바이트: 1 = 양쪽 모두 확인, 페어링 완료 / 0 = 거절 */
    const val PAIR_RESULT = 5
    /** Props: width, height (폰 화면 픽셀, 회전 반영) */
    const val SCREEN = 10
    /** VideoConfig: 영상 크기와 SPS/PPS */
    const val VIDEO_CONFIG = 11
    /** VideoFrame: pts, 키프레임 여부, H.264 데이터(Annex-B) */
    const val VIDEO_FRAME = 12
    /** UTF-8 안내 문구 (노트북 화면에 보여준다) */
    const val NOTICE = 20
    const val PONG = 31

    // ---- 노트북 → 폰 ----
    /**
     * TLS 연결 직후 바로 보낸다.
     * Props: name, version, trusted(이 폰을 이미 페어링했는지 "1"/"0"),
     *        bitrate, maxSize(처음 영상 설정. 없으면 폰 기본값. 세션 시작 때 인코더를 두 번 만들지 않기 위해)
     */
    const val CLIENT_HELLO = 2
    /** 32바이트: SHA-256(노트북 난수) 약속값 */
    const val PAIR_COMMIT = 4
    /** 32바이트 노트북 난수 공개 */
    const val PAIR_REVEAL = 6
    /** 1바이트: 노트북 사용자가 숫자 일치를 확인함(1) / 거절(0) */
    const val PAIR_CONFIRM = 7
    /** 8바이트: 보낸 시각(ms). 폰은 그대로 PONG으로 돌려준다 */
    const val PING = 30
    /** Touch */
    const val TOUCH = 40
    /** Scroll */
    const val SCROLL = 41
    /** 1바이트 Keys 값 */
    const val KEY = 42
    /** UTF-8 글자 입력 */
    const val TEXT = 43
    /** 내용 없음. 영상이 깨졌을 때 키프레임을 다시 요청 */
    const val REQUEST_KEYFRAME = 44
    /** Props: bitrate(bps), maxSize(긴 변 픽셀) */
    const val VIDEO_SETTINGS = 45
}

/** KEY 메시지 값 */
object Keys {
    const val BACK = 1
    const val HOME = 2
    const val RECENTS = 3
    const val NOTIFICATIONS = 4
}
