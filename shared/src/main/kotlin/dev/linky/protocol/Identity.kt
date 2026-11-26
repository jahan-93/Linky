package dev.linky.protocol

import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

/**
 * 이 기기의 신원: EC P-256 키와 자체 서명 인증서.
 * 처음 실행할 때 만들어 파일(PKCS12)에 저장하고, 이후에는 같은 것을 쓴다.
 * 상대는 이 인증서의 지문을 기억해서 "페어링한 그 기기"인지 확인한다.
 *
 * BouncyCastle은 인증서를 "만드는" 데만 쓰고, 보안 공급자(Provider)로 등록하지 않는다.
 * (안드로이드에는 이미 "BC"라는 이름의 내장 공급자가 있어서 등록하면 충돌한다)
 */
class Identity private constructor(
    val keyStore: KeyStore,
    val certificate: X509Certificate,
    /**
     * 저장된 신원 파일을 읽지 못해 새로 만들었다면 그 이유. 정상이면 null.
     * 새 신원은 지문이 달라서 기존 페어링이 모두 끊기므로, 앱은 이 값을 사용자에게 알려야 한다.
     * 읽지 못한 파일은 지우지 않고 "<이름>.broken-<시각>"으로 옆에 남겨 둔다.
     */
    val recoveredFrom: String? = null,
) {
    val fingerprint: ByteArray = Pairing.fingerprint(certificate)

    companion object {
        const val ALIAS = "linky"
        /** 파일은 앱 전용 폴더에 저장되며, 이 비밀번호는 형식상 필요해서 쓰는 고정값이다. */
        val PASSWORD = "linky-identity".toCharArray()

        fun loadOrCreate(file: File, commonName: String): Identity {
            var problem: String? = null
            if (file.exists()) {
                try {
                    return load(file)
                } catch (e: Exception) {
                    problem = e.message ?: e.javaClass.simpleName
                    val broken = File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}")
                    if (!file.renameTo(broken)) file.delete()
                }
            }
            val created = create(commonName, problem)
            save(created, file)
            // 저장한 파일을 다시 읽어서, 이 기기에서 저장·읽기가 실제로 되는지 바로 확인한다
            load(file)
            return created
        }

        /** 임시 파일에 다 쓴 뒤 한 번에 바꿔치기해서, 중간에 꺼져도 파일이 반쯤 망가지지 않게 한다. */
        private fun save(identity: Identity, file: File) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use {
                identity.keyStore.store(it, PASSWORD)
                it.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun load(file: File): Identity {
            val ks = KeyStore.getInstance("PKCS12")
            FileInputStream(file).use { ks.load(it, PASSWORD) }
            val cert = ks.getCertificate(ALIAS) as X509Certificate
            requireNotNull(ks.getKey(ALIAS, PASSWORD)) { "개인 키 없음" }
            return Identity(ks, cert)
        }

        private fun create(commonName: String, recoveredFrom: String?): Identity {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()

            val name = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, commonName.ifBlank { "Linky" }).build()
            val now = System.currentTimeMillis()
            val day = 24L * 60 * 60 * 1000
            val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
            val builder = JcaX509v3CertificateBuilder(
                name, serial, Date(now - day), Date(now + 20 * 365 * day), name, kp.public,
            )
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, kp.private, PASSWORD, arrayOf(cert))
            return Identity(ks, cert, recoveredFrom)
        }
    }
}
