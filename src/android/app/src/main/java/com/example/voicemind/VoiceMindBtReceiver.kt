package com.example.voicemind

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

interface WearableLink {
    fun connect()
    fun send(sequence: Int, type: Int, payload: ByteArray)
    fun close()
}

/** One connection, one reader. Closing its socket also interrupts a blocked connect/read/write. */
class VoiceMindBtReceiver(
    private val context: Context,
    private val address: String,
    private val onConnected: () -> Unit,
    private val onDisconnected: (String) -> Unit,
    private val onPacket: (VoiceMindWire.Packet) -> Unit,
) : WearableLink {
    private val closed = AtomicBoolean(false)
    @Volatile private var socket: BluetoothSocket? = null
    private val outputLock = Any()
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val reported = AtomicBoolean(false)
    private fun timeout() {
        if (reported.compareAndSet(false, true) && !closed.get()) onDisconnected("Превышено время ожидания Bluetooth")
        close()
    }
    @SuppressLint("MissingPermission")
    override fun connect() {
        thread(name = "VoiceMind-Bluetooth", isDaemon = true) {
            try {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?: error("Bluetooth недоступен")
                check(adapter.isEnabled) { "Включите Bluetooth" }
                val device = adapter.bondedDevices.firstOrNull { it.address == address }
                    ?: error("Сначала выполните сопряжение в настройках Bluetooth")
                // Discovery is managed by system Settings; cancel only when SCAN is granted.
                try { adapter.cancelDiscovery() } catch (_: SecurityException) { }
                val candidate = device.createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                synchronized(this) {
                    if (closed.get()) { candidate.close(); return@thread }
                    socket = candidate
                }
                val deadline = timers.schedule({ timeout() }, 8, TimeUnit.SECONDS)
                try { candidate.connect() } finally { deadline.cancel(false) }
                if (closed.get()) return@thread
                onConnected()
                val parser = VoiceMindPacketParser(onPacket = onPacket)
                val bytes = ByteArray(4096)
                val input = candidate.inputStream
                while (!closed.get()) {
                    val count = input.read(bytes)
                    check(count > 0) { "Устройство завершило передачу" }
                    parser.feed(bytes, count)
                }
            } catch (e: Exception) {
                if (!closed.get() && reported.compareAndSet(false, true)) onDisconnected(e.message ?: "Соединение прервано")
            } finally { close() }
        }
    }
    override fun send(sequence: Int, type: Int, payload: ByteArray) = synchronized(outputLock) {
        check(!closed.get()) { "Bluetooth отключён" }
        val output = socket?.outputStream ?: error("Bluetooth ещё не подключён")
        val deadline = timers.schedule({ timeout() }, 5, TimeUnit.SECONDS)
        try { output.write(VoiceMindWire.encode(sequence, type, payload)); output.flush() }
        finally { deadline.cancel(false) }
    }
    override fun close() {
        closed.set(true)
        timers.shutdownNow()
        synchronized(this) {
            try { socket?.close() } catch (_: Exception) { }
            socket = null
        }
    }
}
