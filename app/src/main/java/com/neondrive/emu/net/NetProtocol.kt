package com.neondrive.emu.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** NeonDrive netplay messages (one per transport frame; first byte = type). */
sealed class NetMsg {
    data class Hello(val proto: Int, val gameCrc: String, val name: String) : NetMsg()
    /** Host -> guest: (re)start from this exact state (deflated), in [epoch], with [delay] frames. */
    class Welcome(val epoch: Int, val delay: Int, val state: ByteArray) : NetMsg()
    data class Reject(val reason: String) : NetMsg()
    /** Buttons for frames [first] .. first + masks.size - 1. */
    class Input(val epoch: Int, val first: Long, val masks: ShortArray) : NetMsg()
    data class Check(val epoch: Int, val frame: Long, val crc: Int) : NetMsg()
    data class Ping(val time: Long) : NetMsg()
    data class Pong(val time: Long) : NetMsg()
    object Bye : NetMsg()
}

object NetProtocol {
    fun encode(m: NetMsg): ByteArray {
        val bo = ByteArrayOutputStream(32)
        DataOutputStream(bo).use { o ->
            when (m) {
                is NetMsg.Hello -> { o.writeByte(1); o.writeInt(m.proto); o.writeUTF(m.gameCrc); o.writeUTF(m.name) }
                is NetMsg.Welcome -> { o.writeByte(2); o.writeInt(m.epoch); o.writeByte(m.delay); o.writeInt(m.state.size); o.write(m.state) }
                is NetMsg.Reject -> { o.writeByte(3); o.writeUTF(m.reason) }
                is NetMsg.Input -> {
                    o.writeByte(4); o.writeInt(m.epoch); o.writeLong(m.first); o.writeByte(m.masks.size)
                    m.masks.forEach { o.writeShort(it.toInt()) }
                }
                is NetMsg.Check -> { o.writeByte(5); o.writeInt(m.epoch); o.writeLong(m.frame); o.writeInt(m.crc) }
                is NetMsg.Ping -> { o.writeByte(6); o.writeLong(m.time) }
                is NetMsg.Pong -> { o.writeByte(7); o.writeLong(m.time) }
                NetMsg.Bye -> o.writeByte(8)
            }
        }
        return bo.toByteArray()
    }

    fun decode(b: ByteArray): NetMsg? = runCatching {
        DataInputStream(ByteArrayInputStream(b)).use { i ->
            when (i.readUnsignedByte()) {
                1 -> NetMsg.Hello(i.readInt(), i.readUTF(), i.readUTF())
                2 -> {
                    val epoch = i.readInt(); val delay = i.readUnsignedByte()
                    val st = ByteArray(i.readInt()); i.readFully(st)
                    NetMsg.Welcome(epoch, delay, st)
                }
                3 -> NetMsg.Reject(i.readUTF())
                4 -> {
                    val epoch = i.readInt(); val first = i.readLong(); val n = i.readUnsignedByte()
                    NetMsg.Input(epoch, first, ShortArray(n) { i.readShort() })
                }
                5 -> NetMsg.Check(i.readInt(), i.readLong(), i.readInt())
                6 -> NetMsg.Ping(i.readLong())
                7 -> NetMsg.Pong(i.readLong())
                8 -> NetMsg.Bye
                else -> null
            }
        }
    }.getOrNull()

    /** Game states compress ~5-10x; keeps them well under the relay's 1 MB message limit. */
    fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_SPEED)
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream(data.size / 4 + 64)
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream(data.size * 6)
        val buf = ByteArray(64 * 1024)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }
}
