package dev.linky.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.Certificate

/**
 * 페어링 계산 (블루투스의 "숫자 비교" 방식과 같은 원리).
 *
 * 1. 노트북 → 폰: 약속값 = SHA-256(노트북 난수)   (난수 자체는 아직 숨김)
 * 2. 폰 → 노트북: 폰 난수
 * 3. 노트북 → 폰: 노트북 난수 공개 → 폰이 약속값과 맞는지 확인
 * 4. 양쪽이 SAS = (폰 인증서 지문, 노트북 인증서 지문, 두 난수)로 6자리 숫자를 계산해 화면에 보여준다.
 *    사용자가 두 화면의 숫자가 같은 것을 양쪽에서 확인하면 페어링 완료.
 *
 * 중간에 누가 끼어들면 양쪽이 보는 인증서 지문이 달라 숫자가 달라진다.
 * 끼어든 쪽은 난수를 미리 정해야(약속) 하므로 숫자를 맞추려고 여러 번 시도할 수 없다.
 */
object Pairing {
    private val random = SecureRandom()

    fun nonce(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun commit(nonce: ByteArray): ByteArray = sha256("linky-pair-commit-v1".toByteArray(), nonce)

    fun verifyCommit(commit: ByteArray, nonce: ByteArray): Boolean =
        MessageDigest.isEqual(commit, commit(nonce))

    /** 양쪽 화면에 보여줄 6자리 숫자. */
    fun sas(phoneFp: ByteArray, desktopFp: ByteArray, phoneNonce: ByteArray, desktopNonce: ByteArray): String {
        val h = sha256("linky-sas-v1".toByteArray(), phoneFp, desktopFp, phoneNonce, desktopNonce)
        val v = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return (v % 1_000_000).toString().padStart(6, '0')
    }

    /** 인증서 지문 = SHA-256(인증서 DER). 페어링한 상대를 기억하는 데 쓴다. */
    fun fingerprint(cert: Certificate): ByteArray = sha256(cert.encoded)

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** 화면 표시와 mDNS용 짧은 지문 (앞 16글자) */
    fun shortId(fp: ByteArray): String = hex(fp).take(16)

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }
}
