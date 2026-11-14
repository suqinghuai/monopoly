package com.monopoly.lan

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 极简 HTTP/1.1 服务端（不依赖任何第三方库）。
 *
 * 为什么要自己写：原版是 Python 的 ThreadingHTTPServer，接口就 8 个 GET + 7 个 POST，
 * 自己实现反而更可控 —— SSE 这种「一直挂着的响应」在手机上也更好收拾。
 * 每个连接一个线程，响应头统一 Connection: close（SSE 就靠这个连接不关来推流）。
 */
class HttpServer(private val port: Int) {

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false
    var router: ((Request, Response) -> Unit)? = null

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "monopoly-http").also { it.isDaemon = true }
    }

    /** 返回实际监听端口（port 传 0 时由系统分配，测试用）。 */
    fun start(): Int {
        val ss = ServerSocket()
        ss.reuseAddress = true
        // 只服务同一个局域网，监听全部 IPv4 网卡就够了
        ss.bind(InetSocketAddress("0.0.0.0", port))
        serverSocket = ss
        running = true
        Thread({
            while (running) {
                val client = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (running) continue else break
                }
                if (client == null) break
                pool.execute { serve(client) }
            }
        }, "monopoly-accept").also { it.isDaemon = true }.start()
        return ss.localPort
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // 已经关了
        }
        serverSocket = null
    }

    val isRunning: Boolean get() = running

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30000
            val input = BufferedInputStream(socket.getInputStream())
            val out = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readLine(input) ?: return
            if (requestLine.isBlank()) return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawTarget = parts[1]

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }

            val path = rawTarget.substringBefore('?')
            val queryRaw = rawTarget.substringAfter('?', "")
            val body = readBody(input, headers, socket)

            val request = Request(method, path, parseQuery(queryRaw), headers, body)
            val response = Response(out, socket)
            router?.invoke(request, response) ?: response.error("服务还没准备好", 503)
            if (!response.streaming) {
                out.flush()
            }
        } catch (e: Exception) {
            // 客户端断开 / 超时：这一条连接丢掉即可，不影响服务
        } finally {
            try {
                if (socket.isConnected && !socket.isClosed) socket.close()
            } catch (e: IOException) {
                // ignore
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>, socket: Socket): String {
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun parseQuery(raw: String): Map<String, List<String>> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        for (pair in raw.split("&")) {
            if (pair.isEmpty()) continue
            val k = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val v = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            out.getOrPut(k) { ArrayList() }.add(v)
        }
        return out
    }

    class Request(
        val method: String,
        val path: String,
        val query: Map<String, List<String>>,
        val headers: Map<String, String>,
        val body: String,
    ) {
        fun q(name: String): String = query[name]?.firstOrNull() ?: ""

        fun jsonBody(): JSONObject {
            if (body.isBlank()) return JSONObject()
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                throw IllegalArgumentException("请求格式不对")
            }
        }
    }

    class Response(private val out: BufferedOutputStream, private val socket: Socket) {

        @Volatile
        var streaming: Boolean = false
            private set

        private var sent = false

        private fun send(status: Int, contentType: String, body: ByteArray, extra: Map<String, String>) {
            if (sent) return
            sent = true
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
            sb.append("Content-Type: ").append(contentType).append("\r\n")
            sb.append("Content-Length: ").append(body.size).append("\r\n")
            sb.append("Cache-Control: no-store\r\n")
            sb.append("Access-Control-Allow-Origin: *\r\n")
            for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            out.write(body)
            out.flush()
        }

        fun json(data: JSONObject, code: Int = 200) {
            send(code, "application/json; charset=utf-8", data.toString().toByteArray(Charsets.UTF_8), emptyMap())
        }

        fun html(bytes: ByteArray) {
            send(200, "text/html; charset=utf-8", bytes, emptyMap())
        }

        fun text(body: String, code: Int = 200, contentType: String = "text/plain; charset=utf-8") {
            send(code, contentType, body.toByteArray(Charsets.UTF_8), emptyMap())
        }

        fun error(message: String, code: Int = 400) {
            json(JSONObject().put("error", message), code)
        }

        /** 开始一条 SSE 流：先把头写出去，之后调用方自己 write/flush。 */
        fun beginSse(): OutputStream {
            sent = true
            streaming = true
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Accel-Buffering: no\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.flush()
            try {
                socket.soTimeout = 0
            } catch (e: Exception) {
                // 设不了也无所谓
            }
            return out
        }

        private fun statusText(code: Int): String = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            409 -> "Conflict"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "OK"
        }
    }

    companion object {
        /** 等一小会儿让 accept 线程退出，避免端口还没释放就重启。 */
        fun stopQuietly(server: HttpServer?) {
            try {
                server?.stop()
                TimeUnit.MILLISECONDS.sleep(100)
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
