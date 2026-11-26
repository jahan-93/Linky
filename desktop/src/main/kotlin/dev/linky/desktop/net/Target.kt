package dev.linky.desktop.net

/**
 * 연결할 곳.
 * @param advertisedFp mDNS TXT로 알린 폰 지문 앞부분 (직접 입력이면 null)
 * @param key mDNS 목록의 항목 키 (직접 입력이면 null)
 */
data class Target(
    val host: String,
    val port: Int,
    val label: String,
    val advertisedFp: String? = null,
    val key: String? = null,
)

/** mDNS로 찾은 폰 하나. */
data class FoundPhone(
    val key: String,
    val name: String,
    val host: String,
    val port: Int,
    val fp: String?,
    val version: String?,
) {
    fun toTarget() = Target(host, port, name, fp, key)
}
