package com.guzlik.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Disposable encrypted checkpoints, excluded from Android and GuzlikVoice backups. */
object DiarizationCheckpointStore {
    private const val TAG = "DiarCheckpoint"
    private val lock = Any()
    private fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "diarization")
    private fun file(ctx: Context, ts: Long) = File(dir(ctx), "$ts.aead")
    private fun marker(ctx: Context, ts: Long) = File(dir(ctx), "$ts.pending")
    fun hasPending(ctx: Context, ts: Long): Boolean = synchronized(lock) { marker(ctx,ts).exists() }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("guzlikvoice_diarization_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("guzlikvoice_diarization_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    fun save(ctx: Context, c: DiarizationCheckpointCodec.Checkpoint, ticket: DiarizationEvidenceCache.Ticket): Boolean = synchronized(lock) {
        if (c.key.timestamp != ticket.timestamp || !DiarizationEvidenceCache.isCurrent(ticket) || UserDataJobs.wiping) return false
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key()); updateAAD("diarization-v3:${c.key.timestamp}".toByteArray())
            }
            val encrypted = cipher.iv + cipher.doFinal(DiarizationCheckpointCodec.encode(c))
            if (!DiarizationEvidenceCache.isCurrent(ticket)) return false
            val folder = dir(ctx); check(folder.isDirectory || folder.mkdirs())
            if (!c.finished) marker(ctx,c.key.timestamp).createNewFile()
            val atomic = AtomicFile(file(ctx, c.key.timestamp))
            val output = atomic.startWrite()
            try { output.write(encrypted); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
            if (c.finished) marker(ctx,c.key.timestamp).delete()
            // Bound retained evidence to three recordings / 96 MiB, oldest first.
            val retained = folder.listFiles { f -> f.name.endsWith(".aead") }.orEmpty().sortedByDescending { it.lastModified() }
            var bytes = 0L
            retained.forEachIndexed { i, f -> bytes += f.length(); if (i >= 3 || bytes > 96L * 1024 * 1024) {
                AtomicFile(f).delete(); f.nameWithoutExtension.toLongOrNull()?.let { marker(ctx,it).delete() }
            } }
            true
        } catch (e: Exception) { Log.w(TAG, "Checkpoint unavailable: ${e.javaClass.simpleName}"); false }
    }
    fun load(ctx: Context, key: DiarizationEvidenceCache.Key): DiarizationCheckpointCodec.Checkpoint? = synchronized(lock) {
        read(ctx, key.timestamp)?.takeIf { it.key == key } ?: run { AtomicFile(file(ctx,key.timestamp)).delete(); marker(ctx,key.timestamp).delete(); null }
    }
    private fun read(ctx: Context, ts: Long): DiarizationCheckpointCodec.Checkpoint? {
        val f = file(ctx, ts)
        if (!f.exists() && !File(f.path + ".bak").exists()) {
            AtomicFile(f).delete() // discard an uncommitted .new left by process death
            marker(ctx,ts).delete()
            return null
        }
        return try {
            require(maxOf(f.length(),File(f.path + ".bak").length()) <= DiarizationCheckpointCodec.MAX_BYTES + 64)
            val raw = AtomicFile(f).readFully(); require(raw.size >= 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128,raw,0,12)); updateAAD("diarization-v3:$ts".toByteArray())
            }
            DiarizationCheckpointCodec.decode(cipher.doFinal(raw,12,raw.size-12)).let { checkpoint ->
                require(checkpoint.key.timestamp == ts)
                if (checkpoint.processed==checkpoint.total && !marker(ctx,ts).exists()) checkpoint.copy(finished=true) else checkpoint
            }
        } catch (e: Exception) { Log.w(TAG,"Discarding invalid checkpoint: ${e.javaClass.simpleName}"); AtomicFile(f).delete(); marker(ctx,ts).delete(); null }
    }
    /** Only incomplete jobs are offered; never starts work from Application.onCreate. */
    fun pending(ctx: Context): List<DiarizationCheckpointCodec.Checkpoint> = synchronized(lock) {
        dir(ctx).listFiles { f -> f.name.endsWith(".pending") }.orEmpty()
            .mapNotNull { it.nameWithoutExtension.toLongOrNull() }.mapNotNull { ts ->
                read(ctx,ts)?.also { if (it.finished) marker(ctx,ts).delete() }
            }.filter { !it.finished }
    }
    /** The evidence is immutable; completing a DB commit needs only a marker removal. */
    fun complete(ctx: Context, key: DiarizationEvidenceCache.Key, ticket: DiarizationEvidenceCache.Ticket): Boolean = synchronized(lock) {
        if(key.timestamp!=ticket.timestamp || !DiarizationEvidenceCache.isCurrent(ticket) || UserDataJobs.wiping || !file(ctx,key.timestamp).exists()) return false
        marker(ctx,key.timestamp).delete() || !marker(ctx,key.timestamp).exists()
    }
    fun remove(ctx: Context, ts: Long) = synchronized(lock) { AtomicFile(file(ctx,ts)).delete(); marker(ctx,ts).delete(); Unit }
    fun clear(ctx: Context) = synchronized(lock) { dir(ctx).deleteRecursively(); Unit }
}
