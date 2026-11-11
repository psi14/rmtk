package com.inkbridge.app.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Ligação ao PC. O PC é o servidor; o tablet liga-se a ele.
 *  - Wi-Fi: o PC anuncia-se por UDP (porta 47801) e o tablet encontra-o sozinho.
 *  - USB: no PC corre-se `adb reverse tcp:47800 tcp:47800` (a app do PC faz isso)
 *    e o tablet liga-se a 127.0.0.1.
 *
 * Tudo o que é público é chamado na thread principal.
 */
object Link {
    enum class State { OFF, CONNECTING, CONNECTED }

    data class Target(val name: String, val host: String, val port: Int) {
        val isUsb get() = host == USB_HOST
    }

    data class Discovered(val name: String, val host: String, val port: Int, val seenAt: Long)

    const val USB_HOST = "127.0.0.1"
    val USB_TARGET = Target("Cabo USB", USB_HOST, Protocol.TCP_PORT)

    var state = State.OFF; private set
    var target: Target? = null; private set
    var serverName: String? = null; private set
    var lastError: String? = null; private set

    /** Gera a mensagem com o caderno completo, enviada logo ao ligar. */
    var snapshotProvider: (() -> String?)? = null
    var deviceName: String = Build.MANUFACTURER + " " + Build.MODEL

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var queue = LinkedBlockingQueue<String>()
    @Volatile private var generation = 0
    @Volatile private var socket: Socket? = null

    // descoberta
    private val found = LinkedHashMap<String, Discovered>()
    @Volatile private var discovering = false
    private var discoveryThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    var onDiscovered: ((Discovered) -> Unit)? = null

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() = listeners.forEach { it() }

    fun discovered(): List<Discovered> {
        val now = System.currentTimeMillis()
        return found.values.filter { now - it.seenAt < 6000 }
    }

    // ------------------------------------------------------------------ envio

    /** Envia uma linha se houver ligação. Sem ligação, descarta (o caderno inteiro vai ao ligar). */
    fun send(line: String) {
        if (state == State.CONNECTED) queue.offer(line)
    }

    /** Volta a mandar o caderno completo (mudança de páginas, de caderno, etc.). */
    fun resync() {
        if (state == State.CONNECTED) snapshotProvider?.invoke()?.let { queue.offer(it) }
    }

    // ------------------------------------------------------------------ ligação

    fun connect(t: Target) {
        disconnect()
        target = t
        lastError = null
        val gen = ++generation
        state = State.CONNECTING
        notifyChanged()
        Thread({ runConnection(gen, t) }, "link-$gen").apply { isDaemon = true }.start()
    }

    fun disconnect() {
        generation++
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        target = null
        serverName = null
        if (state != State.OFF) {
            state = State.OFF
            notifyChanged()
        }
    }

    private fun runConnection(gen: Int, t: Target) {
        var backoff = 1000L
        while (gen == generation) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(t.host, t.port), 3000)
                s.tcpNoDelay = true
                s.keepAlive = true
                s.soTimeout = 7000
                socket = s
                val out = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), 64 * 1024)
                val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

                // Prepara a fila na thread principal: assim nenhuma mensagem se perde
                // nem chega antes do caderno completo.
                val ready = java.util.concurrent.CountDownLatch(1)
                val ok = java.util.concurrent.atomic.AtomicBoolean(false)
                main.post {
                    if (gen == generation) {
                        queue = LinkedBlockingQueue()
                        queue.offer(Protocol.hello(deviceName))
                        snapshotProvider?.invoke()?.let { queue.offer(it) }
                        state = State.CONNECTED
                        lastError = null
                        backoff = 1000L
                        notifyChanged()
                        ok.set(true)
                    }
                    ready.countDown()
                }
                ready.await(3, TimeUnit.SECONDS)
                if (!ok.get()) { s.close(); break }

                val q = queue
                val reader = Thread({ readLoop(gen, input) }, "link-read-$gen").apply { isDaemon = true; start() }
                writeLoop(gen, q, out)
                reader.interrupt()
            } catch (e: Exception) {
                if (gen == generation) main.post { lastError = e.message }
            } finally {
                try { s.close() } catch (_: Exception) {}
            }
            if (gen != generation) break
            main.post {
                if (gen == generation) {
                    state = State.CONNECTING
                    notifyChanged()
                }
            }
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(5000L)
        }
    }

    private fun writeLoop(gen: Int, q: LinkedBlockingQueue<String>, out: BufferedWriter) {
        while (gen == generation) {
            val line = q.poll(2, TimeUnit.SECONDS)
            if (line == null) {
                out.write(Protocol.PING); out.write('\n'.code); out.flush()
                continue
            }
            out.write(line); out.write('\n'.code)
            // junta o que já estiver na fila antes de enviar (menos pacotes)
            var n = 0
            while (n < 64) {
                val more = q.poll() ?: break
                out.write(more); out.write('\n'.code); n++
            }
            out.flush()
        }
    }

    private fun readLoop(gen: Int, input: BufferedReader) {
        try {
            while (gen == generation) {
                val line = try { input.readLine() } catch (e: SocketTimeoutException) { null } ?: break
                val o = try { JSONObject(line) } catch (e: Exception) { continue }
                when (o.optString("t")) {
                    "welcome" -> {
                        val name = o.optString("name")
                        main.post { if (gen == generation) { serverName = name; notifyChanged() } }
                    }
                }
            }
        } catch (_: Exception) {
        }
        // leitura parou (timeout ou fecho): força o reinício da ligação
        try { socket?.close() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ descoberta

    fun startDiscovery(ctx: Context) {
        if (discovering) return
        discovering = true
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("inkbridge").apply {
            setReferenceCounted(false)
            try { acquire() } catch (_: Exception) {}
        }
        discoveryThread = Thread({
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1000
                    bind(InetSocketAddress(Protocol.UDP_PORT))
                }
                val buf = ByteArray(2048)
                while (discovering) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (e: SocketTimeoutException) { continue }
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val o = try { JSONObject(text) } catch (e: Exception) { continue }
                    if (o.optString("t") != "beacon") continue
                    val d = Discovered(
                        name = o.optString("name", "PC"),
                        host = pkt.address.hostAddress ?: continue,
                        port = o.optInt("port", Protocol.TCP_PORT),
                        seenAt = System.currentTimeMillis(),
                    )
                    main.post {
                        found[d.name + "@" + d.host] = d
                        onDiscovered?.invoke(d)
                    }
                }
            } catch (e: Exception) {
                main.post { lastError = "Descoberta: ${e.message}" }
            } finally {
                sock?.close()
            }
        }, "link-discovery").apply { isDaemon = true; start() }
    }

    fun stopDiscovery() {
        discovering = false
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }
}
