package dev.linky.desktop.net

/** 연결 단계 (화면 표시용) */
enum class ConnPhase { IDLE, CONNECTING, AUTH, PAIRING, SESSION }

/** 연결이 알려주는 일. 받기/보내기 스레드에서 불리므로 UI 상태는 받는 쪽이 UI 스레드로 넘겨서 바꾼다. */
interface ConnectionListener {
    fun log(text: String)
    fun phase(phase: ConnPhase, phoneName: String)
    /** 폰의 안내(NOTICE). 이미 정리하고 길이를 줄인 문구다. */
    fun notice(text: String)
    /** 페어링 숫자. phoneId: TLS로 받은 폰 인증서의 짧은 지문, warning: 페어링한 폰과 이름·주소가 같을 때의 경고 */
    fun pairingCode(code: String, phoneId: String, warning: String?)
    fun trustChanged()
    fun videoSize(width: Int, height: Int)
    fun stats(fps: Float, kbps: Long)
    fun rtt(ms: Long)
    /**
     * 연결이 끝남. message가 null이면 사용자가 끊은 것.
     * retryable: 안내 없이 네트워크 문제로 끊김 (자동으로 다시 연결해 볼 만함)
     */
    fun closed(message: String?, error: Boolean, retryable: Boolean)
}

/** 사용자에게 그대로 보여줄 문구를 담은 연결 오류 */
class LinkyException(message: String) : Exception(message)
