package com.guzlik.voice

/** Bounded decoder; rescan corrupt frames to recover valid frames embedded after a damaged length. */
class GuzlikVoicePacketParser(
    private val onAudio: ((ByteArray) -> Unit)? = null,
    private val onHeartbeat: (() -> Unit)? = null,
    private val onStatus: ((Int, Boolean) -> Unit)? = null,
    private val onPacket: ((GuzlikVoiceWire.Packet) -> Unit)? = null,
) {
    var crcErrors = 0L; private set
    var invalidLengths = 0L; private set
    var sequenceGaps = 0L; private set
    private var previousSequence: Int? = null
    private val buffer = ArrayList<Byte>(GuzlikVoiceWire.MAX_PAYLOAD + 7)
    fun feed(data: ByteArray, len: Int = data.size) {
        require(len in 0..data.size)
        for (i in 0 until len) {
            buffer.add(data[i])
            while (buffer.isNotEmpty()) {
                if (buffer[0] != 0xAA.toByte()) { buffer.removeAt(0); continue }
                if (buffer.size < 6) break
                val length = ((buffer[4].toInt() and 255) shl 8) or (buffer[5].toInt() and 255)
                if (length > GuzlikVoiceWire.MAX_PAYLOAD) { invalidLengths++; buffer.removeAt(0); continue }
                val total = length + 7
                if (buffer.size < total) break
                val bytes = ByteArray(total) { buffer[it] }
                if (GuzlikVoiceWire.crc(bytes, total - 1) != (bytes.last().toInt() and 255)) {
                    crcErrors++; buffer.removeAt(0); continue
                }
                buffer.subList(0, total).clear()
                val seq = ((bytes[1].toInt() and 255) shl 8) or (bytes[2].toInt() and 255)
                previousSequence?.let {
                    val distance = (seq - it) and 65535
                    if (distance in 2..32767) sequenceGaps += distance - 1
                    if (distance in 1..32767) previousSequence = seq
                }
                if (previousSequence == null) previousSequence = seq
                val payload = bytes.copyOfRange(6, total - 1)
                val type = bytes[3].toInt() and 255
                when (type) {
                    1 -> onAudio?.invoke(payload)
                    5 -> if (payload.size >= 2) onStatus?.invoke(payload[0].toInt() and 255, payload[1] != 0.toByte())
                    6 -> onHeartbeat?.invoke()
                }
                onPacket?.invoke(GuzlikVoiceWire.Packet(seq, type, payload))
            }
        }
    }
}

object GuzlikVoiceWire {
    const val MAX_PAYLOAD = 1024
    const val HELLO = 0x10
    const val AUDIO = 0x11
    const val STATUS = 0x12
    const val BUTTON = 0x13
    const val COMMAND = 0x20
    const val ACK = 0x21
    const val START = 1
    const val STOP = 2
    const val QUERY = 3
    data class Packet(val sequence: Int, val type: Int, val payload: ByteArray)
    fun crc(bytes: ByteArray, length: Int = bytes.size): Int {
        var crc = 0
        for (i in 0 until length) {
            crc = crc xor (bytes[i].toInt() and 255)
            repeat(8) { crc = if (crc and 128 != 0) ((crc shl 1) xor 0x31) and 255 else (crc shl 1) and 255 }
        }
        return crc
    }
    fun encode(sequence: Int, type: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(payload.size <= MAX_PAYLOAD)
        return ByteArray(payload.size + 7).also {
            it[0] = 0xAA.toByte(); it[1] = (sequence ushr 8).toByte(); it[2] = sequence.toByte()
            it[3] = type.toByte(); it[4] = (payload.size ushr 8).toByte(); it[5] = payload.size.toByte()
            payload.copyInto(it, 6); it[it.lastIndex] = crc(it, it.lastIndex).toByte()
        }
    }
    fun u32(data: ByteArray, offset: Int): Long = (0..3).fold(0L) { n, i -> (n shl 8) or (data[offset + i].toLong() and 255) }
    fun put32(data: ByteArray, offset: Int, value: Long) { repeat(4) { data[offset + it] = (value ushr (24 - it * 8)).toByte() } }
    fun command(op: Int, session: Long) = ByteArray(5).also { it[0] = op.toByte(); put32(it, 1, session) }
}
