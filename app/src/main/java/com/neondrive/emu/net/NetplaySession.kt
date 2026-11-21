package com.neondrive.emu.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.swordfish.libretrodroid.LibretroDroid
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Two-player netplay over the local Wi-Fi (or a phone hotspot).
 *
 * Host = player 1, guest = player 2. The guest connects, both check they have the same game
 * (CRC), the host sends its exact game state, then both switch the engine to lockstep and only
 * button presses travel over the network (a few bytes per frame).
 *
 * Protocol (TCP, big-endian):
 *   HELLO   1  int proto, utf crc, utf name        guest -> host
 *   WELCOME 2  int delay, int len, bytes state      host  -> guest
 *   REJECT  3  utf reason                           host  -> guest
 *   INPUT   4  long frame, short mask               both
 *   PING    5  long nanos                           both
 *   PONG    6  long nanos                           both
 *   BYE     7                                       both
 */
class NetplaySession(
    private val context: Context,
    private val gameCrc: String,
    private val gameTitle: String,
    private val listener: Listener,
) {
    interface Listener {
        /** Runs [block] on the emulation (GL) thread and waits for it. */
        fun onEmulationThread(block: () -> Unit)
        fun onWaiting(address: String)
        fun onConnected(peer: String, asHost: Boolean)
        fun onPing(ms: Int)
        fun onEnded(reason: String)
    }

    companion object {
        const val PORT = 47_700
        const val SERVICE_TYPE = "_neondrive._tcp."
        private const val PROTO = 1
        const val INPUT_DELAY = 3          // frames (~50 ms) of slack for Wi-Fi latency

        private const val HELLO: Byte = 1
        private const val WELCOME: Byte = 2
        private const val REJECT: Byte = 3
        private const val INPUT: Byte = 4
        private const val PING: Byte = 5
        private const val PONG: Byte = 6
        private const val BYE: Byte = 7

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
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private val writeLock = Any()
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // ------------------------------------------------------------------ host

    /** Player 1: waits for a guest; the game keeps running normally until one joins. */
    fun host(captureState: () -> ByteArray) {
        thread(name = "netplay-host") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                server = ss
                advertise()
                listener.onWaiting(localAddress() ?: "؟")

                val client = ss.accept()
                unadvertise()
                client.tcpNoDelay = true
                socket = client
                val input = DataInputStream(BufferedInputStream(client.getInputStream()))
                val out = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
                output = out

                if (input.readByte() != HELLO) throw IOException("bad hello")
                val proto = input.readInt()
                val crc = input.readUTF()
                val peerName = input.readUTF()
                if (proto != PROTO) { reject(out, "نسخة التطبيق مختلفة عند اللاعب الثاني، حدّثوا الاثنين"); return@thread }
                if (!crc.equals(gameCrc, ignoreCase = true)) {
                    reject(out, "اللعبة مختلفة! لازم نفس ملف اللعبة على الموبايلين"); return@thread
                }

                // Freeze one exact moment: state + switch to lockstep, in the same emulation tick.
                var state = ByteArray(0)
                listener.onEmulationThread {
                    LibretroDroid.resetCheat()
                    state = captureState()
                    LibretroDroid.netplayStart(true, INPUT_DELAY)
                }
                synchronized(writeLock) {
                    out.writeByte(WELCOME.toInt()); out.writeInt(INPUT_DELAY)
                    out.writeInt(state.size); out.write(state); out.flush()
                }
                begin(input, peerName, asHost = true)
            } catch (e: Exception) {
                if (!closed) end("انقطع الاتصال: ${e.message ?: "خطأ بالشبكة"}")
            }
        }
    }

    private fun reject(out: DataOutputStream, reason: String) {
        runCatching { out.writeByte(REJECT.toInt()); out.writeUTF(reason); out.flush() }
        end(reason)
    }

    // ------------------------------------------------------------------ guest

    /** Player 2: joins a host found on the Wi-Fi (or typed in). */
    fun join(address: String, port: Int = PORT, applyState: (ByteArray) -> Unit) {
        thread(name = "netplay-join") {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(address, port), 6_000)
                socket = s
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                output = out
                synchronized(writeLock) {
                    out.writeByte(HELLO.toInt()); out.writeInt(PROTO)
                    out.writeUTF(gameCrc); out.writeUTF(android.os.Build.MODEL ?: "Android"); out.flush()
                }
                when (input.readByte()) {
                    WELCOME -> {
                        val delay = input.readInt()
                        val state = ByteArray(input.readInt())
                        input.readFully(state)
                        listener.onEmulationThread {
                            LibretroDroid.resetCheat()
                            applyState(state)
                            LibretroDroid.netplayStart(false, delay)
                        }
                        begin(input, address, asHost = false)
                    }
                    REJECT -> end(input.readUTF())
                    else -> end("رد غير متوقع من السيرفر")
                }
            } catch (e: Exception) {
                if (!closed) end("تعذر الاتصال بالسيرفر: ${e.message ?: ""}")
            }
        }
    }

    // ------------------------------------------------------------------ running

    private fun begin(input: DataInputStream, peer: String, asHost: Boolean) {
        active = true
        listener.onConnected(peer, asHost)

        // Sender: buttons from the engine -> network, plus a ping every second.
        thread(name = "netplay-send") {
            val frames = LongArray(64)
            val masks = IntArray(64)
            var lastPing = 0L
            try {
                while (active) {
                    val n = LibretroDroid.netplayPollLocal(frames, masks)
                    val now = System.nanoTime()
                    val out = output ?: break
                    if (n > 0 || now - lastPing > 1_000_000_000L) {
                        synchronized(writeLock) {
                            for (i in 0 until n) {
                                out.writeByte(INPUT.toInt()); out.writeLong(frames[i]); out.writeShort(masks[i])
                            }
                            if (now - lastPing > 1_000_000_000L) {
                                out.writeByte(PING.toInt()); out.writeLong(now); lastPing = now
                            }
                            out.flush()
                        }
                    } else {
                        Thread.sleep(2)
                    }
                }
            } catch (e: Exception) {
                if (active && !closed) end("انقطع الاتصال باللاعب الثاني")
            }
        }

        // Receiver (this thread): buttons from the other player -> engine.
        try {
            while (active) {
                when (input.readByte()) {
                    INPUT -> LibretroDroid.netplayPushRemote(input.readLong(), input.readUnsignedShort())
                    PING -> {
                        val t = input.readLong()
                        synchronized(writeLock) { output?.apply { writeByte(PONG.toInt()); writeLong(t); flush() } }
                    }
                    PONG -> listener.onPing(((System.nanoTime() - input.readLong()) / 1_000_000L).toInt())
                    BYE -> { end("اللاعب الثاني طلع من اللعب الزوجي"); return }
                    else -> throw IOException("unknown message")
                }
            }
        } catch (e: Exception) {
            if (active && !closed) end("انقطع الاتصال باللاعب الثاني")
        }
    }

    // ------------------------------------------------------------------ discovery (host side)

    private fun advertise() = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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
        nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
        registration = reg
    }

    private fun unadvertise() {
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        registration = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    // ------------------------------------------------------------------ ending

    /** Leaves two-player mode (also tells the other phone). */
    fun close() {
        if (closed) return
        closed = true
        val wasActive = active
        active = false
        LibretroDroid.netplayStop()
        thread(name = "netplay-close") {
            if (wasActive) runCatching { synchronized(writeLock) { output?.apply { writeByte(BYE.toInt()); flush() } } }
            cleanup()
        }
    }

    private fun end(reason: String) {
        if (closed) return
        closed = true
        active = false
        LibretroDroid.netplayStop()
        cleanup()
        listener.onEnded(reason)
    }

    private fun cleanup() {
        unadvertise()
        runCatching { socket?.close() }
        runCatching { server?.close() }
        socket = null; server = null; output = null
    }
}
