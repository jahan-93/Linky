package dev.linky.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.linky.protocol.Linky
import dev.linky.protocol.Pairing

/** 같은 Wi-Fi의 노트북이 폰을 찾을 수 있게 mDNS(_linky._tcp)로 알린다 (설계서 4장). */
class Advertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun register(deviceName: String, fingerprint: ByteArray) {
        val name = dnsName("Linky-$deviceName")
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = Linky.SERVICE_TYPE
            port = Linky.PORT
            setAttribute(Linky.TXT_FINGERPRINT, Pairing.shortId(fingerprint))
            setAttribute(Linky.TXT_NAME, deviceName)
            setAttribute(Linky.TXT_VERSION, Linky.PROTOCOL_VERSION.toString())
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) =
                AppState.log("같은 Wi-Fi에 폰을 알리는 중: ${info.serviceName}")

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                AppState.log("mDNS 알림 실패 (코드 $errorCode). 노트북에서 IP를 직접 입력하세요")

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
        } catch (e: Exception) {
            AppState.log("mDNS 알림 실패: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun unregister() {
        val l = listener ?: return
        listener = null
        runCatching { nsd.unregisterService(l) }
    }

    /** DNS 이름 한 칸은 63바이트까지 */
    private fun dnsName(s: String): String {
        var n = s
        while (n.toByteArray().size > 63) n = n.dropLast(1)
        return n
    }
}
