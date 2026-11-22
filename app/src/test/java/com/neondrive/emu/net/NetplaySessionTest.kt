package com.neondrive.emu.net

import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import kotlin.random.Random

/** In-memory pipe with random latency (keeps order, like TCP / the relay). */
private class FakeLink(private val net: ScheduledExecutorService, private val maxLatencyMs: Int, seed: Int) {
    private val rnd = Random(seed)
    private var lastDue = 0L
    val a = End(); val b = End()
    inner class End : Transport {
        lateinit var peer: End
        override val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val label = "test"
        override val isLan = true
        override fun send(message: ByteArray) {
            synchronized(this@FakeLink) {
                val due = maxOf(System.nanoTime() + rnd.nextInt(maxLatencyMs + 1) * 1_000_000L, lastDue)
                lastDue = due
                net.schedule({ peer.incoming.trySend(message) }, due - System.nanoTime(), TimeUnit.NANOSECONDS)
            }
        }
        override fun close() { incoming.close(); peer.incoming.close() }
    }
    init { a.peer = b; b.peer = a }
}

/** Kotlin twin of the native lockstep (netplay.h) driving a tiny deterministic "game". */
private class FakeEmu(seed: Int, private val checkEvery: Int) : NetEngine {
    private val lock = Object()
    private val rnd = Random(seed)
    var active = false; var host = true; var delay = 3
    var frame = 0L; var starts = 0
    private var localQueued = false
    private val local = HashMap<Long, Int>(); private val remote = HashMap<Long, Int>()
    private val outbox = ArrayDeque<Pair<Long, Int>>(); private val checks = ArrayDeque<Pair<Long, Int>>()
    var state = 1L
    @Volatile var stopAtFrame = Long.MAX_VALUE
    @Volatile var corruptAtFrame = -1L

    override fun start(host: Boolean, delay: Int) = synchronized(lock) {
        active = true; this.host = host; this.delay = delay; frame = 0; localQueued = false; starts++
        local.clear(); remote.clear(); outbox.clear(); checks.clear()
        for (f in 0 until delay) { local[f.toLong()] = 0; remote[f.toLong()] = 0 }
    }
    override fun stop() = synchronized(lock) { active = false }
    override fun pushRemote(frame: Long, mask: Int) = synchronized(lock) { if (active && frame >= this.frame) remote[frame] = mask }
    override fun pollLocal(frames: LongArray, masks: IntArray): Int = synchronized(lock) {
        var n = 0
        while (n < frames.size && outbox.isNotEmpty()) { val (f, m) = outbox.removeFirst(); frames[n] = f; masks[n] = m; n++ }
        n
    }
    override fun pollChecks(frames: LongArray, crcs: IntArray): Int = synchronized(lock) {
        var n = 0
        while (n < frames.size && checks.isNotEmpty()) { val (f, c) = checks.removeFirst(); frames[n] = f; crcs[n] = c; n++ }
        n
    }
    override fun resetCheats() {}

    fun runOnEmu(block: () -> Unit) = synchronized(lock) { block() }
    fun capture(): ByteArray = ByteBuffer.allocate(8).putLong(state).array()
    fun apply(b: ByteArray) { state = ByteBuffer.wrap(b).long }

    /** One emulation tick (like LibretroDroid::step). */
    fun tick() { synchronized(lock) { tickLocked() } }

    private fun tickLocked() {
        if (!active || frame >= stopAtFrame) return
        if (!localQueued) {
            val m = rnd.nextInt(0x1000)
            local[frame + delay] = m; outbox.addLast(frame + delay to m); localQueued = true
        }
        val theirs = remote[frame] ?: return
        val mine = local[frame] ?: 0
        val p1 = if (host) mine else theirs
        val p2 = if (host) theirs else mine
        state = (state xor p1.toLong()) * 1099511628211L
        state = (state xor (p2.toLong() shl 16)) * 1099511628211L
        if (frame == corruptAtFrame) { state = state xor 0x5A5A; corruptAtFrame = -1 }
        local.remove(frame); remote.remove(frame); frame++; localQueued = false
        if (frame % checkEvery == 0L) {
            val crc = CRC32().apply { update(capture()) }.value.toInt()
            checks.addLast(frame to crc)
        }
    }
}

