package dev.linky.desktop.store

import dev.linky.protocol.Identity
import dev.linky.protocol.Tls
import java.io.File
import java.net.InetAddress
import javax.net.ssl.SSLContext

/** 이 노트북: 이름, 신원(인증서), TLS 설정, 페어링한 폰 목록. */
class LocalDevice(val dir: File, val name: String) {
    val trust = TrustStore(File(dir, "trusted.properties"))

    /** 처음 쓸 때 identity.p12를 읽거나 새로 만든다 (여러 스레드에서 불러도 한 번만). */
    val identity: Identity by lazy { Identity.loadOrCreate(File(dir, "identity.p12"), name) }
    val tls: SSLContext by lazy { Tls.context(identity) }

    companion object {
        /** %APPDATA%\Linky, 없으면 ~/.linky */
        fun open(): LocalDevice {
            val dir = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { File(it, "Linky") }
                ?: File(System.getProperty("user.home"), ".linky")
            dir.mkdirs()
            val name = System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
                ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
                ?: "노트북"
            return LocalDevice(dir, name)
        }
    }
}
