package dev.linky.phone

import android.content.Context

/**
 * 페어링한 노트북 목록. 키 = 노트북 인증서 지문 hex, 값 = 노트북 이름 (설계서 1.5).
 * 연결 스레드와 화면이 동시에 고칠 수 있어 모든 변경을 한 잠금으로 묶는다.
 */
class TrustStore(context: Context) {
    private val prefs = context.getSharedPreferences("trusted", Context.MODE_PRIVATE)

    fun contains(fp: String): Boolean = prefs.contains(fp)

    /** 페어링 성공 때만 쓴다. */
    fun add(fp: String, name: String) = synchronized(LOCK) {
        if (prefs.getString(fp, null) != name) prefs.edit().putString(fp, name).apply()
    }

    /** 이미 페어링한 노트북의 이름만 바꾼다. 그사이 삭제했으면 되살리지 않는다. */
    fun rename(fp: String, name: String) = synchronized(LOCK) {
        if (prefs.contains(fp) && prefs.getString(fp, null) != name) prefs.edit().putString(fp, name).apply()
    }

    fun remove(fp: String) = synchronized(LOCK) { prefs.edit().remove(fp).apply() }

    fun all(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    private companion object {
        val LOCK = Any()
    }
}
