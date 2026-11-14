package com.monopoly.lan

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * AI 玩家的大脑：走 OpenAI 兼容接口（/chat/completions），只要一段 JSON 计划。
 * 配置存 SharedPreferences，不写文件 —— 手机上更省心。
 */
class AiBrain(context: Context? = null) {

    class PlanResult(val kind: String, val plan: JSONObject?, val error: String)

    private val prefs = context?.applicationContext
        ?.getSharedPreferences("ai_config", Context.MODE_PRIVATE)

    @Volatile
    var baseUrl: String = ""
        private set

    @Volatile
    var model: String = ""
        private set

    @Volatile
    var timeoutSec: Double = 16.0
        private set

    private var apiKey: String = ""

    init {
        if (prefs != null) {
            baseUrl = prefs.getString("baseUrl", "") ?: ""
            model = prefs.getString("model", "") ?: ""
            apiKey = prefs.getString("apiKey", "") ?: ""
            timeoutSec = prefs.getFloat("timeout", 16.0f).toDouble()
        }
    }

    val ok: Boolean get() = baseUrl.isNotEmpty() && model.isNotEmpty() && apiKey.isNotEmpty()

    val reason: String
        get() = when {
            !ok -> "还没填 AI 接口（地址 / 模型 / 密钥）"
            else -> ""
        }

    private fun keyMask(): String {
        if (apiKey.isEmpty()) return ""
        return if (apiKey.length <= 6) "****"
        else apiKey.take(4) + "****" + apiKey.takeLast(4)
    }

    fun public(): JSONObject = JSONObject()
        .put("baseUrl", baseUrl)
        .put("model", model)
        .put("keyMask", keyMask())
        .put("hasKey", apiKey.isNotEmpty())
        .put("ok", ok)
        .put("reason", reason)
        .put("timeout", timeoutSec)

    /** 开始页保存配置：校验格式 → 写 SharedPreferences → 立即生效。 */
    fun configure(
        baseUrlIn: String,
        modelIn: String,
        apiKeyIn: String = "",
        timeoutIn: Any? = null,
    ): JSONObject {
        var base = baseUrlIn.trim().trimEnd('/')
        val m = modelIn.trim()
        if (base.isEmpty() || m.isEmpty()) throw IllegalArgumentException("接口地址和模型都要填")
        if (!(base.startsWith("http://") || base.startsWith("https://"))) {
            throw IllegalArgumentException("接口地址要以 http:// 或 https:// 开头")
        }
        val t = when (timeoutIn) {
            null, JSONObject.NULL -> null
            is Number -> timeoutIn.toDouble()
            is String -> timeoutIn.trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("超时得填数字")
            else -> throw IllegalArgumentException("超时得填数字")
        }
        val timeout = (t ?: 16.0).coerceIn(4.0, 90.0)
        var key = apiKeyIn.trim()
        // 页面里回传的是打码后的值：没改就别覆盖真 key
        if (key.contains("****")) key = apiKey
        if (key.isEmpty()) throw IllegalArgumentException("密钥不能为空")

        baseUrl = base
        model = m
        apiKey = key
        timeoutSec = timeout
        prefs?.edit()?.putString("baseUrl", base)?.putString("model", m)
            ?.putString("apiKey", key)?.putFloat("timeout", timeout.toFloat())?.apply()
        return public()
    }

    fun clampMemory(text: String): String = text.trim().take(Rules.AI_MEM_LIMIT)

    /** 让模型给这一回合的落地计划；解析不出来就把原文带回去让人机接手。 */
    fun decidePlan(context: String, level: String, feedback: String): PlanResult {
        if (!ok) return PlanResult("parse", null, "AI 还没配好")
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.6)
            .put("max_tokens", 400)
            .put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                put(JSONObject().put("role", "user").put("content", userPrompt(context, level, feedback)))
            })
            .put("response_format", JSONObject().put("type", "json_object"))

        val text = try {
            post(body)
        } catch (e: NetException) {
            return PlanResult("net", null, e.message ?: "连不上 AI 接口")
        } catch (e: Exception) {
            return PlanResult("net", null, e.message ?: "调用 AI 接口出错")
        }

        val json = extractJson(text) ?: return PlanResult("parse", null, "模型没返回 JSON：${text.take(80)}")
        return PlanResult("parse", json, "")
    }

    private class NetException(message: String) : Exception(message)

    private fun post(body: JSONObject): String {
        val conn = (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TimeUnit.SECONDS.toMillis(timeoutSec.toLong().coerceAtLeast(4)).toInt()
            readTimeout = TimeUnit.SECONDS.toMillis(timeoutSec.toLong().coerceAtLeast(4)).toInt()
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) throw NetException("接口返回 $code：${text.take(100)}")
            val o = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw NetException("接口返回的内容不是 JSON：${text.take(80)}")
            }
            val choices = o.optJSONArray("choices") ?: throw NetException("接口没给 choices")
            val msg = choices.optJSONObject(0)?.optJSONObject("message")
                ?: throw NetException("接口没给 message")
            return msg.optString("content", "")
        } finally {
            conn.disconnect()
        }
    }

    /** 模型常带 ```json 包裹或说几句话，这里只把第一个完整 JSON 对象抠出来。 */
    private fun extractJson(text: String): JSONObject? {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        try {
            return JSONObject(trimmed)
        } catch (e: Exception) {
            // 继续找
        }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(trimmed.substring(start, end + 1))
        } catch (e: Exception) {
            null
        }
    }

    private fun userPrompt(context: String, level: String, feedback: String): String {
        val sb = StringBuilder()
        sb.append("你的打法偏好：").append(level).append("\n\n")
        sb.append(context)
        if (feedback.isNotEmpty()) sb.append("\n\n").append(feedback)
        sb.append("\n\n请给出这一回合的计划，只输出 JSON。")
        return sb.toString()
    }

    companion object {
        private const val SYSTEM_PROMPT = """你在玩一局大富翁（棋盘 40 格，钱够就能买地、在自己地块上升级、也能跟别人换地）。
请只输出一个 JSON 对象，不要任何解释文字，格式如下：
{
  "why": "一句话说明你这步的想法（中文，20 字以内）",
  "buy": {"want": true, "maxPrice": 500},
  "land": {"upgrade": false, "maxCost": 300},
  "trade": {"target": "对方玩家名", "want": "你要的地块名", "give": "你出的地块名", "giveCash": 0},
  "memory": "留给下一回合自己的提醒，20 字以内"
}
buy 表示走到无主地块时要不要买；land 表示走到自己地块时要不要升级；
trade 可以省略（不需要换地就不要这个字段）。数值必须是整数，别写字符串。"""
    }
}
