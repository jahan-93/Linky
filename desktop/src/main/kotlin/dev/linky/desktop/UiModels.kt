package dev.linky.desktop

/** 화질 (설계서 5장 도구줄). 보통이 폰의 기본값과 같다. */
enum class Quality(val label: String, val detail: String, val bitrate: Int, val maxSize: Int) {
    LOW("낮음", "4 Mbps · 1280", 4_000_000, 1280),
    NORMAL("보통", "8 Mbps · 1920", 8_000_000, 1920),
    HIGH("높음", "16 Mbps · 원본", 16_000_000, 4096),
}

/** 화면 위쪽 알림 띠 */
data class Banner(val text: String, val error: Boolean)

/**
 * 페어링 숫자 대화상자. 숫자와 함께 양쪽 ID도 보여줘 폰 화면과 비교하게 한다.
 * phoneId: TLS로 받은 폰 인증서의 짧은 지문, laptopId: 이 노트북의 짧은 지문, answered: 노트북에서 이미 답함
 */
data class SasPrompt(
    val code: String,
    val phoneName: String,
    val phoneId: String,
    val laptopId: String,
    /** 페어링한 폰과 이름·주소가 같은데 ID가 다를 때의 경고 */
    val warning: String? = null,
    val answered: Boolean = false,
)

/** 상태줄 숫자 */
data class Stats(val fps: Float = 0f, val kbps: Long = 0, val rttMs: Long? = null)
