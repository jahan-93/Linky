package dev.linky.desktop.net

import dev.linky.protocol.Linky
import dev.linky.protocol.Props
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlin.concurrent.thread

/**
 * 같은 Wi-Fi의 폰 찾기 (설계서 4장).
 * VPN 등 어댑터가 여러 개일 수 있어 사이트 로컬 IPv4 주소마다 JmDNS를 하나씩 만든다.
 * 10초마다 주소를 다시 보고, 새 주소는 시작하고 사라진 주소는 닫는다.
 */
class Discovery(
    private val onChange: (List<FoundPhone>) -> Unit,
    private val log: (String) -> Unit,
) {
    private val type = Linky.SERVICE_TYPE + ".local."
    private val instances = ConcurrentHashMap<InetAddress, JmDNS>()
    private val failed = HashSet<InetAddress>()
    private var hadAddress = true
    private var lastError: String? = null

    /** 서비스 이름 → (찾은 주소별 항목). 여러 JmDNS가 같은 폰을 볼 수 있다. */
    private val seen = HashMap<String, HashMap<InetAddress, FoundPhone>>()

    private val scanner = Executors.newSingleThreadScheduledExecutor { Thread(it, "Linky-mDNS").apply { isDaemon = true } }

    fun start() {
        scanner.scheduleWithFixedDelay(::rescan, 0, 10, TimeUnit.SECONDS)
    }

    /** 모든 JmDNS를 닫는다 (느릴 수 있으니 UI 스레드에서 부르지 말 것). */
    fun close() {
        scanner.shutdownNow()
        scanner.awaitTermination(2, TimeUnit.SECONDS)
        val all = synchronized(seen) { instances.values.toList().also { instances.clear() } }
        all.map { dns -> thread(isDaemon = true, name = "Linky-mDNS-닫기") { runCatching { dns.close() } } }
            .forEach { it.join(3000) }
    }

    /** 오류가 나도 밖으로 던지지 않는다. 던지면 다음 주기가 다시는 돌지 않는다. */
    private fun rescan() {
        try {
            val now = siteLocalAddresses()
            for (addr in instances.keys - now) {
                stop(addr)
                log("mDNS 중지: ${addr.hostAddress} (주소가 사라짐)")
            }
            for (addr in now - instances.keys) startOn(addr)
            val hasAddress = now.isNotEmpty()
            if (!hasAddress && hadAddress) log("Wi-Fi 등 사설망 IPv4 주소가 없어 폰을 찾을 수 없습니다. 네트워크 연결을 확인하세요.")
            hadAddress = hasAddress
            lastError = null
        } catch (e: Throwable) {
            val text = "mDNS 오류: ${e.message ?: e.javaClass.simpleName}"
            if (text != lastError) log(text)
            lastError = text
        }
    }

    private fun startOn(addr: InetAddress) {
        try {
            // 이름을 직접 주면 느린 역방향 DNS 조회를 하지 않는다
            val dns = JmDNS.create(addr, "linky-desktop")
            instances[addr] = dns // 듣기 전에 넣어야 첫 알림을 버리지 않는다
            try {
                dns.addServiceListener(type, Listener(addr))
            } catch (e: Exception) {
                instances.remove(addr)
                runCatching { dns.close() }
                throw e
            }
            failed.remove(addr)
            log("mDNS로 폰 찾는 중: ${addr.hostAddress}")
        } catch (e: Exception) {
            if (failed.add(addr)) log("mDNS 시작 실패 (${addr.hostAddress}): ${e.message}")
        }
    }

    /** 목록 잠금 안에서 빼야, 닫히는 중에 늦게 온 알림이 항목을 되살리지 못한다. */
    private fun stop(addr: InetAddress) {
        var dns: JmDNS? = null
        update {
            dns = instances.remove(addr)
            seen.values.forEach { it.remove(addr) }
            seen.values.removeAll { it.isEmpty() }
        }
        dns?.let { runCatching { it.close() } }
    }

    private fun siteLocalAddresses(): Set<InetAddress> = NetworkInterface.networkInterfaces().toList()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .flatMap { it.inetAddresses().toList() }
        .filter { it is Inet4Address && it.isSiteLocalAddress }
        .toSet()

    /** 잠금 안에서 바꾸고 알린다. 알림도 잠금 안에서 해야 목록이 뒤바뀐 순서로 전달되지 않는다. */
    private fun update(change: () -> Unit) = synchronized(seen) {
        change()
        onChange(seen.values.mapNotNull { it.values.firstOrNull() }.sortedBy { it.name.lowercase() })
    }

    private inner class Listener(private val source: InetAddress) : ServiceListener {
        /** 이미 닫았거나 새로 만든 JmDNS의 늦은 알림은 버린다. */
        private fun live(event: ServiceEvent) = instances[source] === event.dns

        override fun serviceAdded(event: ServiceEvent) {
            // 해석을 요청만 하고 기다리지 않는다. 결과는 serviceResolved로 온다.
            if (live(event)) event.dns.requestServiceInfo(event.type, event.name, 1L)
        }

        override fun serviceRemoved(event: ServiceEvent) = update {
            val key = event.name.lowercase()
            if (live(event)) {
                seen[key]?.let {
                    it.remove(source)
                    if (it.isEmpty()) seen.remove(key)
                }
            }
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info ?: return
            val ip = info.inet4Addresses.firstOrNull() ?: return
            val fallback = Props.cleanName(event.name.removePrefix("Linky-"), "폰")
            val phone = FoundPhone(
                key = event.name.lowercase(),
                name = Props.cleanName(info.getPropertyString(Linky.TXT_NAME), fallback),
                host = ip.hostAddress,
                port = info.port.takeIf { it in 1..65535 } ?: Linky.PORT,
                fp = info.getPropertyString(Linky.TXT_FINGERPRINT)?.lowercase()
                    ?.takeIf { fp -> fp.length in 8..64 && fp.all { it in '0'..'9' || it in 'a'..'f' } },
                version = info.getPropertyString(Linky.TXT_VERSION)?.take(8),
            )
            update {
                // 같은 Wi-Fi의 누군가가 항목을 쏟아내도 목록이 끝없이 늘지 않게 한다
                if (live(event) && (phone.key in seen || seen.size < MAX_PHONES)) {
                    seen.getOrPut(phone.key) { HashMap() }[source] = phone
                }
            }
        }
    }

    private companion object {
        const val MAX_PHONES = 20
    }
}
