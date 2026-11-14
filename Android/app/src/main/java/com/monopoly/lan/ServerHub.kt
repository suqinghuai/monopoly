package com.monopoly.lan

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 把 Game 装到 HttpServer 上，并把内置的 www/index.html 一起发出去。
 * 手机既是服务端也是网页宿主：别人用浏览器打开 http://手机IP:9000/ 就能玩。
 */
class ServerHub(private val assets: AssetManager, private val brain: AiBrain) {

    val game = Game(brain)

    @Volatile
    var port: Int = 0
        private set

    private var server: HttpServer? = null
    private var page: ByteArray? = null

    fun start(preferredPort: Int = DEFAULT_PORT): Int {
        stop()
        page = readAsset("www/index.html")
        var lastError: Exception? = null
        // 9000 被占了就往下试几个，最后让系统随便给一个，别出现「点了没反应」
        for (p in intArrayOf(preferredPort, DEFAULT_PORT, 9001, 9002, 9003, 9004, 0)) {
            val s = HttpServer(p)
            s.router = { req, res -> route(req, res) }
            try {
                val actual = s.start()
                server = s
                port = actual
                return actual
            } catch (e: Exception) {
                lastError = e
                HttpServer.stopQuietly(s)
            }
        }
        throw IOException(lastError?.message ?: "端口都占用了，起不来")
    }

    fun stop() {
        HttpServer.stopQuietly(server)
        server = null
        port = 0
    }

    val isRunning: Boolean get() = server?.isRunning == true

    // ---------------------------------------------------------------- 路由
    private fun route(req: HttpServer.Request, res: HttpServer.Response) {
        try {
            if (req.method == "GET") get(req, res) else post(req, res)
        } catch (e: IllegalStateException) {
            res.error(e.message ?: "操作失败", 409)
        } catch (e: IllegalArgumentException) {
            res.error(e.message ?: "请求格式不对", 400)
        } catch (e: Exception) {
            res.error(e.message ?: "服务端出错", 500)
        }
    }

    private fun get(req: HttpServer.Request, res: HttpServer.Response) {
        val token = req.q("token")
        when (req.path) {
            "/", "/index.html", "/index.htm" -> {
                val bytes = page ?: readAsset("www/index.html")
                if (bytes == null) res.error("页面没打包进来", 500) else res.html(bytes)
            }
            "/api/lobby" -> {
                game.lock.lock()
                try {
                    res.json(JSONObject()
                        .put("phase", game.phase)
                        .put("paused", game.frozenSnap != null)
                        .put("chars", charsJson())
                        .put("players", JSONArray().apply {
                            for (p in game.players) {
                                put(JSONObject().put("name", p.name).put("char", p.char).put("kind", p.kind))
                            }
                        })
                        .put("taken", JSONArray().apply { for (c in game.usedChars()) put(c) })
                        .put("settings", JSONObject()
                            .put("startMoney", game.startMoney)
                            .put("botLevel", game.botLevel)
                            .put("moneyChoices", Json.arrInts(Rules.MONEY_CHOICES.toList()))
                            .put("moneyMin", Rules.MONEY_MIN)
                            .put("moneyMax", Rules.MONEY_MAX)
                            .put("botLevels", JSONObject().apply {
                                for ((k, v) in Rules.BOT_LEVELS) put(k, v.name)
                            })
                            .put("maxPlayers", Rules.MAX_PLAYERS))
                    )
                } finally {
                    game.lock.unlock()
                }
            }
            "/api/rules" -> res.json(Rules.rulesPayload())
            "/api/seats" -> res.json(JSONObject()
                .put("phase", game.phase).put("seats", game.seatList()).put("admin", game.isAdmin(token)))
            "/api/saves" -> {
                val cur: Any = game.gameId ?: JSONObject.NULL
                res.json(JSONObject().put("saves", Store.listGames()).put("current", cur))
            }
            "/api/state" -> {
                if (!game.canView(token)) res.error("登录状态失效", 401)
                else res.json(game.view(game.pidOf(token), token))
            }
            "/api/events" -> events(req, token, res)
            "/api/ai_config" -> res.json(brain.public())
            else -> res.error("没有这个地址", 404)
        }
    }

    private fun charsJson(): JSONArray {
        val arr = JSONArray()
        for (c in Rules.CHARS) {
            arr.put(JSONObject().put("id", c.id).put("name", c.name).put("glyph", c.glyph)
                .put("color", c.color).put("ink", c.ink).put("desc", c.desc))
        }
        return arr
    }

    private fun events(req: HttpServer.Request, token: String, res: HttpServer.Response) {
        if (!game.canView(token)) {
            res.error("登录状态失效", 401)
            return
        }
        val pid = game.pidOf(token)
        val out = res.beginSse()
        var last = -1
        while (true) {
            if (!(server?.isRunning ?: false)) return
            var payload: JSONObject? = null
            game.lock.lock()
            try {
                var wait = TimeUnit.SECONDS.toNanos(15)
                while (game.version == last && wait > 0) wait = game.cond.awaitNanos(wait)
                if (game.version != last) {
                    last = game.version
                    payload = game.view(pid, token)
                }
            } finally {
                game.lock.unlock()
            }
            try {
                if (payload == null) out.write(": ping\n\n".toByteArray()) else
                    out.write(("data: " + payload + "\n\n").toByteArray())
                out.flush()
            } catch (e: Exception) {
                return
            }
        }
    }

    private fun post(req: HttpServer.Request, res: HttpServer.Response) {
        val token = req.q("token")
        val payload = req.jsonBody()
        when (req.path) {
            "/api/watch" -> res.json(JSONObject().put("token", game.watch()).put("watching", true))
            "/api/join" -> {
                val (t, pid) = game.join(payload.optString("name", ""), payload.optString("char", ""))
                res.json(JSONObject().put("token", t).put("pid", pid))
            }
            "/api/claim" -> {
                val pid = payload.optInt("pid", 0)
                res.json(JSONObject().put("token", game.claimSeat(pid)).put("pid", pid))
            }
            "/api/load" -> {
                val gid = payload.optLong("id", 0L)
                val seats = game.loadFromDb(gid)
                res.json(JSONObject().put("ok", true).put("seats", seats).put("phase", game.phase))
            }
            "/api/action" -> {
                game.act(token, payload.optString("action", ""), payload)
                res.json(JSONObject().put("ok", true))
            }
            "/api/chat" -> {
                game.chat(token, payload.optString("text", ""))
                res.json(JSONObject().put("ok", true))
            }
            "/api/leave" -> {
                game.leave(token)
                res.json(JSONObject().put("ok", true))
            }
            "/api/ai_config" -> res.json(brain.configure(
                payload.optString("baseUrl", ""),
                payload.optString("model", ""),
                payload.optString("apiKey", ""),
                payload.opt("timeout"),
            ))
            else -> res.error("没有这个地址", 404)
        }
    }

    private fun readAsset(path: String): ByteArray? = try {
        assets.open(path).use { it.readBytes() }
    } catch (e: IOException) {
        null
    }

    companion object {
        const val DEFAULT_PORT = 9000
    }
}
