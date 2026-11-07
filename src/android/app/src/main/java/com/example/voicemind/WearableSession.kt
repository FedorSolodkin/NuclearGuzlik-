package com.example.voicemind

/** Serialized by the service worker; independent of Android for stream fault tests. */
class WearableSession(
    private val send: (Int, ByteArray) -> Int,
    private val writePcm: (ByteArray) -> Unit,
    private val finished: (String?) -> Unit,
    private val clock: () -> Long,
) {
    enum class State { OFFLINE, READY, STARTING, RECORDING, STOPPING }
    var state = State.OFFLINE; private set
    var samples = 0L; private set
    var missingSamples = 0L; private set
    var receivedSamples = 0L; private set
    var battery: Int? = null; private set
    var sessionId: Long? = null; private set
    var ready = false; private set
    private var desired = false
    private var boot: Long? = null
    private var orphanId: Long? = null
    private data class Pending(val op: Int, var at: Long, var attempts: Int = 1)
    private var pending: Pending? = null
    private var disconnectedAt: Long? = null

    fun start(id: Long) {
        check(ready && sessionId == null) { "Дождитесь подключения устройства" }
        require(id in 1..0xFFFF_FFFFL)
        samples = 0; missingSamples = 0; receivedSamples = 0
        sessionId = id; desired = true
        issue(VoiceMindWire.START)
    }
    fun stop() {
        if (sessionId == null) return
        if (!desired) return
        desired = false; state = State.STOPPING
        if (ready) issue(VoiceMindWire.STOP)
    }
    fun disconnected() {
        ready = false; pending = null; state = State.OFFLINE
        if (sessionId != null && disconnectedAt == null) disconnectedAt = clock()
    }
    fun abort(reason: String) { if (sessionId != null) finish(reason) }
    fun tick() {
        disconnectedAt?.let { if (clock() - it >= 15_000) { finish("Связь не восстановлена за 15 секунд"); return } }
        val p = pending ?: return
        if (clock() - p.at < if (p.op == VoiceMindWire.STOP) 8000 else 1500) return
        if (p.attempts >= 3) { ready = false; finish("Устройство не подтвердило команду"); return }
        // Session + operation make retries idempotent even when transport sequence changes.
        send(VoiceMindWire.COMMAND, VoiceMindWire.command(p.op, sessionId ?: orphanId ?: 0))
        p.at = clock(); p.attempts++
    }
    fun accept(packet: VoiceMindWire.Packet) {
        val p = packet.payload
        when (packet.type) {
            VoiceMindWire.HELLO -> {
                check(p.size == 16 && p[0] == 2.toByte() && p[1] == 0x3E.toByte() && p[2] == 0x80.toByte()
                    && p[3] == 16.toByte() && p[4] == 1.toByte() && p[5] == 1.toByte() && p[6] == 0x40.toByte()) {
                    "Несовместимый формат: требуется PCM 16 кГц, 16 бит, моно, протокол 2"
                }
                val newBoot = VoiceMindWire.u32(p, 7)
                if (sessionId != null && boot != null && boot != newBoot) finish("Устройство перезапустилось; запись сохранена")
                boot = newBoot; ready = true; disconnectedAt = null
                if (sessionId != null) issue(if (desired) VoiceMindWire.START else VoiceMindWire.STOP)
                else if (p[11] != 0.toByte()) {
                    // A previous phone process died. Stop its capture before arming the button again.
                    orphanId = VoiceMindWire.u32(p, 12)
                    check(orphanId != 0L) { "Устройство сообщает неверный сеанс" }
                    issue(VoiceMindWire.STOP)
                    ready = false
                } else { orphanId = null; state = State.READY }
            }
            VoiceMindWire.AUDIO -> {
                check(p.size >= 10 && p.size <= 648 && (p.size - 8) % 2 == 0) { "Неверный аудиопакет" }
                if (sessionId == null || VoiceMindWire.u32(p, 0) != sessionId) return
                val offset = VoiceMindWire.u32(p, 4)
                val count = (p.size - 8) / 2
                if (offset + count <= samples) return
                padTo(offset)
                val skip = (samples - offset).coerceAtLeast(0).toInt()
                val pcm = p.copyOfRange(8 + skip * 2, p.size)
                writePcm(pcm); samples += pcm.size / 2; receivedSamples += pcm.size / 2
                check(samples <= 16000L * 60 * 60 * 8) { "Достигнут предел записи: 8 часов" }
            }
            VoiceMindWire.ACK -> {
                val expectedId = sessionId ?: orphanId ?: return
                if (p.size != 12 || VoiceMindWire.u32(p, 4) != expectedId) return
                val op = p[2].toInt() and 255
                if (op != pending?.op) return
                check(p[3] == 0.toByte()) { "Устройство отклонило команду ($op)" }
                pending = null
                if (orphanId != null && op == VoiceMindWire.STOP) {
                    orphanId = null; ready = true; state = State.READY; return
                }
                if (op == VoiceMindWire.START) state = State.RECORDING
                if (op == VoiceMindWire.STOP) {
                    val end = VoiceMindWire.u32(p, 8)
                    check(end >= samples) { "Устройство подтвердило неполную длину записи" }
                    padTo(end); finish(null)
                }
            }
            VoiceMindWire.STATUS -> if (p.size == 14) {
                battery = (p[1].toInt() and 255).takeIf { it <= 100 }
                if (state == State.RECORDING && p[0] == 0.toByte() && VoiceMindWire.u32(p, 2) == sessionId) {
                    padTo(VoiceMindWire.u32(p, 6))
                    finish("Устройство самостоятельно остановило запись")
                }
                // Buffered audio can still be in transit; only STOP ACK gives the final length.
            }
        }
    }
    private fun issue(op: Int) {
        state = if (op == VoiceMindWire.START) State.STARTING else State.STOPPING
        pending = Pending(op, clock())
        send(VoiceMindWire.COMMAND, VoiceMindWire.command(op, sessionId ?: orphanId ?: 0))
    }
    private fun padTo(offset: Long) {
        val gap = offset - samples
        check(gap <= 16000 * 60) { "Недостоверный таймкод аудио" }
        if (gap <= 0) return
        var left = gap
        while (left > 0) {
            val count = minOf(320L, left).toInt()
            writePcm(ByteArray(count * 2)); left -= count
        }
        samples += gap; missingSamples += gap
    }
    private fun finish(reason: String?) {
        sessionId = null; orphanId = null; desired = false; pending = null; disconnectedAt = null
        state = if (ready) State.READY else State.OFFLINE
        finished(reason)
    }
}
