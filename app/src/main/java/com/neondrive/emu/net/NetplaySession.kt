package com.neondrive.emu.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.swordfish.libretrodroid.LibretroDroid
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.ceil

/** What the session needs from the emulator (swappable for tests). */
interface NetEngine {
    fun start(host: Boolean, delay: Int)
    fun stop()
    fun pushRemote(frame: Long, mask: Int)
    fun pollLocal(frames: LongArray, masks: IntArray): Int
    fun pollChecks(frames: LongArray, crcs: IntArray): Int
    fun resetCheats()
}

object LibretroEngine : NetEngine {
    override fun start(host: Boolean, delay: Int) = LibretroDroid.netplayStart(host, delay)
    override fun stop() = LibretroDroid.netplayStop()
    override fun pushRemote(frame: Long, mask: Int) = LibretroDroid.netplayPushRemote(frame, mask)
    override fun pollLocal(frames: LongArray, masks: IntArray) = LibretroDroid.netplayPollLocal(frames, masks)
    override fun pollChecks(frames: LongArray, crcs: IntArray) = LibretroDroid.netplayPollChecks(frames, crcs)
    override fun resetCheats() = LibretroDroid.resetCheat()
}

/**
 * Two-player netplay, on the home Wi-Fi (TCP) or over the internet (room code via the relay).
 *
 * Host = player 1, guest = player 2. The guest says hello (same game CRC required), the host sends
 * its exact state and both engines switch to lockstep: only buttons travel. Every ~10 s both send a
 * checksum of the game state at the same frame; if they ever differ the host resends its state
 * (a new "epoch") and play continues in sync. Inputs are tagged with the epoch, and nothing is sent
 * for a new epoch until its state has gone out, so old and new never mix.
 */
