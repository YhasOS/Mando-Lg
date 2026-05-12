package com.josecarlos.lgremote

import android.app.Activity
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class MainActivity : Activity() {

    private lateinit var ipInput: EditText
    private lateinit var status: TextView
    private lateinit var devicesBox: LinearLayout
    private var currentIp: String = ""

    private val prefs by lazy {
        getSharedPreferences("lg_remote", MODE_PRIVATE)
    }

    private val client: OkHttpClient by lazy {
        unsafeClient()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentIp = prefs.getString("tv_ip", "") ?: ""

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(28, 42, 28, 28)
        root.setBackgroundColor(0xFF111827.toInt())

        val title = TextView(this)
        title.text = "LG Remote"
        title.textSize = 36f
        title.setTextColor(0xFFFFFFFF.toInt())
        title.setPadding(0, 0, 0, 24)
        root.addView(title)

        ipInput = EditText(this)
        ipInput.setText(currentIp)
        ipInput.hint = "IP TV, ej. 192.168.0.11"
        ipInput.inputType = InputType.TYPE_CLASS_PHONE
        ipInput.textSize = 20f
        ipInput.setTextColor(0xFFFFFFFF.toInt())
        ipInput.setHintTextColor(0xFF94A3B8.toInt())
        ipInput.setBackgroundColor(0xFF1E293B.toInt())
        ipInput.setPadding(22, 18, 22, 18)
        root.addView(ipInput, LinearLayout.LayoutParams(-1, -2))

        val scanBtn = button("Buscar TV en la red")
        root.addView(scanBtn)

        val pairBtn = button("Vincular TV")
        root.addView(pairBtn)

        devicesBox = LinearLayout(this)
        devicesBox.orientation = LinearLayout.VERTICAL
        root.addView(devicesBox)

        val grid = GridLayout(this)
        grid.columnCount = 3
        grid.setPadding(0, 18, 0, 0)

        val keys = listOf(
            "HOME" to "HOME",
            "BACK" to "ATRÁS",
            "ENTER" to "OK",
            "UP" to "↑",
            "DOWN" to "↓",
            "LEFT" to "←",
            "RIGHT" to "→",
            "VOLUMEUP" to "VOL+",
            "VOLUMEDOWN" to "VOL-",
            "MUTE" to "MUTE",
            "CHANNELUP" to "CH+",
            "CHANNELDOWN" to "CH-"
        )

        for ((key, label) in keys) {
            val b = button(label)
            val p = GridLayout.LayoutParams()
            p.width = 0
            p.height = 120
            p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            p.setMargins(8, 8, 8, 8)
            grid.addView(b, p)

            b.setOnClickListener {
                saveIp()
                runAsync("Enviando $label...") {
                    sendButton(currentIp, key)
                    "OK"
                }
            }
        }

        root.addView(grid)

        status = TextView(this)
        status.text = "Pulsa Buscar TV o escribe la IP y vincula."
        status.textSize = 17f
        status.setTextColor(0xFFFFFFFF.toInt())
        status.setBackgroundColor(0xFF1E293B.toInt())
        status.setPadding(20, 20, 20, 20)
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)

        scanBtn.setOnClickListener {
            runAsync("Buscando TVs LG en la red...") {
                val found = discoverLgTvs()
                runOnUiThread {
                    devicesBox.removeAllViews()
                    if (found.isEmpty()) {
                        setStatus("No encontré la TV por búsqueda automática. Escribe la IP manualmente.")
                    } else {
                        setStatus("Encontradas ${found.size} TV/dispositivos LG.")
                        found.forEach { tv ->
                            val row = button("${tv.name}\n${tv.ip}")
                            row.setOnClickListener {
                                ipInput.setText(tv.ip)
                                saveIp()
                                setStatus("IP seleccionada: ${tv.ip}. Pulsa Vincular TV.")
                            }
                            devicesBox.addView(row)
                        }
                    }
                }
                ""
            }
        }

        pairBtn.setOnClickListener {
            saveIp()
            runAsync("Conectando... acepta el aviso en la TV.") {
                pair(currentIp)
                "TV vinculada. Ya puedes usar el mando."
            }
        }
    }

    private fun button(text: String): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 17f
        b.setTextColor(0xFFFFFFFF.toInt())
        b.setBackgroundColor(0xFFEC4899.toInt())
        b.gravity = Gravity.CENTER
        b.setPadding(8, 8, 8, 8)
        return b
    }

    private fun saveIp() {
        currentIp = ipInput.text.toString().trim()
        prefs.edit().putString("tv_ip", currentIp).apply()
    }

    private fun setStatus(s: String) {
        status.text = s
    }

    private fun runAsync(startMsg: String, work: () -> String) {
        setStatus(startMsg)
        Thread {
            try {
                val result = work()
                if (result.isNotBlank()) {
                    runOnUiThread { setStatus(result) }
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("ERROR: ${e.message}") }
            }
        }.start()
    }

    data class FoundTv(val name: String, val ip: String)

    private fun discoverLgTvs(): List<FoundTv> {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("lg-remote-ssdp")
        lock.setReferenceCounted(true)
        lock.acquire()

        val results = linkedMapOf<String, FoundTv>()

        try {
            val socket = DatagramSocket()
            socket.soTimeout = 1800

            val msg = """
                M-SEARCH * HTTP/1.1
                HOST: 239.255.255.250:1900
                MAN: "ssdp:discover"
                MX: 2
                ST: ssdp:all

            """.trimIndent().replace("\n", "\r\n").toByteArray()

            val packet = DatagramPacket(
                msg,
                msg.size,
                InetAddress.getByName("239.255.255.250"),
                1900
            )

            repeat(3) { socket.send(packet) }

            val start = System.currentTimeMillis()
            val buf = ByteArray(8192)

            while (System.currentTimeMillis() - start < 4500) {
                try {
                    val response = DatagramPacket(buf, buf.size)
                    socket.receive(response)
                    val text = String(response.data, 0, response.length)
                    val ip = response.address.hostAddress ?: continue
                    val lower = text.lowercase()

                    if (
                        lower.contains("lg") ||
                        lower.contains("webos") ||
                        lower.contains("lge") ||
                        lower.contains("mediarenderer")
                    ) {
                        val name = when {
                            lower.contains("webos") -> "LG webOS TV"
                            lower.contains("lge") -> "LG TV"
                            else -> "Dispositivo compatible"
                        }
                        results[ip] = FoundTv(name, ip)
                    }
                } catch (_: Exception) {}
            }

            socket.close()
        } finally {
            try { lock.release() } catch (_: Exception) {}
        }

        return results.values.toList()
    }

    private fun pair(ip: String) {
        if (ip.isBlank()) throw Exception("Falta la IP de la TV")

        val ws = openWebSocket(ip)
        val latch = CountDownLatch(1)
        var error: Exception? = null
        var paired = false

        val clientKey = prefs.getString("client_key_$ip", null)

        val payload = JSONObject()
        payload.put("pairingType", "PROMPT")
        if (!clientKey.isNullOrBlank()) {
            payload.put("client-key", clientKey)
        }

        val manifest = JSONObject()
        manifest.put("manifestVersion", 1)
        manifest.put("appVersion", "1.0")
        manifest.put("permissions", listOf(
            "LAUNCH",
            "CONTROL_INPUT_JOYSTICK",
            "CONTROL_INPUT_MEDIA_PLAYBACK",
            "CONTROL_AUDIO",
            "CONTROL_POWER",
            "READ_INSTALLED_APPS"
        ))
        payload.put("manifest", manifest)

        ws.listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    val p = json.optJSONObject("payload")
                    val key = p?.optString("client-key", "") ?: ""

                    if (key.isNotBlank()) {
                        prefs.edit().putString("client_key_$ip", key).apply()
                    }

                    if (type == "registered" || json.optString("id") == "register") {
                        paired = true
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    error = e
                    latch.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                error = Exception(t.message ?: "Fallo WebSocket")
                latch.countDown()
            }
        }

        ws.send(JSONObject()
            .put("id", "register")
            .put("type", "register")
            .put("payload", payload)
            .toString()
        )

        latch.await(30, TimeUnit.SECONDS)
        ws.close(1000, null)

        if (error != null) throw error!!
        if (!paired) throw Exception("No se autorizó. Acepta el aviso en la TV.")
    }

    private fun sendButton(ip: String, key: String) {
        if (ip.isBlank()) throw Exception("Falta la IP de la TV")
        val clientKey = prefs.getString("client_key_$ip", null)
            ?: throw Exception("Primero debes vincular la TV")

        val ws = openWebSocket(ip)
        val registerLatch = CountDownLatch(1)
        val socketLatch = CountDownLatch(1)
        var inputSocketPath: String? = null
        var error: Exception? = null

        ws.listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)

                    if (json.optString("id") == "register" || json.optString("type") == "registered") {
                        registerLatch.countDown()
                    }

                    if (json.optString("id") == "input") {
                        inputSocketPath = json.optJSONObject("payload")?.optString("socketPath")
                        socketLatch.countDown()
                    }
                } catch (e: Exception) {
                    error = e
                    registerLatch.countDown()
                    socketLatch.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                error = Exception(t.message ?: "Fallo WebSocket")
                registerLatch.countDown()
                socketLatch.countDown()
            }
        }

        ws.send(JSONObject()
            .put("id", "register")
            .put("type", "register")
            .put("payload", JSONObject().put("client-key", clientKey))
            .toString()
        )

        registerLatch.await(8, TimeUnit.SECONDS)
        if (error != null) throw error!!

        ws.send(JSONObject()
            .put("id", "input")
            .put("type", "request")
            .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
            .toString()
        )

        socketLatch.await(8, TimeUnit.SECONDS)
        if (error != null) throw error!!

        val socketPath = inputSocketPath ?: throw Exception("La TV no devolvió socket de control")

        val inputWs = client.newWebSocket(
            Request.Builder().url(socketPath).build(),
            object : WebSocketListener() {}
        )

        Thread.sleep(500)
        inputWs.send("type:button\nname:$key\n\n")
        Thread.sleep(150)

        inputWs.close(1000, null)
        ws.close(1000, null)
    }

    private class Holder(var socket: WebSocket? = null, var listener: WebSocketListener? = null)

    private fun openWebSocket(ip: String): MutableSocket {
        return MutableSocket(client, "wss://$ip:3001")
    }

    class MutableSocket(private val client: OkHttpClient, private val url: String) {
        var listener: WebSocketListener? = null
        private var socket: WebSocket? = null

        private fun ensure(): WebSocket {
            if (socket == null) {
                socket = client.newWebSocket(
                    Request.Builder().url(url).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            listener?.onOpen(webSocket, response)
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            listener?.onMessage(webSocket, text)
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            listener?.onFailure(webSocket, t, response)
                        }
                    }
                )
                Thread.sleep(600)
            }
            return socket!!
        }

        fun send(text: String) {
            ensure().send(text)
        }

        fun close(code: Int, reason: String?) {
            socket?.close(code, reason)
        }
    }

    private fun unsafeClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}