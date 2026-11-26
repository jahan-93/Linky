package dev.linky.protocol

import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS 설정. 양쪽 모두 자기 인증서를 내민다(상호 인증).
 *
 * 인증서가 "믿을 만한지"는 TLS가 판단하지 않는다(자체 서명이라 판단할 기준이 없음).
 * 대신 연결된 뒤 상대 인증서 지문을 꺼내서
 *  - 페어링한 기억이 있으면 그 지문과 같은지 확인하고
 *  - 처음이면 숫자 비교 페어링(Pairing)으로 확인한다.
 * TLS는 암호화와 "상대가 그 인증서의 개인 키를 가졌는지" 증명을 맡는다.
 */
object Tls {
    fun context(identity: Identity): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(identity.keyStore, Identity.PASSWORD)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf(CaptureTrustManager), SecureRandom())
        return ctx
    }

    /** TLS 1.3/1.2만 쓴다. */
    fun restrictProtocols(socket: SSLSocket) {
        val wanted = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
        if (wanted.isNotEmpty()) socket.enabledProtocols = wanted.toTypedArray()
    }

    /** 핸드셰이크가 끝난 소켓에서 상대 인증서 지문. */
    fun peerFingerprint(socket: SSLSocket): ByteArray =
        Pairing.fingerprint(socket.session.peerCertificates.first())

    /** 검증은 연결 후 지문으로 하므로 여기서는 받아들이기만 한다. (위 설명 참고) */
    private object CaptureTrustManager : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = requireChain(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = requireChain(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = requireChain(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = requireChain(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = requireChain(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = requireChain(chain)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        /**
         * 유효기간은 확인하지 않는다. 지문으로 고정한 자체 서명 인증서라 기간이 의미가 없고,
         * 기기 시계가 하루 이상 틀리면 멀쩡한 연결이 막히기 때문이다.
         */
        private fun requireChain(chain: Array<out X509Certificate>?) {
            if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("인증서 없음")
        }
    }
}