class NetplaySession(
    private val gameCrc: String,
    private val gameTitle: String,
    private val listener: Listener,
    private val engine: NetEngine = LibretroEngine,
    private val context: Context? = null,
) {
    interface Listener {
        /** Runs [block] on the emulation (GL) thread and waits for it. */
        fun onEmulationThread(block: () -> Unit)
        fun onWaiting(info: String)
        fun onConnected(asHost: Boolean, online: Boolean)
        fun onPing(ms: Int)
        fun onResync(reason: String)
        fun onEnded(reason: String)
    }

    companion object {
        const val PROTO = 2
        const val PORT = 47_700
        const val SERVICE_TYPE = "_neondrive._tcp."
        const val LAN_DELAY = 3          // frames (~50 ms)
        const val ONLINE_DELAY = 5       // frames (~83 ms); raised automatically on slow links
        const val MAX_DELAY = 10
        private const val ONLINE_BATCH = 3
        private const val ONLINE_FLUSH_NS = 25_000_000L

        private const val ROOM_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        fun newRoomCode(): String = buildString { repeat(6) { append(ROOM_ALPHABET.random()) } }

        /** This phone's Wi-Fi / hotspot address, for showing and manual joining. */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { it != null && (it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.")) }
        }.getOrNull()
    }

    @Volatile var active = false; private set
    @Volatile private var closed = false
    @Volatile private var transport: Transport? = null
    private var isHost = false
    private var online = false
    @Volatile private var epoch = 0
    @Volatile private var delay = LAN_DELAY
    @Volatile private var canSend = false
    @Volatile private var rttMs = -1
    private var lastDelayChange = 0L
    private var captureState: (() -> ByteArray)? = null
    private var applyState: ((ByteArray) -> Unit)? = null
    private val myChecks = ConcurrentHashMap<Long, Int>()
    private val peerChecks = ConcurrentHashMap<Long, Int>()
    private val control = Executors.newSingleThreadExecutor()
    /** Held while polling+sending, and while switching epochs: the two never interleave. */
    private val sendLock = Any()

    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // ================================================================== start

    fun hostLan(capture: () -> ByteArray) {
        isHost = true; online = false; delay = LAN_DELAY; captureState = capture
        thread(name = "netplay-lan-host") {
            try {
                val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
                server = ss
                advertise()
                listener.onWaiting("بانتظار اللاعب الثاني • ${localAddress() ?: "?"}")
                val client = ss.accept()
                unadvertise()
                attach(TcpTransport(client))
            } catch (e: Exception) {
                if (!closed) end("تعذر تشغيل السيرفر: ${e.message ?: ""}")
            }
        }
    }

    fun joinLan(address: String, port: Int, apply: (ByteArray) -> Unit) {
        isHost = false; online = false; applyState = apply
        thread(name = "netplay-lan-join") {
            try {
                val s = Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(address, port), 6_000) }
                attach(TcpTransport(s))
                send(NetMsg.Hello(PROTO, gameCrc, android.os.Build.MODEL ?: "Android"))
            } catch (e: Exception) {
                if (!closed) end("تعذر الاتصال بالمستضيف: ${e.message ?: ""}")
            }
        }
    }

    fun hostOnline(relayUrl: String, code: String, capture: () -> ByteArray) {
        isHost = true; online = true; delay = ONLINE_DELAY; captureState = capture
        attach(RelayTransport.connect(relayUrl, code, host = true) { relayEvent(it, code) })
    }

    fun joinOnline(relayUrl: String, code: String, apply: (ByteArray) -> Unit) {
        isHost = false; online = true; applyState = apply
        attach(RelayTransport.connect(relayUrl, code, host = false) { relayEvent(it, code) })
    }

    /** Test hook / shared path: use an already-connected transport. */
    fun attachForTest(t: Transport, host: Boolean, capture: (() -> ByteArray)?, apply: ((ByteArray) -> Unit)?) {
        isHost = host; online = !t.isLan; delay = if (t.isLan) LAN_DELAY else ONLINE_DELAY
        captureState = capture; applyState = apply
        attach(t)
        if (!host) send(NetMsg.Hello(PROTO, gameCrc, "test"))
    }

    private fun relayEvent(event: String, code: String) {
        when {
            event == "open" -> listener.onWaiting(if (isHost) "كود الغرفة: $code • بانتظار اللاعب الثاني" else "يدخل الغرفة $code…")
            event == "peer-present" -> if (!isHost) send(NetMsg.Hello(PROTO, gameCrc, android.os.Build.MODEL ?: "Android"))
            event == "peer-joined" -> Unit // the guest says hello next
            event == "peer-left" -> end("اللاعب الثاني طلع من الغرفة")
            event == "no-host" -> end("ماكو غرفة بهذا الكود، تأكد منه")
            event == "room-full" -> end("الغرفة مليانة")
            event == "room-taken" -> end("الكود مستخدم، جرّب غرفة جديدة")
            event == "closed" -> if (!closed) end("انقطع الاتصال بسيرفر الأونلاين")
            event.startsWith("error:") -> if (!closed) end("تعذر الاتصال بسيرفر الأونلاين — تأكد من الإنترنت")
        }
    }

    private fun attach(t: Transport) {
        transport = t
        thread(name = "netplay-read") {
            runCatching {
                runBlocking { for (bytes in t.incoming) handle(NetProtocol.decode(bytes) ?: continue) }
            }
            if (!closed) end("انقطع الاتصال باللاعب الثاني")
        }
    }

    private fun send(m: NetMsg) { transport?.send(NetProtocol.encode(m)) }

    // ================================================================== messages

    private fun handle(m: NetMsg) {
        when (m) {
            is NetMsg.Hello -> if (isHost && !active) {
                when {
                    m.proto != PROTO -> reject("نسخة التطبيق مختلفة عند اللاعب الثاني، حدّثوا الاثنين")
                    !m.gameCrc.equals(gameCrc, ignoreCase = true) -> reject("اللعبة مختلفة! لازم نفس ملف اللعبة على الموبايلين")
                    else -> control.execute { startEpoch(first = true, reason = "") }
                }
            }
            is NetMsg.Welcome -> if (!isHost) {
                val state = NetProtocol.inflate(m.state)
                synchronized(sendLock) {
                    canSend = false
                    delay = m.delay
                    listener.onEmulationThread {
                        engine.resetCheats()
                        applyState?.invoke(state)
                        engine.start(false, m.delay)   // also drops anything queued for the old epoch
                    }
                    epoch = m.epoch
                    myChecks.clear(); peerChecks.clear()
                    canSend = true
                }
                if (!active) {
                    active = true
                    startSender()
                    listener.onConnected(asHost = false, online = online)
                } else listener.onResync("تمت إعادة المزامنة")
            }
            is NetMsg.Reject -> end(m.reason)
            is NetMsg.Input -> if (m.epoch == epoch) {
                for (i in m.masks.indices) engine.pushRemote(m.first + i, m.masks[i].toInt() and 0xFFFF)
            }
            is NetMsg.Check -> if (m.epoch == epoch) { peerChecks[m.frame] = m.crc; compare(m.frame) }
            is NetMsg.Ping -> send(NetMsg.Pong(m.time))
            is NetMsg.Pong -> {
                rttMs = ((System.nanoTime() - m.time) / 1_000_000L).toInt()
                listener.onPing(rttMs)
            }
            NetMsg.Bye -> end("اللاعب الثاني طلع من اللعب الزوجي")
        }
    }

    private fun reject(reason: String) {
        send(NetMsg.Reject(reason))
        end(reason)
    }

    /** Host: freeze one exact state, restart lockstep from it, send it (new epoch). */
    private fun startEpoch(first: Boolean, reason: String) {
        if (closed) return
        synchronized(sendLock) {
            canSend = false
            val nextEpoch = epoch + 1
            var state = ByteArray(0)
            listener.onEmulationThread {
                engine.resetCheats()
                state = captureState?.invoke() ?: ByteArray(0)
                engine.start(true, delay)          // also drops anything queued for the old epoch
            }
            epoch = nextEpoch
            myChecks.clear(); peerChecks.clear()
            // Welcome goes out before any input of the new epoch (same ordered pipe, same lock).
            send(NetMsg.Welcome(nextEpoch, delay, NetProtocol.deflate(state)))
            canSend = true
        }
        if (first) {
            active = true
            startSender()
            listener.onConnected(asHost = true, online = online)
        } else listener.onResync(reason)
    }

    private fun compare(frame: Long) {
        val mine = myChecks[frame] ?: return
        val theirs = peerChecks[frame] ?: return
        myChecks.remove(frame); peerChecks.remove(frame)
        if (!isHost) return
        if (mine != theirs) {
            control.execute { startEpoch(first = false, reason = "تمت إعادة المزامنة تلقائياً") }
            return
        }
        // Slow internet link: give it more frames of slack (needs a fresh start to take effect).
        if (online && rttMs > 0) {
            val wanted = (ceil((rttMs / 2.0 + 12.0) / 16.67).toInt() + 1).coerceIn(ONLINE_DELAY, MAX_DELAY)
            val now = System.nanoTime()
            if (wanted > delay + 1 && now - lastDelayChange > 30_000_000_000L) {
                lastDelayChange = now
                delay = wanted
                control.execute { startEpoch(first = false, reason = "تم ضبط التأخير حسب سرعة الإنترنت") }
            }
        }
    }

    // ================================================================== sending

    private fun startSender() = thread(name = "netplay-send") {
        val frames = LongArray(64); val masks = IntArray(64)
        val cFrames = LongArray(8); val cCrcs = IntArray(8)
        val pendingFirst = ArrayList<Long>(); val pendingMasks = ArrayList<Short>()
        var pendingSince = 0L
        var lastPing = 0L
        fun flush() {
            if (pendingMasks.isEmpty()) return
            // split into runs of consecutive frames
            var start = 0
            for (i in 1..pendingMasks.size) {
                if (i == pendingMasks.size || pendingFirst[i] != pendingFirst[i - 1] + 1) {
                    send(NetMsg.Input(epoch, pendingFirst[start], ShortArray(i - start) { pendingMasks[start + it] }))
                    start = i
                }
            }
            pendingFirst.clear(); pendingMasks.clear()
        }
        var pendingEpoch = -1
        try {
            while (!closed) {
                var worked = false
                synchronized(sendLock) {
                    if (pendingEpoch != epoch) { pendingFirst.clear(); pendingMasks.clear(); pendingEpoch = epoch }
                    if (canSend) {
                        val n = engine.pollLocal(frames, masks)
                        for (i in 0 until n) {
                            if (pendingMasks.isEmpty()) pendingSince = System.nanoTime()
                            pendingFirst += frames[i]; pendingMasks += masks[i].toShort()
                        }
                        val now = System.nanoTime()
                        if (pendingMasks.isNotEmpty() &&
                            (!online || pendingMasks.size >= ONLINE_BATCH || now - pendingSince > ONLINE_FLUSH_NS)) flush()
                        val c = engine.pollChecks(cFrames, cCrcs)
                        for (i in 0 until c) {
                            myChecks[cFrames[i]] = cCrcs[i]
                            send(NetMsg.Check(epoch, cFrames[i], cCrcs[i]))
                        }
                        for (i in 0 until c) compare(cFrames[i])
                        worked = n > 0 || c > 0
                    }
                }
                val now = System.nanoTime()
                if (now - lastPing > 1_000_000_000L) { send(NetMsg.Ping(now)); lastPing = now }
                if (!worked) Thread.sleep(2)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!closed) end("انقطع الاتصال باللاعب الثاني")
        }
    }

    // ================================================================== LAN discovery

    private fun advertise() = runCatching {
        val ctx = context ?: return@runCatching
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("neondrive-netplay").apply { setReferenceCounted(false); acquire() }
        val info = NsdServiceInfo().apply {
            serviceName = "NeonDrive · " + gameTitle.take(30)
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
        registration = reg
    }

    private fun unadvertise() {
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        registration = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    // ================================================================== ending

    /** Leaves two-player mode (and tells the other phone). */
    fun close() {
        if (closed) return
        closed = true
        active = false
        engine.stop()
        thread(name = "netplay-close") {
            runCatching { send(NetMsg.Bye); Thread.sleep(150) }
            cleanup()
        }
    }

    private fun end(reason: String) {
        if (closed) return
        closed = true
        active = false
        engine.stop()
        cleanup()
        listener.onEnded(reason)
    }

    private fun cleanup() {
        unadvertise()
        runCatching { transport?.close() }
        runCatching { server?.close() }
        transport = null; server = null
        control.shutdownNow()
    }
}
