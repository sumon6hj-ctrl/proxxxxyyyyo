package com.sulav.proxy

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object CaptureStore {
    private const val FILE = "captures.json"
    private val lock = Any()

    fun load(dir: File): MutableList<Capture> = synchronized(lock) {
        val file = File(dir, FILE)
        if (!file.exists()) return@synchronized mutableListOf()
        runCatching {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Capture(
                    o.optString("method"), o.optString("url"), o.optInt("status"),
                    o.optString("requestHex"), o.optString("responseHex"), o.optString("headers"),
                    o.optLong("requestBytes", 0), o.optLong("responseBytes", 0)
                )
            }
        }.getOrElse { mutableListOf() }
    }

    fun append(dir: File, capture: Capture) = synchronized(lock) {
        val list = load(dir)
        list.add(capture)
        val arr = JSONArray()
        list.forEach { c -> arr.put(JSONObject().apply {
            put("method", c.method); put("url", c.url); put("status", c.status)
            put("requestHex", c.requestHex); put("responseHex", c.responseHex)
            put("headers", c.headers); put("requestBytes", c.requestBytes); put("responseBytes", c.responseBytes)
        }) }
        File(dir, FILE).writeText(arr.toString())
    }

    fun clear(dir: File) = synchronized(lock) { File(dir, FILE).delete() }
}
