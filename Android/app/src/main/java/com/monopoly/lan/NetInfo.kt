package com.monopoly.lan

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * 局域网那点事：拿本机 IP、广播自己的房间、扫别人的房间。
 * 广播包很小：{"app":"monopoly","name":"谁的房间","port":9000}
 */
object NetInfo {

    const val DISCOVER_PORT = 9009

    class Room(val name: String, val host: String, val port: Int)

    /** 流量网卡：rmnet（高通）/ ccmni（联发科）/ clat（IPv4 兼容层）。 */
    private fun isCellular(name: String): Boolean =
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("clat") ||
            name.startsWith("v4-") || name.startsWith("pdp")

    private fun isP2p(name: String): Boolean = name.startsWith("p2p")

    /** 扫全部网卡，挑出第一个符合条件的地址。 */
    private inline fun pick(predicate: (String, String) -> Boolean): String {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = (nif.name ?: "").lowercase()
                for (addr in nif.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val h = addr.hostAddress ?: continue
                    if (predicate(name, h)) return h.substringBefore('%')
                }
            }
        } catch (e: Exception) {
            // 拿不到就算了
        }
        return ""
    }

    /** 本机 Wi-Fi / 以太网 IPv4（局域网房间只认这个，流量网卡不算）。取不到返回空串。 */
    fun ipAddress(): String = pick { name, h ->
        !isCellular(name) && !isP2p(name) && h.contains('.')
    }

    /** 是不是连着 Wi-Fi / 有线网（局域网房间的前提）。 */
    fun hasLan(): Boolean = ipAddress().isNotEmpty()

    class Advertiser(private val context: Context, private val name: String, private val port: Int) {
        @Volatile
        private var running = false
        private var thread: Thread? = null
        private var lock: WifiManager.MulticastLock? = null

        fun start() {
            if (running) return
            running = true
            lock = try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wm?.createMulticastLock("monopoly-advert")?.apply { setReferenceCounted(false); acquire() }
            } catch (e: Exception) {
                null
            }
            thread = Thread({
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket()
                    socket.broadcast = true
                    val payload = JSONObject()
                        .put("app", "monopoly")
                        .put("name", name)
                        .put("port", port)
                        .toString().toByteArray(Charsets.UTF_8)
                    val target = InetAddress.getByName("255.255.255.255")
                    while (running) {
                        try {
                            socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(target, DISCOVER_PORT)))
                        } catch (e: Exception) {
                            // 发不出去就下一轮再来
                        }
                        Thread.sleep(2500)
                    }
                } catch (e: Exception) {
                    // 网络没开
                } finally {
                    try {
                        socket?.close()
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }, "monopoly-advert").also { it.isDaemon = true; it.start() }
        }

        fun stop() {
            running = false
            try {
                thread?.join(500)
            } catch (e: Exception) {
                // ignore
            }
            thread = null
            try {
                lock?.release()
            } catch (e: Exception) {
                // ignore
            }
            lock = null
        }
    }

    /** 扫 5 秒内广播出来的房间（同一网段）。 */
    fun scan(timeoutMs: Long = 4000): List<Room> {
        val found = LinkedHashMap<String, Room>()
        val deadline = System.currentTimeMillis() + timeoutMs
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(DISCOVER_PORT).apply { broadcast = true }
            val buf = ByteArray(1024)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                val left = (deadline - System.currentTimeMillis()).coerceAtLeast(200)
                socket.soTimeout = left.toInt()
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val o = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    null
                } ?: continue
                if (o.optString("app") != "monopoly") continue
                val host = packet.address?.hostAddress ?: continue
                val p = o.optInt("port", ServerHub.DEFAULT_PORT)
                if (p <= 0) continue
                val name = o.optString("name", "大富翁房间")
                found[host] = Room(name, host, p)
            }
        } catch (e: Exception) {
            // 端口被占 / 没权限
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
        return found.values.toList()
    }
}