class NetplaySessionTest {

    @Test
    fun protocolRoundTrip() {
        val msgs = listOf(
            NetMsg.Hello(2, "ABCD1234", "phone"),
            NetMsg.Input(3, 42L, shortArrayOf(1, 2, -1)),
            NetMsg.Check(3, 600L, 0x12345678),
            NetMsg.Ping(99L), NetMsg.Pong(99L), NetMsg.Bye, NetMsg.Reject("x"),
        )
        for (m in msgs) {
            val d = NetProtocol.decode(NetProtocol.encode(m))
            when (m) {
                is NetMsg.Input -> { d as NetMsg.Input; assertEquals(m.first, d.first); assertTrue(m.masks.contentEquals(d.masks)) }
                else -> assertEquals(m, d)
            }
        }
        val state = ByteArray(1_036_288) { (it % 251).toByte() }
        val w = NetProtocol.decode(NetProtocol.encode(NetMsg.Welcome(1, 5, NetProtocol.deflate(state)))) as NetMsg.Welcome
        assertTrue(state.contentEquals(NetProtocol.inflate(w.state)))
        assertTrue("state must compress under the relay's 1 MB limit", NetProtocol.deflate(state).size < 1_000_000)
    }

    @Test
    fun twoPhonesStayInSyncAndRecoverFromDesync() {
        // One delivery thread: messages arrive in order, like TCP / a WebSocket.
        val net = Executors.newSingleThreadScheduledExecutor()
        val link = FakeLink(net, maxLatencyMs = 15, seed = 7)
        val hostEmu = FakeEmu(1, checkEvery = 120)
        val guestEmu = FakeEmu(2, checkEvery = 120)
        val resyncs = AtomicInteger()
        val ended = AtomicInteger()

        fun listener(emu: FakeEmu) = object : NetplaySession.Listener {
            override fun onEmulationThread(block: () -> Unit) = emu.runOnEmu(block)
            override fun onWaiting(info: String) {}
            override fun onConnected(asHost: Boolean, online: Boolean) {}
            override fun onPing(ms: Int) {}
            override fun onResync(reason: String) { resyncs.incrementAndGet() }
            override fun onEnded(reason: String) { ended.incrementAndGet() }
        }
        hostEmu.state = 12345L          // the host's game is already running somewhere
        val host = NetplaySession("CRC1", "game", listener(hostEmu), hostEmu)
        val guest = NetplaySession("CRC1", "game", listener(guestEmu), guestEmu)
        host.attachForTest(link.a, host = true, capture = hostEmu::capture, apply = null)
        guest.attachForTest(link.b, host = false, capture = null, apply = guestEmu::apply)

        guestEmu.corruptAtFrame = 300   // simulate a desync on the guest
        val emuThreads = listOf(hostEmu, guestEmu).map { emu ->
            Thread {
                val end = System.nanoTime() + 25_000_000_000L
                while (System.nanoTime() < end && !Thread.currentThread().isInterrupted) {
                    emu.tick(); Thread.sleep(1)
                    // After the automatic resync, run both to the same frame and stop there.
                    if (emu.starts >= 2 && emu.stopAtFrame == Long.MAX_VALUE) emu.stopAtFrame = 600
                    if (emu.starts >= 2 && emu.frame >= 600) break
                }
            }.apply { start() }
        }
        emuThreads.forEach { it.join(30_000) }

        val info = "host(frame=${hostEmu.frame}, starts=${hostEmu.starts}) guest(frame=${guestEmu.frame}, starts=${guestEmu.starts}) resyncs=${resyncs.get()}"
        assertEquals("no disconnect expected; $info", 0, ended.get())
        assertTrue("desync must trigger an automatic resync; $info", hostEmu.starts >= 2 && guestEmu.starts >= 2)
        assertEquals("exactly one resync expected; $info", hostEmu.starts, guestEmu.starts)
        assertEquals("both phones at the same frame; $info", hostEmu.frame, guestEmu.frame)
        assertEquals("identical game state after resync; $info", hostEmu.state, guestEmu.state)
        host.close(); guest.close(); net.shutdownNow()
    }
}
