package com.neondrive.emu.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.TimeUnit

/** A reliable, ordered message pipe between the two players. */
interface Transport {
    /** Messages from the other player. Closed when the connection ends. */
    val incoming: Channel<ByteArray>
    /** Non-blocking: queues the message (safe from the GL thread). */
    fun send(message: ByteArray)
    fun close()
    /** Short label for the UI, e.g. "شبكة البيت" / "إنترنت". */
    val label: String
    val isLan: Boolean
}

/** Same Wi-Fi / hotspot: a plain TCP socket with length-prefixed frames. */
class TcpTransport(private val socket: Socket) : Transport {
    override val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val label = "شبكة البيت"
    override val isLan = true

    init {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        scope.launch {
            runCatching {
                val input = DataInputStream(socket.getInputStream().buffered())
                while (true) {
                    val len = input.readInt()
                    require(len in 0..(4 shl 20)) { "bad frame $len" }
                    val buf = ByteArray(len); input.readFully(buf)
                    incoming.send(buf)
                }
            }
            incoming.close()
            close()
        }
        scope.launch {
            runCatching {
                val out = DataOutputStream(socket.getOutputStream().buffered())
                for (msg in outgoing) {
                    out.writeInt(msg.size); out.write(msg)
                    if (outgoing.isEmpty) out.flush()
                }
            }
            close()
        }
    }

    override fun send(message: ByteArray) { outgoing.trySend(message) }

    override fun close() {
        runCatching { socket.close() }
        outgoing.close()
        incoming.close()
        scope.cancel()
    }
}

/**
 * Internet: both phones connect to the relay (Cloudflare Worker, shared with DragonNES) with the same room code,
 * and the relay forwards every binary message to the other one. Text messages are relay events.
 */
class RelayTransport private constructor(
    private val ws: WebSocket,
    override val incoming: Channel<ByteArray>,
) : Transport {
    override val label = "إنترنت"
    override val isLan = false

    override fun send(message: ByteArray) { ws.send(message.toByteString()) }

    override fun close() {
        runCatching { ws.close(1000, "bye") }
        incoming.close()
    }

    companion object {
        private val client by lazy {
            OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        /**
         * Connects to [relayUrl]/room/[code]. [onEvent] receives relay events
         * ("peer-joined", "peer-left", "room-full") and connection errors.
         */
        fun connect(relayUrl: String, code: String, host: Boolean, onEvent: (String) -> Unit): RelayTransport {
            val incoming = Channel<ByteArray>(Channel.UNLIMITED)
            val base = relayUrl.trim().trimEnd('/')
                .replaceFirst(Regex("^https://"), "wss://").replaceFirst(Regex("^http://"), "ws://")
            val url = "$base/room/${code.uppercase()}?role=${if (host) "host" else "guest"}&v=${NetplaySession.PROTO}&app=neondrive"
            val ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = onEvent("open")
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { incoming.trySend(bytes.toByteArray()) }
                override fun onMessage(webSocket: WebSocket, text: String) = onEvent(text)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    incoming.close(); onEvent("closed")
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    incoming.close(); onEvent("error:${t.message ?: t.javaClass.simpleName}")
                }
            })
            return RelayTransport(ws, incoming)
        }
    }
}
