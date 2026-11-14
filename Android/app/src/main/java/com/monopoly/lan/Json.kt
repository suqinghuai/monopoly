package com.monopoly.lan

import org.json.JSONArray
import org.json.JSONObject

/** 几个 org.json 的小工具：省掉到处写 JSONObject.NULL 的重复代码。 */
object Json {

    fun nullable(value: Int?): Any = value ?: JSONObject.NULL

    fun nullable(value: String?): Any = value ?: JSONObject.NULL

    fun arrInts(list: List<Int>): JSONArray {
        val a = JSONArray()
        for (i in list) a.put(i)
        return a
    }

    /** 兼容单选 / 多选：None、单个数字、数组都收，统一成去重后的下标列表。 */
    fun readTileIds(value: Any?): List<Int> {
        if (value == null || value == JSONObject.NULL || value == "") return emptyList()
        val raw: List<Any?> = when (value) {
            is JSONArray -> (0 until value.length()).map { value.opt(it) }
            is Collection<*> -> value.toList()
            else -> listOf(value)
        }
        val out = ArrayList<Int>()
        for (x in raw) {
            if (x == null || x == JSONObject.NULL || x == "") continue
            val i = when (x) {
                is Number -> x.toInt()
                is String -> x.trim().toIntOrNull() ?: throw IllegalArgumentException("地块参数不对")
                else -> throw IllegalArgumentException("地块参数不对")
            }
            if (i < 0 || i >= Rules.BOARD_SIZE) throw IllegalArgumentException("没有这块地")
            if (!out.contains(i)) out.add(i)
        }
        out.sort()
        return out
    }

    fun optString(o: JSONObject, key: String, def: String = ""): String {
        if (!o.has(key) || o.isNull(key)) return def
        return o.optString(key, def)
    }

    fun optIntOrNull(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }

    fun charsOf(o: JSONObject, key: String): JSONArray = o.optJSONArray(key) ?: JSONArray()
}
