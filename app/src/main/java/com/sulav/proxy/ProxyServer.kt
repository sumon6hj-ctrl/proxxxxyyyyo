package com.sulav.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class ProxyServer(
    private val host: String,
    private val port: Int,
    private val target: String,
    private val onEvent: (Capture) -> Unit,
    private val onError: (String) -> Unit
) {
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private var socket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        pool.execute {
            try {
                socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(host, port), 128) }
                while (running) {
                    val client = socket!!.accept()
                    pool.execute { handle(client) }
                }
            } catch (e: Exception) {
                if (running) onError("Proxy error: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: Socket) {
        client.use { s ->
            try {
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ", limit = 3)
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1]
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val p = line.indexOf(':')
                    if (p > 0) headers[line.substring(0, p).trim()] = line.substring(p + 1).trim()
                }
                if (method == "CONNECT") {
                    handleConnect(path, headers, input, output, s)
                    return
                }
                if (path == "/download" || path.endsWith(".json")) {
                    val body = "{\"serverLoginUrl\":\"http://$host:$port/\",\"forwardTarget\":\"$target\"}"
                    writeResponse(output, 200, "application/json", body.toByteArray())
                    return
                }
                val length = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull() ?: 0
                val body = readExactly(input, length)
                val upstreamUrl = target.trimEnd('/') + (if (path.startsWith('/')) path else "/$path")
                val conn = URL(upstreamUrl).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = false
                headers.forEach { (k, v) -> if (!k.equals("Host", true) && !k.equals("Content-Length", true) && !k.equals("Connection", true)) conn.setRequestProperty(k, v) }
                if (method != "GET" && method != "DELETE") { conn.doOutput = true; conn.outputStream.use { it.write(body) } }
                val status = conn.responseCode
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val response = stream?.use { it.readBytes() } ?: ByteArray(0)
                writeResponse(output, status, conn.contentType ?: "application/octet-stream", response)
                val safeHeaders = headers.entries.joinToString("\n") { (k,v) -> if(k.equals("Authorization",true)) "$k: [REDACTED]" else "$k: $v" }
                onEvent(Capture(method, upstreamUrl, status, hex(body,512), hex(response,512), safeHeaders, body.size.toLong(), response.size.toLong()))
                conn.disconnect()
            } catch (e: Exception) {
                runCatching { writeResponse(outputFor(client), 502, "text/plain", (e.message ?: "Bad Gateway").toByteArray(StandardCharsets.UTF_8)) }
                onError("Request failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun handleConnect(path: String, headers: Map<String,String>, input: BufferedInputStream, output: BufferedOutputStream, client: Socket) {
        val hp = path.split(":", limit = 2)
        val hostName = hp[0]
        val portNum = hp.getOrNull(1)?.toIntOrNull() ?: 443
        val upstream = Socket()
        var upIn: BufferedInputStream? = null
        var upOut: BufferedOutputStream? = null
        try {
            upstream.connect(InetSocketAddress(hostName, portNum), 10000)
            upIn = BufferedInputStream(upstream.getInputStream())
            upOut = BufferedOutputStream(upstream.getOutputStream())
            output.write("HTTP/1.1 200 Connection Established\r\nConnection: keep-alive\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)); output.flush()
            val safeHeaders = headers.entries.joinToString("\n") { (k,v) -> if(k.equals("Proxy-Authorization",true)) "$k: [REDACTED]" else "$k: $v" }
            val bytes = longArrayOf(0,0)
            val t1 = pool.submit { copyUntilEnd(input, upOut, bytes, 0) }
            val t2 = pool.submit { copyUntilEnd(upIn, output, bytes, 1) }
            t1.get(); t2.get()
            onEvent(Capture("CONNECT", "https://$hostName:$portNum", 200, "", "", safeHeaders, bytes[0], bytes[1]))
        } finally { runCatching { upstream.close() } }
    }

    private fun copyUntilEnd(input: BufferedInputStream?, output: BufferedOutputStream?, bytes: LongArray, index: Int) {
        if (input == null || output == null) return
        val buf = ByteArray(16384)
        try { while (true) { val n=input.read(buf); if(n<0) break; if(n==0) continue; output.write(buf,0,n); output.flush(); synchronized(bytes){bytes[index]+=n} } } catch (_: IOException) {}
    }

    private fun readExactly(input: BufferedInputStream, length: Int): ByteArray { if(length<=0)return ByteArray(0); val b=ByteArray(length); var o=0; while(o<length){val n=input.read(b,o,length-o);if(n<0)break;o+=n};return if(o==length)b else b.copyOf(o) }
    private fun readLine(input: BufferedInputStream): String? { val out=StringBuilder(); while(true){val b=input.read();if(b==-1)return if(out.isEmpty())null else out.toString();if(b=='\n'.code)break;if(b!='\r'.code)out.append(b.toChar());if(out.length>8192)return null};return out.toString() }
    private fun writeResponse(out: BufferedOutputStream,status:Int,type:String,body:ByteArray){val reason=when(status){200->"OK";201->"Created";204->"No Content";400->"Bad Request";404->"Not Found";502->"Bad Gateway";else->"Response"};out.write("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1));out.write(body);out.flush()}
    private fun outputFor(s:Socket)=BufferedOutputStream(s.getOutputStream())
    private fun hex(b:ByteArray,n:Int)=b.copyOfRange(0,minOf(b.size,n)).joinToString(" "){String.format("%02X",it)}
}
