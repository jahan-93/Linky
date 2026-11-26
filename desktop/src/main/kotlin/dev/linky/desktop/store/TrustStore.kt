package dev.linky.desktop.store

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** 페어링한 폰 하나. host: 마지막으로 세션을 맺은 주소 */
data class TrustedPhone(val fp: String, val name: String, val host: String?) {
    /** 폰 앱 화면에 보이는 ID와 같은 짧은 지문 (Pairing.shortId) */
    val shortId: String get() = fp.take(16)
}

/**
 * 페어링한 폰 목록: trusted.properties.
 * "폰 지문 hex = 폰 이름"과 "폰 지문 hex.host = 마지막 주소"를 저장한다.
 * 받기 스레드와 UI 스레드가 같이 쓰므로 모든 메서드를 동기화한다.
 */
class TrustStore(private val file: File) {
    private val map = LinkedHashMap<String, TrustedPhone>()

    init {
        if (file.exists()) {
            runCatching {
                val p = Properties()
                file.reader(Charsets.UTF_8).use { p.load(it) }
                p.stringPropertyNames().filterNot { it.endsWith(HOST) }.forEach { key ->
                    val fp = key.lowercase()
                    map[fp] = TrustedPhone(fp, p.getProperty(key), p.getProperty(key + HOST))
                }
            }
        }
    }

    @Synchronized
    fun snapshot(): List<TrustedPhone> = map.values.toList()

    @Synchronized
    fun contains(fpHex: String): Boolean = fpHex in map

    /**
     * 강한 신호: 모르는 인증서인데 mDNS로 알린 지문 앞부분이 페어링한 폰과 같다.
     * 폰 인증서가 바뀌었거나 다른 기기가 그 폰인 척하는 경우라, 이 연결로는 페어링하지 않는다.
     */
    @Synchronized
    fun lookalikeByFp(advertisedFp: String?): TrustedPhone? {
        val prefix = advertisedFp?.lowercase()?.takeIf { it.length >= 8 } ?: return null
        return map.values.firstOrNull { it.fp.startsWith(prefix) }
    }

    /**
     * 약한 신호: 이름이나 주소만 같다. 같은 이름의 다른 폰이거나 주소를 이어받은 기기일 수도 있으므로
     * 막지 않고, 사용자가 직접 페어링할 때 경고만 보여준다.
     */
    @Synchronized
    fun lookalikeByNameOrHost(name: String?, host: String?): TrustedPhone? = map.values.firstOrNull { t ->
        (name != null && t.name.equals(name, ignoreCase = true)) || (host != null && t.host == host)
    }

    /** 페어링을 저장하거나 이름·주소를 갱신한다. 바뀐 것이 없으면 파일을 쓰지 않고 false. */
    @Synchronized
    fun put(phone: TrustedPhone): Boolean {
        if (map[phone.fp] == phone) return false
        map[phone.fp] = phone
        save()
        return true
    }

    /** 이미 페어링한 폰의 이름·주소만 갱신한다. 그사이 삭제했으면 되살리지 않는다. */
    @Synchronized
    fun refresh(phone: TrustedPhone): Boolean = phone.fp in map && put(phone)

    @Synchronized
    fun remove(fpHex: String) {
        if (map.remove(fpHex) != null) save()
    }

    private fun save() {
        val p = Properties()
        map.values.forEach { t ->
            p.setProperty(t.fp, t.name)
            t.host?.let { p.setProperty(t.fp + HOST, it) }
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writer(Charsets.UTF_8).use { p.store(it, "Linky 페어링한 폰 (지문 = 이름, 지문.host = 마지막 주소)") }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        const val HOST = ".host"
    }
}
